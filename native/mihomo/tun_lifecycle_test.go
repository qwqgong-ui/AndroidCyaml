package main

import (
	"testing"

	"github.com/metacubex/mihomo/listener"
	LC "github.com/metacubex/mihomo/listener/config"
)

func TestResetTunListenerForRestartClearsCachedConfig(t *testing.T) {
	stale := LC.Tun{Device: "AndroidCyaml", FileDescriptor: 42}
	listener.ReCreateTun(stale, nil)
	t.Cleanup(resetTunListenerForRestart)

	if got := listener.GetTunConf(); got.Device != stale.Device {
		t.Fatalf("test setup did not retain stale TUN config: %+v", got)
	}

	resetTunListenerForRestart()
	if got := listener.GetTunConf(); !got.Equal(LC.Tun{}) {
		t.Fatalf("cached TUN config survived embedded restart: %+v", got)
	}
}
