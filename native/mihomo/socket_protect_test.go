//go:build unix

package main

import (
	"errors"
	"net"
	"path/filepath"
	"runtime"
	"runtime/pprof"
	"sync"
	"syscall"
	"testing"
)

// protectEndpointStub stands in for the Kotlin SocketProtectService: it accepts
// unix connections, receives one descriptor per connection through SCM_RIGHTS
// and answers with the verdict the test asked for.
type protectEndpointStub struct {
	listener *net.UnixListener
	granted  bool
	// received reports the byte the stub wrote into every descriptor it was
	// handed, so a test can prove the peer really got the same socket.
	probe byte
}

func startProtectEndpointStub(t *testing.T, granted bool, probe byte) *protectEndpointStub {
	t.Helper()
	path := filepath.Join(t.TempDir(), "protect.sock")
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		t.Fatalf("listen on the protect endpoint stub: %v", err)
	}
	stub := &protectEndpointStub{listener: listener, granted: granted, probe: probe}
	go stub.serve()
	t.Cleanup(func() { _ = listener.Close() })
	return stub
}

func (s *protectEndpointStub) path() string {
	return s.listener.Addr().String()
}

func (s *protectEndpointStub) serve() {
	for {
		connection, err := s.listener.AcceptUnix()
		if err != nil {
			return
		}
		go s.handle(connection)
	}
}

func (s *protectEndpointStub) handle(connection *net.UnixConn) {
	defer connection.Close()
	request := make([]byte, 1)
	oob := make([]byte, syscall.CmsgSpace(4))
	_, oobCount, _, _, err := connection.ReadMsgUnix(request, oob)
	if err != nil {
		return
	}
	descriptors := parseUnixRights(oob[:oobCount])
	defer func() {
		for _, descriptor := range descriptors {
			_ = syscall.Close(descriptor)
		}
	}()
	if len(descriptors) != 1 {
		_, _ = connection.Write([]byte{0x00})
		return
	}
	if s.probe != 0 {
		if _, err := syscall.Write(descriptors[0], []byte{s.probe}); err != nil {
			_, _ = connection.Write([]byte{0x00})
			return
		}
	}
	if s.granted {
		_, _ = connection.Write([]byte{socketProtectGranted})
		return
	}
	_, _ = connection.Write([]byte{0x00})
}

func parseUnixRights(oob []byte) []int {
	messages, err := syscall.ParseSocketControlMessage(oob)
	if err != nil {
		return nil
	}
	var descriptors []int
	for _, message := range messages {
		parsed, err := syscall.ParseUnixRights(&message)
		if err != nil {
			continue
		}
		descriptors = append(descriptors, parsed...)
	}
	return descriptors
}

func newSocketPair(t *testing.T) (int, int) {
	t.Helper()
	pair, err := syscall.Socketpair(syscall.AF_UNIX, syscall.SOCK_STREAM, 0)
	if err != nil {
		t.Fatalf("create a socket pair: %v", err)
	}
	t.Cleanup(func() {
		_ = syscall.Close(pair[0])
		_ = syscall.Close(pair[1])
	})
	return pair[0], pair[1]
}

func TestSocketProtectorHandsTheRealDescriptorToTheEndpoint(t *testing.T) {
	const probe = 0x7f
	stub := startProtectEndpointStub(t, true, probe)
	var protector socketProtector
	protector.setEndpoint(stub.path())

	local, remote := newSocketPair(t)
	if err := protector.protect(local); err != nil {
		t.Fatalf("protect a socket: %v", err)
	}

	echo := make([]byte, 1)
	if _, err := syscall.Read(remote, echo); err != nil {
		t.Fatalf("read from the peer of the protected socket: %v", err)
	}
	if echo[0] != probe {
		t.Fatalf("endpoint wrote %#x into the descriptor, want %#x", echo[0], probe)
	}
}

func TestSocketProtectorReportsRejectionSeparately(t *testing.T) {
	stub := startProtectEndpointStub(t, false, 0)
	var protector socketProtector
	protector.setEndpoint(stub.path())

	local, _ := newSocketPair(t)
	err := protector.protect(local)
	if !errors.Is(err, errSocketProtectRejected) {
		t.Fatalf("protect error = %v, want errSocketProtectRejected", err)
	}
}

func TestSocketProtectorWithoutAnEndpointIsUnavailable(t *testing.T) {
	var protector socketProtector
	if protector.enabled() {
		t.Fatal("a protector without an endpoint reported itself enabled")
	}
	local, _ := newSocketPair(t)
	err := protector.protect(local)
	if !errors.Is(err, errSocketProtectUnavailable) {
		t.Fatalf("protect error = %v, want errSocketProtectUnavailable", err)
	}
	protector.setEndpoint("/nonexistent/androidcyaml-protect.sock")
	if !protector.enabled() {
		t.Fatal("a protector with an endpoint reported itself disabled")
	}
	if err := protector.protect(local); err == nil {
		t.Fatal("protect succeeded against an endpoint that does not exist")
	}
}

// The bug this path replaces was thread growth, not a wrong verdict: the JNI
// upcall blocked its OS thread, so a wide dial burst made the Go runtime create
// one replacement thread per waiting connection. Passing the descriptor over a
// unix socket must keep every waiter on the network poller instead.
func TestSocketProtectorKeepsTheThreadCountFlatUnderLoad(t *testing.T) {
	const callers = 256
	stub := startProtectEndpointStub(t, true, 0)
	var protector socketProtector
	protector.setEndpoint(stub.path())

	// Warm the runtime up so the measurement excludes threads that any first
	// unix socket round trip would have created anyway.
	warmup, _ := newSocketPair(t)
	if err := protector.protect(warmup); err != nil {
		t.Fatalf("warm the protect endpoint up: %v", err)
	}
	before := pprof.Lookup("threadcreate").Count()

	descriptors := make([]int, callers)
	for index := range descriptors {
		descriptors[index], _ = newSocketPair(t)
	}
	var wait sync.WaitGroup
	failures := make(chan error, callers)
	for _, descriptor := range descriptors {
		wait.Add(1)
		go func() {
			defer wait.Done()
			if err := protector.protect(descriptor); err != nil {
				failures <- err
			}
		}()
	}
	wait.Wait()
	close(failures)
	for err := range failures {
		t.Fatalf("concurrent protect failed: %v", err)
	}

	// Parallelism alone lets the scheduler add threads, so allow for that and
	// still fail long before the one-thread-per-connection regression.
	budget := runtime.GOMAXPROCS(0) + 16
	if growth := pprof.Lookup("threadcreate").Count() - before; growth > budget {
		t.Fatalf("%d concurrent protect calls created %d threads, want at most %d", callers, growth, budget)
	}
}
