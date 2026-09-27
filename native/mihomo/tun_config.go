package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"sort"

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

type embeddedOptions struct {
	FileDescriptor  int
	IPv6Enabled     bool
	ProcessMatching string
}

type tunSpec struct {
	MTU              uint32   `json:"mtu"`
	Inet4Address     []string `json:"inet4Address"`
	Inet6Address     []string `json:"inet6Address"`
	DNSServerAddress []string `json:"dnsServerAddress"`
}

func prepareEmbeddedConfig(cfg *core.Config, options embeddedOptions) ([]byte, error) {
	if cfg == nil || cfg.General == nil {
		return nil, errors.New("AndroidCyaml received an incomplete mihomo configuration")
	}

	tunConfig := &cfg.General.Tun
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

	// Android establishes default routes for every enabled address family and
	// includes every app. Route and package filters from YAML never reach Builder.
	return tunSpec{
		MTU:              mtu,
		Inet4Address:     prefixStrings(tunConfig.Inet4Address),
		Inet6Address:     prefixStrings(tunConfig.Inet6Address),
		DNSServerAddress: dnsServerAddresses(tunConfig, dnsEnabled),
	}
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
