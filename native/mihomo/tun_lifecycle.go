package main

import (
	"github.com/metacubex/mihomo/listener"
	LC "github.com/metacubex/mihomo/listener/config"
)

// resetTunListenerForRestart clears mihomo's cached TUN configuration through
// its synchronized recreate path. executor.Shutdown closes the active listener,
// but mihomo normally keeps LastTunConf because a standalone process exits next.
// AndroidCyaml restarts the core in-process, so leaving that cache intact can make
// an identical configuration skip listener creation and leave Android's TUN with
// no reader.
func resetTunListenerForRestart() {
	listener.ReCreateTun(LC.Tun{}, nil)
}
