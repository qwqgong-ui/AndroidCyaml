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
	"runtime"
	"runtime/debug"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/component/iface"
	"github.com/metacubex/mihomo/component/process"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/config"
	MC "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/hub/route"
	LC "github.com/metacubex/mihomo/listener/config"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

const (
	embeddedIPv4Prefix = "172.19.0.1/30"
	embeddedIPv6Prefix = "fdfe:dcba:9876::1/126"
	embeddedMTU        = 9000
)

type nativeResponse struct {
	OK      bool            `json:"ok"`
	Error   string          `json:"error,omitempty"`
	Payload json.RawMessage `json:"payload,omitempty"`
}

type embeddedOptions struct {
	FileDescriptor  int
	Stack           string
	IPv6Enabled     bool
	ProcessMatching bool
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
	stackValue *C.char,
	ipv6Value,
	processMatchingValue C.int,
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
		Stack:           C.GoString(stackValue),
		IPv6Enabled:     ipv6Value != 0,
		ProcessMatching: processMatchingValue != 0,
	})
	return respond(payload, err)
}

//export AndroidCyamlStart
func AndroidCyamlStart(
	homeValue,
	configValue,
	uiValue,
	controllerValue,
	secretValue,
	stackValue *C.char,
	fileDescriptor,
	ipv6Value,
	processMatchingValue C.int,
) *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if active {
		stopLocked()
	}
	if !callbacksInstalled() {
		return respond(nil, errors.New("Android JNI callbacks are not installed"))
	}

	home := C.GoString(homeValue)
	configPath := C.GoString(configValue)
	if err := initializeRuntimePaths(home, configPath); err != nil {
		return respond(nil, err)
	}
	cfg, err := executor.ParseWithPath(configPath)
	if err != nil {
		return respond(nil, err)
	}

	cfg.Controller.ExternalUI = C.GoString(uiValue)
	cfg.Controller.ExternalController = C.GoString(controllerValue)
	cfg.Controller.Secret = C.GoString(secretValue)
	_, err = prepareEmbeddedConfig(cfg, embeddedOptions{
		FileDescriptor:  int(fileDescriptor),
		Stack:           C.GoString(stackValue),
		IPv6Enabled:     ipv6Value != 0,
		ProcessMatching: processMatchingValue != 0,
	})
	if err != nil {
		return respond(nil, err)
	}

	installPlatformHooks()
	route.SetEmbedMode(true)
	hub.ApplyConfig(cfg)
	active = true
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
func AndroidCyamlNotifyNetworkChanged() *C.char {
	runtimeMu.Lock()
	defer runtimeMu.Unlock()

	if active {
		iface.FlushCache()
		resolver.ClearCache()
		resolver.ResetConnection()
		statistic.DefaultManager.Range(func(connection statistic.Tracker) bool {
			_ = connection.Close()
			return true
		})
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
	runtime.GC()
	debug.FreeOSMemory()
	return 1
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

	stackName := strings.ToLower(strings.TrimSpace(options.Stack))
	if stackName == "" {
		stackName = "system"
	}
	stack, found := MC.StackTypeMapping[stackName]
	if !found {
		return nil, fmt.Errorf("unsupported Android TUN stack: %s", options.Stack)
	}

	tunConfig := &cfg.General.Tun
	if len(tunConfig.RouteAddressSet) != 0 || len(tunConfig.RouteExcludeAddressSet) != 0 {
		return nil, errors.New("Android VpnService does not support dynamic TUN route-address-set fields")
	}

	originalAutoRoute := tunConfig.AutoRoute
	tunConfig.Enable = true
	tunConfig.Device = "AndroidCyaml"
	tunConfig.Stack = stack
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

	if options.ProcessMatching {
		cfg.General.FindProcessMode = process.FindProcessAlways
	} else {
		cfg.General.FindProcessMode = process.FindProcessOff
	}

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

func installPlatformHooks() {
	dialer.DefaultSocketHook = func(network, address string, connection syscall.RawConn) error {
		callback := currentProtectCallback()
		if callback == nil {
			return errors.New("Android socket protect callback is unavailable")
		}
		var rejected bool
		err := connection.Control(func(fileDescriptor uintptr) {
			rejected = C.androidcyaml_call_protect(callback, C.int(fileDescriptor)) == 0
		})
		if err != nil {
			return err
		}
		if rejected {
			return fmt.Errorf("VpnService.protect rejected %s socket for %s", network, address)
		}
		return nil
	}
	process.DefaultProcessNameResolver = resolveProcess
}

func clearPlatformHooks() {
	dialer.DefaultSocketHook = nil
	process.DefaultProcessNameResolver = nil
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

	encoded := C.androidcyaml_call_resolve(
		callback,
		C.int(protocol),
		sourceAddress,
		C.int(source.Port()),
		destinationAddress,
		C.int(destination.Port()),
	)
	if encoded == nil {
		return 0, "", process.ErrNotFound
	}
	defer C.free(unsafe.Pointer(encoded))
	uidValue, packageName, found := strings.Cut(C.GoString(encoded), "\n")
	if !found || packageName == "" {
		return 0, "", process.ErrNotFound
	}
	uid, err := strconv.ParseUint(uidValue, 10, 32)
	if err != nil {
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
		route.ReCreateServer(&route.Config{})
	}
	clearPlatformHooks()
	active = false
	runtime.GC()
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
