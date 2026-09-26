package main

import (
	"context"
	"errors"
	"net"
	"syscall"
	"testing"
)

func TestDNSBindingIncludesTCPRetryAndBothAddressFamilies(t *testing.T) {
	for _, test := range []struct {
		network, address string
		want             bool
	}{
		{"udp4", "112.5.230.54:53", true},
		{"udp6", "[2409:8034:2000::4]:53", true},
		{"tcp6", "[2409:8034:2000::4]:53", true},
		{"udp6", "[fe80::1%wlan0]:53", true},
		{"udp", "223.5.5.5:853", false},
		{"tcp", "1.1.1.1:443", false},
		{"udp", "112.5.230.54:5353", false},
		{"icmp", "112.5.230.54:53", false},
		{"udp", "invalid", false},
	} {
		if got := isPlainDNSSocket(test.network, test.address); got != test.want {
			t.Errorf("%s %s: binding=%t, want %t", test.network, test.address, got, test.want)
		}
	}
}

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
