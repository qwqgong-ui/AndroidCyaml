package main

import (
	"errors"
	"syscall"
)

var errSocketProtectionRejected = errors.New("VPN socket protection rejected")

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
