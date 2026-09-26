package main

import (
	"errors"
	"net"
	"strings"
	"syscall"
)

var errSocketProtectionRejected = errors.New("VPN socket protection rejected")

func isPlainDNSSocket(network, address string) bool {
	if !strings.HasPrefix(network, "udp") && !strings.HasPrefix(network, "tcp") {
		return false
	}
	_, port, err := net.SplitHostPort(address)
	return err == nil && port == "53"
}

// Returning an error from the socket control hook prevents net.Dialer and
// net.ListenConfig from using a socket that can route back into our own TUN.
func protectRawSocket(connection syscall.RawConn, protect func(int) bool) error {
	protected := false
	if err := connection.Control(func(fd uintptr) {
		protected = protect(int(fd))
	}); err != nil {
		return err
	}
	if !protected {
		return errSocketProtectionRejected
	}
	return nil
}
