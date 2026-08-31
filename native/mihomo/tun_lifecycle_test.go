package main

import (
	"testing"

	core "github.com/metacubex/mihomo/androidcyaml"
	"github.com/metacubex/mihomo/listener"
)

// Seeding a stale configuration is not something AndroidCyaml ever asks the core
// to do, so it is not a facade verb. This test reaches past the facade to set up
// the state it is guarding against; production code must not.
func TestResetTunListenerClearsCachedConfig(t *testing.T) {
	stale := core.Tun{Device: "AndroidCyaml", FileDescriptor: 42}
	listener.ReCreateTun(stale, nil)
	t.Cleanup(core.ResetTunListener)

	if got := core.TunConf(); got.Device != stale.Device {
		t.Fatalf("test setup did not retain stale TUN config: %+v", got)
	}

	core.ResetTunListener()
	if got := core.TunConf(); !got.Equal(core.Tun{}) {
		t.Fatalf("cached TUN config survived embedded restart: %+v", got)
	}
}
