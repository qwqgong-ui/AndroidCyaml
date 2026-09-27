package main

import (
	"encoding/json"
	"net/netip"
	"testing"

	core "github.com/metacubex/mihomo/androidcyaml"
	"github.com/metacubex/mihomo/config"
)

func TestEmbeddedTunAddressFamilyContract(t *testing.T) {
	for _, enabled := range []bool{false, true} {
		cfg := &core.Config{General: &config.General{}, DNS: &config.DNS{Enable: true, IPv6: true}}
		cfg.General.Tun = core.Tun{
			Inet6RouteAddress: []netip.Prefix{netip.MustParsePrefix("::/0")},
			RouteAddress:      []netip.Prefix{netip.MustParsePrefix("0.0.0.0/0"), netip.MustParsePrefix("::/0")},
		}
		payload, err := prepareEmbeddedConfig(cfg, embeddedOptions{FileDescriptor: 42, IPv6Enabled: enabled, ProcessMatching: "always"})
		if err != nil {
			t.Fatal(err)
		}
		var spec tunSpec
		if err := json.Unmarshal(payload, &spec); err != nil {
			t.Fatal(err)
		}
		if len(spec.Inet4Address) != 1 || len(spec.Inet6Address) != boolCount(enabled) {
			t.Fatalf("ipv6=%v: Android address family mismatch: %+v", enabled, spec)
		}
		if len(spec.DNSServerAddress) != 1+boolCount(enabled) {
			t.Fatalf("ipv6=%v: DNS server enabled an unwanted family: %+v", enabled, spec)
		}
		if cfg.General.IPv6 != enabled || cfg.DNS.IPv6 != enabled {
			t.Fatalf("core and Android TUN disagree on IPv6: %+v", cfg.General)
		}
		if !enabled && (len(cfg.General.Tun.Inet6RouteAddress) != 0 || len(cfg.General.Tun.RouteAddress) != 1) {
			t.Fatal("IPv6 routes survived IPv4-only configuration")
		}
		if cfg.General.Tun.FileDescriptor != 42 || cfg.General.Tun.AutoRoute {
			t.Fatal("embedded core must retain the Android-owned descriptor")
		}
	}
}

func TestEmbeddedTunPreservesExplicitSessionTimeouts(t *testing.T) {
	tun := core.Tun{UDPTimeout: 45, ICMPTimeout: 9}
	applyAndroidTunTuning(&tun)
	if tun.UDPTimeout != 45 || tun.ICMPTimeout != 9 || !tun.EndpointIndependentNat {
		t.Fatalf("explicit timeouts or Android NAT contract lost: %+v", tun)
	}
	tun = core.Tun{}
	applyAndroidTunTuning(&tun)
	if tun.UDPTimeout != embeddedUDPTimeoutSeconds || tun.ICMPTimeout != embeddedICMPTimeoutSeconds {
		t.Fatalf("missing bounded Android session defaults: %+v", tun)
	}
}

func TestEmbeddedTunRejectsInvalidConfiguration(t *testing.T) {
	for _, cfg := range []*core.Config{nil, {}} {
		if _, err := prepareEmbeddedConfig(cfg, embeddedOptions{}); err == nil {
			t.Fatal("incomplete configuration accepted")
		}
	}
	cfg := &core.Config{General: &config.General{}}
	if _, err := prepareEmbeddedConfig(cfg, embeddedOptions{ProcessMatching: "invalid"}); err == nil {
		t.Fatal("invalid process mode accepted")
	}
}

func boolCount(value bool) int {
	if value {
		return 1
	}
	return 0
}
