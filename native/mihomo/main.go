//go:build android && cgo

package main

/*
#include <stdlib.h>

typedef int (*androidcyaml_protect_callback_t)(int fd);
typedef char* (*androidcyaml_resolve_callback_t)(
    int protocol,
    const char* source_address,
    int source_port,
    const char* destination_address,
    int destination_port
);

static __attribute__((unused)) int androidcyaml_call_protect(void* callback, int fd) {
    if (callback == NULL) {
        return 0;
    }
    return ((androidcyaml_protect_callback_t) callback)(fd);
}

static __attribute__((unused)) char* androidcyaml_call_resolve(
    void* callback,
    int protocol,
    const char* source_address,
    int source_port,
    const char* destination_address,
    int destination_port
) {
    if (callback == NULL) {
        return NULL;
    }
    return ((androidcyaml_resolve_callback_t) callback)(
        protocol,
        source_address,
        source_port,
        destination_address,
        destination_port
    );
}
*/
import "C"

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"os"
	"path/filepath"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"unsafe"

	androidcyamlcore "github.com/metacubex/mihomo/androidcyaml"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/geodata"
	"github.com/metacubex/mihomo/component/iface"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	MC "github.com/metacubex/mihomo/constant"
	MDNS "github.com/metacubex/mihomo/dns"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/hub/route"
	LC "github.com/metacubex/mihomo/listener/config"
	MLog "github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

const (
	embeddedIPv4Prefix = "172.19.0.1/30"
	embeddedIPv6Prefix = "fdfe:dcba:9876::1/126"
	embeddedMTU        = 9000

	// The process-owner lookup is a genuine Binder round trip into
	// ConnectivityService, and a goroutine waiting inside cgo owns its OS
	// thread. Bound those callers so a lookup burst parks in the Go scheduler
	// instead of making the runtime create hundreds of OS threads. Socket
	// protection no longer needs this: it leaves through a unix socket in pure
	// Go, see socket_protect.go.
	maxConcurrentPlatformCallbacks = 16
)

type nativeResponse struct {
	OK      bool            `json:"ok"`
	Error   string          `json:"error,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type embeddedOptions struct {
	FileDescriptor  int
	IPv6Enabled     bool
	ProcessMatching string
}

type diagnosticsSample struct {
	Metrics     map[string]uint64 `json:"metrics"`
	Unavailable []string          `json:"unavailable,omitempty"`
}

type startPayload struct {
	ControllerSecret string `json:"controllerSecret"`
	PrimarySelector  string `json:"primarySelector"`
}

type tunSpec struct {
	MTU                      uint32   `json:"mtu"`
	Inet4Address             []string `json:"inet4Address"`
	Inet6Address             []string `json:"inet6Address"`
	AutoRoute                bool     `json:"autoRoute"`
	Inet4RouteAddress        []string `json:"inet4RouteAddress"`
	Inet6RouteAddress        []string `json:"inet6RouteAddress"`
	Inet4RouteExcludeAddress []string `json:"inet4RouteExcludeAddress"`
	Inet6RouteExcludeAddress []string `json:"inet6RouteExcludeAddress"`
	DNSServerAddress         []string `json:"dnsServerAddress"`
	IncludePackage           []string `json:"includePackage"`
	ExcludePackage           []string `json:"excludePackage"`
}

var (
	runtimeMu sync.Mutex
	active    bool

	callbackMu             sync.RWMutex
	protectCallback        unsafe.Pointer
	resolveProcessCallback unsafe.Pointer
	platformCallbackLimit  = newCallbackLimiter(maxConcurrentPlatformCallbacks)
	platformSocketProtect  socketProtector

	// Dial-path tallies for the diagnostics sampler. Deltas between two samples
	// separate "the network is churning" (dialHookCalls climbing) from "dials
	// are being refused" (rejections climbing) from "the endpoint broke and we
	// are back on JNI" (jniFallbacks climbing).
	dialHookCalls        atomic.Uint64
	dialHookControlFails atomic.Uint64
	protectJniCalls      atomic.Uint64
	protectJniRejections atomic.Uint64
	protectJniFallbacks  atomic.Uint64
	processLookupCalls   atomic.Uint64
	processLookupMisses  atomic.Uint64

	// mihomo publishes every log event to its observable regardless of the
	// configured level, and a slow subscriber blocks the call site. Subscribe
	// only while diagnostics is on, and keep the pump a tight loop.
	coreLog       coreLogCounters
	coreLogMu     sync.Mutex
	coreLogEvents <-chan MLog.Event
)

func main() {}

//export AndroidCyamlInstallCallbacks
func AndroidCyamlInstallCallbacks(protectValue, resolveValue unsafe.Pointer) {
	callbackMu.Lock()
	protectCallback = protectValue
	resolveProcessCallback = resolveValue
	callbackMu.Unlock()
}

//export AndroidCyamlFree
func AndroidCyamlFree(value *C.char) {
	if value != nil {
		C.free(unsafe.Pointer(value))
	}
}

//export AndroidCyamlValidate
func AndroidCyamlValidate(homeValue, configValue *C.char) *C.char {
	defer releaseRebuildableMemory(false)

	home := C.GoString(homeValue)
	configPath := C.GoString(configValue)
	if err := initializeRuntimePaths(home, configPath); err != nil {
		return respond(nil, err)
	}
	configuration, err := os.ReadFile(configPath)
	if err == nil {
		_, err = executor.ParseWithBytes(configuration)
	}
	return respond(nil, err)
}

//export AndroidCyamlPrepareTun
func AndroidCyamlPrepareTun(
	homeValue,
	configValue,
	processMatchingValue *C.char,
	ipv6Value C.int,
) *C.char {
	home := C.GoString(homeValue)
	configPath := C.GoString(configValue)
	if err := initializeRuntimePaths(home, configPath); err != nil {
		return respond(nil, err)
	}
	configuration, err := os.ReadFile(configPath)
	if err != nil {
		return respond(nil, err)
	}
	cfg, err := executor.ParseWithBytes(configuration)
	if err != nil {
		return respond(nil, err)
	}
	payload, err := prepareEmbeddedConfig(cfg, embeddedOptions{
		FileDescriptor:  -1,
		IPv6Enabled:     ipv6Value != 0,
		ProcessMatching: C.GoString(processMatchingValue),
	})
	return respond(payload, err)
}

//export AndroidCyamlStart
func AndroidCyamlStart(
	homeValue,
	configValue,
	uiValue,
	controllerValue,
	logLevelValue,
	processMatchingValue *C.char,
	networkEnvironmentValue *C.char,
	protectEndpointValue *C.char,
	fileDescriptor,
	ipv6Value,
	lanWebUiPublicValue C.int,
) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if active {
		stopLocked()
	}
	if !callbacksInstalled() {
		return respond(nil, errors.New("Android JNI callbacks are not installed"))
	}
	dialer.SetDirectNetworkEnvironment(C.GoString(networkEnvironmentValue))

	home := C.GoString(homeValue)
	configPath := C.GoString(configValue)
	if err := initializeRuntimePaths(home, configPath); err != nil {
		return respond(nil, err)
	}
	cfg, err := executor.ParseWithPath(configPath)
	if err != nil {
		return respond(nil, err)
	}
	if cfg == nil || cfg.General == nil || cfg.Controller == nil {
		return respond(nil, errors.New("AndroidCyaml received an incomplete mihomo configuration"))
	}
	configuration, err := os.ReadFile(configPath)
	if err != nil {
		return respond(nil, fmt.Errorf("read selector configuration: %w", err))
	}
	rawCfg, err := config.UnmarshalRawConfig(configuration)
	if err != nil {
		return respond(nil, fmt.Errorf("parse selector configuration: %w", err))
	}
	primarySelector := firstConfiguredSelector(rawCfg)

	logLevelName := strings.ToLower(strings.TrimSpace(C.GoString(logLevelValue)))
	logLevel, found := MLog.LogLevelMapping[logLevelName]
	if !found {
		return respond(nil, fmt.Errorf("unsupported mihomo log level: %s", logLevelName))
	}
	if lanWebUiPublicValue != 0 && strings.TrimSpace(cfg.Controller.Secret) == "" {
		return respond(
			nil,
			errors.New("局域网公开 WebUI 需要 config.yaml 中设置非空 secret"),
		)
	}

	cfg.Controller.ExternalUI = C.GoString(uiValue)
	cfg.Controller.ExternalController = C.GoString(controllerValue)
	cfg.General.LogLevel = logLevel
	_, err = prepareEmbeddedConfig(cfg, embeddedOptions{
		FileDescriptor:  int(fileDescriptor),
		IPv6Enabled:     ipv6Value != 0,
		ProcessMatching: C.GoString(processMatchingValue),
	})
	if err != nil {
		return respond(nil, err)
	}
	payload, err := json.Marshal(startPayload{
		ControllerSecret: cfg.Controller.Secret,
		PrimarySelector:  primarySelector,
	})
	if err != nil {
		return respond(nil, fmt.Errorf("encode controller credentials: %w", err))
	}

	installPlatformHooks(C.GoString(protectEndpointValue))
	route.SetEmbedMode(true)
	hub.ApplyConfig(cfg)
	active = true
	releaseRebuildableMemory(false)
	return respond(payload, nil)
}

//export AndroidCyamlUpdateNetworkEnvironment
func AndroidCyamlUpdateNetworkEnvironment(environmentValue *C.char) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	// Changing this value selects another network-scoped direct-DNS branch.
	// Do not clear either the ordinary or the long-lived per-source DNS caches:
	// their scoped keys keep answers from different physical networks isolated.
	dialer.SetDirectNetworkEnvironment(C.GoString(environmentValue))
	return respond(nil, nil)
}

//export AndroidCyamlSetTcpConcurrent
func AndroidCyamlSetTcpConcurrent(enabledValue C.int) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if !active {
		return respond(nil, errors.New("mihomo runtime is not active"))
	}
	dialer.SetTcpConcurrent(enabledValue != 0)
	return respond(nil, nil)
}

//export AndroidCyamlStop
func AndroidCyamlStop() *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()
	stopLocked()
	return respond(nil, nil)
}

//export AndroidCyamlNotifyNetworkChanged
func AndroidCyamlNotifyNetworkChanged(closeConnectionsValue C.int) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if active {
		// DNS state is reconciled separately by AndroidCyamlUpdateSystemDNS.
		// Keeping it out of this transport reset is what lets a later handover
		// return to the previous network's long-lived DNS branch.
		iface.FlushCache()
		if closeConnectionsValue != 0 {
			dialer.ClearTCPConcurrentCache()
			statistic.DefaultManager.Range(func(connection statistic.Tracker) bool {
				_ = connection.Close()
				return true
			})
		}
	}
	return respond(nil, nil)
}

//export AndroidCyamlUpdateSystemDNS
func AndroidCyamlUpdateSystemDNS(serversValue *C.char) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	var servers []string
	if err := json.Unmarshal([]byte(C.GoString(serversValue)), &servers); err != nil {
		return respond(nil, fmt.Errorf("decode Android system DNS: %w", err))
	}
	MDNS.UpdateSystemDNS(servers)
	if active {
		// Clear only ordinary answers and DNS transports. ClearVolatileCache
		// deliberately preserves the 24-hour network/source candidate branches.
		resolver.ClearVolatileCache()
		resolver.ResetConnection()
	}
	return respond(nil, nil)
}

//export AndroidCyamlIsRunning
func AndroidCyamlIsRunning() C.int {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()
	if active {
		return 1
	}
	return 0
}

//export AndroidCyamlTrimMemory
func AndroidCyamlTrimMemory() C.int {
	return C.int(releaseRebuildableMemory(true))
}

// AndroidCyamlRuntimeMetrics answers one diagnostics sample. It deliberately
// takes no lock: runtimeMu is held across a full hub.ApplyConfig during start,
// and a sampler on a timer must never wait on that. Everything read here is
// either lock-free in the Go runtime or already synchronised by mihomo.
//
//export AndroidCyamlRuntimeMetrics
func AndroidCyamlRuntimeMetrics() *C.char {
	sample := collectRuntimeMetrics()
	var connections uint64
	statistic.DefaultManager.Range(func(statistic.Tracker) bool {
		connections++
		return true
	})
	sample["connections"] = connections
	uploaded, downloaded := statistic.DefaultManager.Total()
	sample["uploadedBytes"] = nonNegative(uploaded)
	sample["downloadedBytes"] = nonNegative(downloaded)
	platformSocketProtect.counters(sample)
	sample["dialHookCalls"] = dialHookCalls.Load()
	sample["dialHookControlFails"] = dialHookControlFails.Load()
	sample["protectJniCalls"] = protectJniCalls.Load()
	sample["protectJniRejections"] = protectJniRejections.Load()
	sample["protectJniFallbacks"] = protectJniFallbacks.Load()
	sample["processLookupCalls"] = processLookupCalls.Load()
	sample["processLookupMisses"] = processLookupMisses.Load()
	coreLog.counters(sample)
	payload, err := json.Marshal(diagnosticsSample{
		Metrics:     sample,
		Unavailable: unavailableRuntimeMetrics(),
	})
	return respond(payload, err)
}

// AndroidCyamlSetDiagnostics attaches or detaches the mihomo log classifier.
// Off by default so nothing subscribes to the core's log fan-out unless the
// user asked for diagnostics.
//
//export AndroidCyamlSetDiagnostics
func AndroidCyamlSetDiagnostics(enabledValue C.int) *C.char {
	setCoreLogPump(enabledValue != 0)
	return respond(nil, nil)
}

func setCoreLogPump(enabled bool) {
	coreLogMu.Lock()
	defer coreLogMu.Unlock()
	if enabled {
		if coreLogEvents != nil {
			return
		}
		events := MLog.Subscribe()
		coreLogEvents = events
		go pumpCoreLog(events)
		return
	}
	if coreLogEvents == nil {
		return
	}
	// UnSubscribe closes the channel, which ends the pump goroutine.
	MLog.UnSubscribe(coreLogEvents)
	coreLogEvents = nil
}

func pumpCoreLog(events <-chan MLog.Event) {
	for event := range events {
		switch event.LogLevel {
		case MLog.WARNING:
			coreLog.observe(true, event.Payload)
		case MLog.ERROR:
			coreLog.observe(false, event.Payload)
		}
	}
}

func nonNegative(value int64) uint64 {
	if value < 0 {
		return 0
	}
	return uint64(value)
}

func initializeRuntimePaths(home, configPath string) error {
	if home == "" || !filepath.IsAbs(home) {
		return errors.New("mihomo home directory must be absolute")
	}
	if configPath == "" || !filepath.IsAbs(configPath) {
		return errors.New("mihomo configuration path must be absolute")
	}
	MC.SetHomeDir(home)
	MC.SetConfig(configPath)
	return config.Init(home)
}

func prepareEmbeddedConfig(cfg *config.Config, options embeddedOptions) ([]byte, error) {
	if cfg == nil || cfg.General == nil {
		return nil, errors.New("AndroidCyaml received an incomplete mihomo configuration")
	}

	tunConfig := &cfg.General.Tun
	if len(tunConfig.RouteAddressSet) != 0 || len(tunConfig.RouteExcludeAddressSet) != 0 {
		return nil, errors.New("Android VpnService does not support dynamic TUN route-address-set fields")
	}

	originalAutoRoute := tunConfig.AutoRoute
	tunConfig.Enable = true
	tunConfig.Device = "AndroidCyaml"
	tunConfig.Stack = MC.TunSystem
	tunConfig.MTU = embeddedMTU
	tunConfig.GSO = false
	tunConfig.GSOMaxSize = 0
	tunConfig.Inet4Address = []netip.Prefix{netip.MustParsePrefix(embeddedIPv4Prefix)}
	if options.IPv6Enabled {
		tunConfig.Inet6Address = []netip.Prefix{netip.MustParsePrefix(embeddedIPv6Prefix)}
		cfg.General.IPv6 = true
	} else {
		cfg.General.IPv6 = false
		tunConfig.Inet6Address = nil
		tunConfig.Inet6RouteAddress = nil
		tunConfig.Inet6RouteExcludeAddress = nil
		tunConfig.RouteAddress = ipv4Prefixes(tunConfig.RouteAddress)
		tunConfig.RouteExcludeAddress = ipv4Prefixes(tunConfig.RouteExcludeAddress)
		tunConfig.LoopbackAddress = ipv4Addresses(tunConfig.LoopbackAddress)
	}
	if cfg.DNS != nil {
		cfg.DNS.IPv6 = cfg.DNS.IPv6 && options.IPv6Enabled
	}

	var findProcessMode process.FindProcessMode
	if err := findProcessMode.Set(options.ProcessMatching); err != nil {
		return nil, fmt.Errorf(
			"unsupported mihomo find-process-mode: %s",
			options.ProcessMatching,
		)
	}
	cfg.General.FindProcessMode = findProcessMode

	tunConfig.AutoRoute = originalAutoRoute
	dnsEnabled := cfg.DNS != nil && cfg.DNS.Enable
	spec := makeTunSpec(*tunConfig, dnsEnabled)
	payload, err := json.Marshal(spec)
	if err != nil {
		return nil, fmt.Errorf("encode Android TUN options: %w", err)
	}

	if options.FileDescriptor >= 0 {
		tunConfig.FileDescriptor = options.FileDescriptor
		tunConfig.AutoRoute = false
		tunConfig.AutoRedirect = false
		tunConfig.AutoDetectInterface = false
		tunConfig.IncludePackage = nil
		tunConfig.ExcludePackage = nil
		tunConfig.IncludeAndroidUser = nil
		tunConfig.IncludeUID = nil
		tunConfig.IncludeUIDRange = nil
		tunConfig.ExcludeUID = nil
		tunConfig.ExcludeUIDRange = nil
	}
	return payload, nil
}

func makeTunSpec(tunConfig LC.Tun, dnsEnabled bool) tunSpec {
	mtu := tunConfig.MTU
	if mtu == 0 {
		mtu = embeddedMTU
	}

	routes := append([]netip.Prefix{}, tunConfig.RouteAddress...)
	routes = append(routes, tunConfig.Inet4RouteAddress...)
	routes = append(routes, tunConfig.Inet6RouteAddress...)
	excludedRoutes := append([]netip.Prefix{}, tunConfig.RouteExcludeAddress...)
	excludedRoutes = append(excludedRoutes, tunConfig.Inet4RouteExcludeAddress...)
	excludedRoutes = append(excludedRoutes, tunConfig.Inet6RouteExcludeAddress...)

	inet4Routes, inet6Routes := splitPrefixes(routes)
	inet4Excluded, inet6Excluded := splitPrefixes(excludedRoutes)
	return tunSpec{
		MTU:                      mtu,
		Inet4Address:             prefixStrings(tunConfig.Inet4Address),
		Inet6Address:             prefixStrings(tunConfig.Inet6Address),
		AutoRoute:                tunConfig.AutoRoute,
		Inet4RouteAddress:        inet4Routes,
		Inet6RouteAddress:        inet6Routes,
		Inet4RouteExcludeAddress: inet4Excluded,
		Inet6RouteExcludeAddress: inet6Excluded,
		DNSServerAddress:         dnsServerAddresses(tunConfig, dnsEnabled),
		IncludePackage:           uniqueSorted(append([]string{}, tunConfig.IncludePackage...)),
		ExcludePackage:           uniqueSorted(append([]string{}, tunConfig.ExcludePackage...)),
	}
}

func splitPrefixes(prefixes []netip.Prefix) ([]string, []string) {
	var inet4 []string
	var inet6 []string
	for _, prefix := range prefixes {
		if !prefix.IsValid() {
			continue
		}
		if prefix.Addr().Is4() {
			inet4 = append(inet4, prefix.String())
		} else {
			inet6 = append(inet6, prefix.String())
		}
	}
	return uniqueSorted(inet4), uniqueSorted(inet6)
}

func prefixStrings(prefixes []netip.Prefix) []string {
	result := make([]string, 0, len(prefixes))
	for _, prefix := range prefixes {
		if prefix.IsValid() {
			result = append(result, prefix.String())
		}
	}
	return uniqueSorted(result)
}

func dnsServerAddresses(tunConfig LC.Tun, enabled bool) []string {
	if !enabled {
		return nil
	}
	result := make([]string, 0, len(tunConfig.Inet4Address)+len(tunConfig.Inet6Address))
	addresses := append([]netip.Prefix{}, tunConfig.Inet4Address...)
	addresses = append(addresses, tunConfig.Inet6Address...)
	for _, prefix := range addresses {
		if !prefix.IsValid() {
			continue
		}
		address := prefix.Addr().Next()
		if address.IsValid() && prefix.Contains(address) {
			result = append(result, address.String())
		}
	}
	return uniqueSorted(result)
}

func uniqueSorted(values []string) []string {
	sort.Strings(values)
	result := values[:0]
	for _, value := range values {
		if value == "" || (len(result) != 0 && result[len(result)-1] == value) {
			continue
		}
		result = append(result, value)
	}
	return result
}

func ipv4Prefixes(values []netip.Prefix) []netip.Prefix {
	result := make([]netip.Prefix, 0, len(values))
	for _, value := range values {
		if value.IsValid() && value.Addr().Is4() {
			result = append(result, value)
		}
	}
	return result
}

func ipv4Addresses(values []netip.Addr) []netip.Addr {
	result := make([]netip.Addr, 0, len(values))
	for _, value := range values {
		if value.IsValid() && value.Is4() {
			result = append(result, value)
		}
	}
	return result
}

func installPlatformHooks(protectEndpoint string) {
	platformSocketProtect.setEndpoint(protectEndpoint)
	// The JNI fallback below keeps dialing when the endpoint is missing, which
	// would otherwise make a broken endpoint indistinguishable from a working
	// one. Say which path is live.
	if platformSocketProtect.enabled() {
		MLog.Infoln("Android socket protect uses the unix endpoint at %s", protectEndpoint)
	} else {
		MLog.Warnln("Android socket protect endpoint is unavailable; dials fall back to JNI")
	}
	dialer.DefaultSocketHook = func(network, address string, connection syscall.RawConn) error {
		dialHookCalls.Add(1)
		var protectErr error
		err := connection.Control(func(fileDescriptor uintptr) {
			protectErr = protectDialedSocket(int(fileDescriptor))
		})
		if err != nil {
			dialHookControlFails.Add(1)
			return err
		}
		if protectErr != nil {
			return fmt.Errorf(
				"VpnService.protect rejected %s socket for %s: %w",
				network,
				address,
				protectErr,
			)
		}
		return nil
	}
	androidcyamlcore.SetProcessResolver(resolveProcess)
}

// protectDialedSocket prefers the unix socket endpoint, which keeps the dial
// path in pure Go. The JNI callback stays as a fallback so a broken endpoint
// degrades to the old behaviour instead of failing every dial; a verdict from
// VpnService itself is final and is not retried.
func protectDialedSocket(fileDescriptor int) error {
	if platformSocketProtect.enabled() {
		err := platformSocketProtect.protect(fileDescriptor)
		switch {
		case err == nil:
			if platformSocketProtect.noteDegraded(false) {
				MLog.Infoln("Android protect endpoint recovered; sockets no longer cross JNI")
			}
			return nil
		case errors.Is(err, errSocketProtectRejected):
			return err
		default:
			protectJniFallbacks.Add(1)
			if platformSocketProtect.noteDegraded(true) {
				MLog.Warnln("Android protect endpoint unusable, falling back to JNI: %v", err)
			}
		}
	}
	return protectSocketThroughJNI(fileDescriptor)
}

func protectSocketThroughJNI(fileDescriptor int) error {
	callback := currentProtectCallback()
	if callback == nil {
		return errors.New("Android socket protect callback is unavailable")
	}
	protectJniCalls.Add(1)
	rejected := withCallbackPermit(platformCallbackLimit, func() bool {
		return C.androidcyaml_call_protect(callback, C.int(fileDescriptor)) == 0
	})
	if rejected {
		protectJniRejections.Add(1)
		return errSocketProtectRejected
	}
	return nil
}

func clearPlatformHooks() {
	dialer.DefaultSocketHook = nil
	platformSocketProtect.setEndpoint("")
	androidcyamlcore.ResetProcessResolver()
}

func resolveProcess(network string, source, destination netip.AddrPort) (uint32, string, error) {
	if !source.IsValid() || !destination.IsValid() {
		return 0, "", process.ErrNotFound
	}
	callback := currentResolveProcessCallback()
	if callback == nil {
		return 0, "", process.ErrNotFound
	}

	var protocol int
	switch {
	case strings.HasPrefix(network, "tcp"):
		protocol = syscall.IPPROTO_TCP
	case strings.HasPrefix(network, "udp"):
		protocol = syscall.IPPROTO_UDP
	default:
		return 0, "", process.ErrInvalidNetwork
	}

	sourceAddress := C.CString(source.Addr().String())
	destinationAddress := C.CString(destination.Addr().String())
	defer C.free(unsafe.Pointer(sourceAddress))
	defer C.free(unsafe.Pointer(destinationAddress))

	processLookupCalls.Add(1)
	encoded := withCallbackPermit(platformCallbackLimit, func() *C.char {
		return C.androidcyaml_call_resolve(
			callback,
			C.int(protocol),
			sourceAddress,
			C.int(source.Port()),
			destinationAddress,
			C.int(destination.Port()),
		)
	})
	if encoded == nil {
		processLookupMisses.Add(1)
		return 0, "", process.ErrNotFound
	}
	defer C.free(unsafe.Pointer(encoded))
	uidValue, packageName, found := strings.Cut(C.GoString(encoded), "\n")
	if !found || packageName == "" {
		processLookupMisses.Add(1)
		return 0, "", process.ErrNotFound
	}
	uid, err := strconv.ParseUint(uidValue, 10, 32)
	if err != nil {
		processLookupMisses.Add(1)
		return 0, "", process.ErrNotFound
	}
	return uint32(uid), packageName, nil
}

func callbacksInstalled() bool {
	callbackMu.RLock()
	defer callbackMu.RUnlock()
	return protectCallback != nil && resolveProcessCallback != nil
}

func currentProtectCallback() unsafe.Pointer {
	callbackMu.RLock()
	defer callbackMu.RUnlock()
	return protectCallback
}

func currentResolveProcessCallback() unsafe.Pointer {
	callbackMu.RLock()
	defer callbackMu.RUnlock()
	return resolveProcessCallback
}

func stopLocked() {
	if active {
		executor.Shutdown()
		resetTunListenerForRestart()
		route.ReCreateServer(&route.Config{})
	}
	clearPlatformHooks()
	MDNS.UpdateSystemDNS(nil)
	dialer.SetDirectNetworkEnvironment("")
	active = false
	releaseRebuildableMemory(false)
}

func releaseRebuildableMemory(clearRuntimeCaches bool) int {
	geodata.ClearGeoIPCache()
	geodata.ClearGeoSiteCache()
	clearedCacheGroups := 2
	if clearRuntimeCaches {
		iface.FlushCache()
		resolver.ClearCache()
		clearedCacheGroups += 2
	}
	// FreeOSMemory already performs a full GC before returning unused pages to
	// Android, so a preceding runtime.GC would only duplicate the stop-the-world
	// work.
	debug.FreeOSMemory()
	return clearedCacheGroups
}

func respond(payload []byte, err error) *C.char {
	response := nativeResponse{OK: err == nil}
	if err != nil {
		response.Error = err.Error()
	} else if len(payload) != 0 {
		response.Payload = json.RawMessage(payload)
	}
	encoded, marshalErr := json.Marshal(response)
	if marshalErr != nil {
		encoded = []byte(`{"ok":false,"error":"unable to encode native response"}`)
	}
	return C.CString(string(encoded))
}
