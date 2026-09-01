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

	core "github.com/metacubex/mihomo/androidcyaml"
)

const (
	embeddedIPv4Prefix = "172.19.0.1/30"
	embeddedIPv6Prefix = "fdfe:dcba:9876::1/126"
	embeddedMTU        = 9000

	// A phone's NAT table is not free and nothing reaps it early: sing-tun holds
	// a UDP session for its whole timeout after the last packet. mihomo's
	// default is five minutes, chosen for a desktop that is not being killed for
	// memory. Ninety seconds still outlives a QUIC idle period (30s) and a DNS
	// exchange by a wide margin, while releasing the sessions a chatty app opens
	// and abandons roughly three times sooner.
	embeddedUDPTimeoutSeconds = 90

	// ICMP sessions are answered and finished; they do not need a long window.
	embeddedICMPTimeoutSeconds = 10
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
	// CoreLog carries mihomo's own lines since the previous sample, so the
	// platform's diagnostics log -- the artefact that rotates and can be
	// exported -- holds the same evidence the dashboard's log view shows and
	// then forgets.
	CoreLog        []string `json:"coreLog,omitempty"`
	CoreLogDropped uint64   `json:"coreLogDropped,omitempty"`
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

	// Dial-path tallies for the diagnostics sampler. Deltas between two samples
	// separate "the network is churning" (dialHookCalls climbing) from "dials
	// are being refused" (rejections climbing) from "the endpoint broke and we
	// are back on JNI" (jniFallbacks climbing).
	dialHookCalls        atomic.Uint64
	dialHookControlFails atomic.Uint64
	protectAttempts      atomic.Uint64
	protectRejections    atomic.Uint64
	processLookupCalls   atomic.Uint64
	processLookupMisses  atomic.Uint64

	// mihomo publishes every log event to its observable regardless of the
	// configured level, and a slow subscriber blocks the call site. Subscribe
	// only while diagnostics is on, and keep the pump a tight loop.
	coreLog       coreLogCounters
	coreLogMu     sync.Mutex
	coreLogEvents <-chan core.LogEvent
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
		_, err = core.ParseConfigBytes(configuration)
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
	cfg, err := core.ParseConfigBytes(configuration)
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
	core.SetDirectNetworkEnvironment(C.GoString(networkEnvironmentValue))

	home := C.GoString(homeValue)
	configPath := C.GoString(configValue)
	if err := initializeRuntimePaths(home, configPath); err != nil {
		return respond(nil, err)
	}
	cfg, err := core.ParseConfigPath(configPath)
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
	rawCfg, err := core.UnmarshalRawConfig(configuration)
	if err != nil {
		return respond(nil, fmt.Errorf("parse selector configuration: %w", err))
	}
	primarySelector := firstConfiguredSelector(rawCfg)

	logLevelName := strings.ToLower(strings.TrimSpace(C.GoString(logLevelValue)))
	logLevel, found := core.ParseLogLevel(logLevelName)
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

	installPlatformHooks()
	core.SetEmbedMode(true)
	core.ApplyConfig(cfg)
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
	core.SetDirectNetworkEnvironment(C.GoString(environmentValue))
	return respond(nil, nil)
}

//export AndroidCyamlRetireNetworkScope
func AndroidCyamlRetireNetworkScope(environmentValue *C.char) *C.char {
	// Deliberately does not take runtimeMu and does not require an active
	// runtime. Retirement is bookkeeping about networks the platform no longer
	// tracks, and the caches outlive any single core instance -- refusing while
	// stopped would just leave the answers behind until their own expiry.
	core.RetireNetworkScope(C.GoString(environmentValue))
	return respond(nil, nil)
}

//export AndroidCyamlSetTcpConcurrent
func AndroidCyamlSetTcpConcurrent(enabledValue C.int) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if !active {
		return respond(nil, errors.New("mihomo runtime is not active"))
	}
	core.SetTCPConcurrent(enabledValue != 0)
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
		core.FlushInterfaceCache()
		if closeConnectionsValue != 0 {
			core.ClearTCPConcurrentCache()
			core.ResetDNSConnections()
			core.CloseAllConnections()
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
	core.UpdateSystemDNS(servers)
	if active {
		// Clear only ordinary answers and DNS transports. ClearVolatileCache
		// deliberately preserves the 24-hour network/source candidate branches.
		core.ClearVolatileDNSCache()
		core.ResetDNSConnections()
	}
	return respond(nil, nil)
}

//export AndroidCyamlUpdateIPv6Availability
func AndroidCyamlUpdateIPv6Availability(availableValue C.int) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if active {
		core.SetSystemIPv6Available(availableValue != 0)
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
// takes no lock: runtimeMu is held across a full config apply during start,
// and a sampler on a timer must never wait on that. Everything read here is
// either lock-free in the Go runtime or already synchronised by mihomo.
//
//export AndroidCyamlRuntimeMetrics
func AndroidCyamlRuntimeMetrics() *C.char {
	sample := collectRuntimeMetrics()
	sample["connections"] = core.ConnectionCount()
	uploaded, downloaded := core.TotalTraffic()
	sample["uploadedBytes"] = nonNegative(uploaded)
	sample["downloadedBytes"] = nonNegative(downloaded)
	sample["dialHookCalls"] = dialHookCalls.Load()
	sample["dialHookControlFails"] = dialHookControlFails.Load()
	sample["protectAttempts"] = protectAttempts.Load()
	sample["protectRejections"] = protectRejections.Load()
	sample["processLookupCalls"] = processLookupCalls.Load()
	sample["processLookupMisses"] = processLookupMisses.Load()
	coreLog.counters(sample)
	platformDialProbe.counters(sample)
	// The attribution goes to the core log in full, where the log-level switch
	// controls it; only the window's shape stays in the metrics line.
	platformDialProbe.report()
	// Each sample owns its own window. Without this the first burst after start
	// would dominate the tally for the rest of the session, which is exactly the
	// mistake that made an early burst look like steady state.
	platformDialProbe.reset()
	capturedLines, capturedDropped := capturedCoreLog.drain()
	payload, err := json.Marshal(diagnosticsSample{
		Metrics:        sample,
		Unavailable:    unavailableRuntimeMetrics(),
		CoreLog:        capturedLines,
		CoreLogDropped: capturedDropped,
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
	if enabledValue == 0 {
		capturedCoreLog.reset()
	}
	return respond(nil, nil)
}

// AndroidCyamlSetLogCapture chooses whether mihomo's per-connection INFO lines
// are retained for the diagnostics log, on top of the warnings and errors that
// always are. This is the log-mode switch: the connection lines are what name
// the destination, the matched rule and the outbound, and they are also what
// would fill the ring during ordinary browsing.
//
//export AndroidCyamlSetLogCapture
func AndroidCyamlSetLogCapture(enabledValue C.int) *C.char {
	capturedCoreLog.setInfo(enabledValue != 0)
	return respond(nil, nil)
}

func setCoreLogPump(enabled bool) {
	coreLogMu.Lock()
	defer coreLogMu.Unlock()
	if enabled {
		if coreLogEvents != nil {
			return
		}
		events := core.SubscribeLogs()
		coreLogEvents = events
		go pumpCoreLog(events)
		return
	}
	if coreLogEvents == nil {
		return
	}
	// UnSubscribe closes the channel, which ends the pump goroutine.
	core.UnsubscribeLogs(coreLogEvents)
	coreLogEvents = nil
}

func pumpCoreLog(events <-chan core.LogEvent) {
	for event := range events {
		switch event.LogLevel {
		case core.LogWarning:
			coreLog.observe(true, event.Payload)
			capturedCoreLog.record("WARN", event.Payload)
		case core.LogError:
			coreLog.observe(false, event.Payload)
			capturedCoreLog.record("ERROR", event.Payload)
		default:
			// Everything else is the per-connection stream. It is the half that
			// names what was dialed and which rule matched, so it is worth
			// keeping -- but only when asked, because it is also the half that
			// would fill the ring during ordinary browsing.
			if capturedCoreLog.wantsInfo() {
				capturedCoreLog.record("INFO", event.Payload)
			}
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
	return core.SetPaths(home, configPath)
}

func prepareEmbeddedConfig(cfg *core.Config, options embeddedOptions) ([]byte, error) {
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
	tunConfig.Stack = core.TunStackSystem
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
	applyAndroidTunTuning(tunConfig)

	var findProcessMode core.FindProcessMode
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

// applyAndroidTunTuning fixes the transport-session parameters that mihomo
// otherwise leaves at desktop defaults.
//
// These are deliberately not runtime overrides. They are properties of running
// inside an Android VPN service that can be killed for memory at any moment, not
// preferences, and a user has no way to tell whether a given value is helping.
// A configuration that sets them explicitly is still honoured -- only the
// unset case is filled in.
func applyAndroidTunTuning(tunConfig *core.Tun) {
	if tunConfig.UDPTimeout == 0 {
		tunConfig.UDPTimeout = embeddedUDPTimeoutSeconds
	}
	if tunConfig.ICMPTimeout == 0 {
		tunConfig.ICMPTimeout = embeddedICMPTimeoutSeconds
	}
	// Endpoint-independent NAT is what lets two peers behind different NATs
	// reach each other directly, so WebRTC calls, console and phone games, and
	// peer-to-peer transfers stop falling back to a relay or failing outright.
	// It costs a second index over the session table, which is bounded by the
	// timeout above.
	tunConfig.EndpointIndependentNat = true
}

func makeTunSpec(tunConfig core.Tun, dnsEnabled bool) tunSpec {
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

func dnsServerAddresses(tunConfig core.Tun, enabled bool) []string {
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

func installPlatformHooks() {
	core.SetSocketHook(func(network, address string, connection syscall.RawConn) error {
		dialHookCalls.Add(1)
		platformDialProbe.observeAddress(address)
		// A failed protect deliberately does not fail the dial.
		//
		// Failing it looked safer and was worse. Under load the protect path is
		// exactly what saturates first, so turning that into a dial error made
		// mihomo retry, made the client above retry, and fed more protect calls
		// into the thing that was already saturated. That is congestion
		// collapse, and it is how a slow moment became thousands of half-open
		// sockets.
		//
		// An unprotected socket is not silently wrong either: it routes back
		// into the TUN, where the loopback guard rejects it. The failure still
		// surfaces, as one connection failing instead of a feedback loop.
		// ClashMetaForAndroid and FlClash both return nothing from protect.
		err := connection.Control(func(fileDescriptor uintptr) {
			protectDialedSocket(int(fileDescriptor))
		})
		if err != nil {
			dialHookControlFails.Add(1)
			return err
		}
		return nil
	})
	core.SetProcessResolver(resolveProcess)
}

// protectDialedSocket hands one socket to VpnService.protect through the JNI
// callback, bounded by the shared platform-callback permit.
func protectDialedSocket(fileDescriptor int) {
	callback := currentProtectCallback()
	if callback == nil {
		return
	}
	protectAttempts.Add(1)
	rejected := withCallbackPermit(platformCallbackLimit, func() bool {
		return C.androidcyaml_call_protect(callback, C.int(fileDescriptor)) == 0
	})
	if rejected {
		protectRejections.Add(1)
	}
}

func clearPlatformHooks() {
	core.SetSocketHook(nil)
	core.ResetProcessResolver()
}

func resolveProcess(network string, source, destination netip.AddrPort) (uint32, string, error) {
	if !source.IsValid() || !destination.IsValid() {
		return 0, "", core.ErrProcessNotFound
	}
	callback := currentResolveProcessCallback()
	if callback == nil {
		return 0, "", core.ErrProcessNotFound
	}

	var protocol int
	switch {
	case strings.HasPrefix(network, "tcp"):
		protocol = syscall.IPPROTO_TCP
	case strings.HasPrefix(network, "udp"):
		protocol = syscall.IPPROTO_UDP
	default:
		return 0, "", core.ErrInvalidNetwork
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
		return 0, "", core.ErrProcessNotFound
	}
	defer C.free(unsafe.Pointer(encoded))
	uidValue, packageName, found := strings.Cut(C.GoString(encoded), "\n")
	if !found || packageName == "" {
		processLookupMisses.Add(1)
		return 0, "", core.ErrProcessNotFound
	}
	uid, err := strconv.ParseUint(uidValue, 10, 32)
	if err != nil {
		processLookupMisses.Add(1)
		return 0, "", core.ErrProcessNotFound
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
		core.Shutdown()
		core.ResetTunListener()
		core.ResetAPIServer()
	}
	clearPlatformHooks()
	core.UpdateSystemDNS(nil)
	core.SetDirectNetworkEnvironment("")
	active = false
	releaseRebuildableMemory(false)
}

func releaseRebuildableMemory(clearRuntimeCaches bool) int {
	core.ClearGeoCaches()
	clearedCacheGroups := 2
	if clearRuntimeCaches {
		core.FlushInterfaceCache()
		core.ClearDNSCache()
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
