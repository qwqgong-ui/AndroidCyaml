package main

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"
)

// Exercise the real Go socket control paths used by TCP and UDP DNS. A failed
// Android protect callback must never yield a usable socket to either caller.
func TestRejectedProtectionPreventsTCPAndUDPSockets(t *testing.T) {
	control := func(_, _ string, raw syscall.RawConn) error {
		return protectRawSocket(raw, func(int) bool { return false })
	}
	t.Run("tcp", func(t *testing.T) {
		listener, err := net.Listen("tcp4", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		defer listener.Close()
		d := net.Dialer{Control: control}
		conn, err := d.DialContext(context.Background(), "tcp4", listener.Addr().String())
		if conn != nil {
			conn.Close()
			t.Fatal("unprotected TCP socket escaped the control hook")
		}
		if !errors.Is(err, errSocketProtectionRejected) {
			t.Fatalf("unexpected dial error: %v", err)
		}
	})
	t.Run("udp", func(t *testing.T) {
		lc := net.ListenConfig{Control: control}
		conn, err := lc.ListenPacket(context.Background(), "udp4", "127.0.0.1:0")
		if conn != nil {
			conn.Close()
			t.Fatal("unprotected UDP socket escaped the control hook")
		}
		if !errors.Is(err, errSocketProtectionRejected) {
			t.Fatalf("unexpected listen error: %v", err)
		}
	})
}

func TestAcceptedProtectionAllowsSocket(t *testing.T) {
	called := false
	lc := net.ListenConfig{Control: func(_, _ string, raw syscall.RawConn) error {
		return protectRawSocket(raw, func(fd int) bool {
			called = true
			return fd >= 0
		})
	}}
	conn, err := lc.ListenPacket(context.Background(), "udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if !called {
		t.Fatal("socket was created without consulting protect")
	}
}
