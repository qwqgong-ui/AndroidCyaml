//go:build unix

package main

import (
	"errors"
	"fmt"
	"io"
	"net"
	"sync/atomic"
	"syscall"
	"time"
)

// socketProtector hands an outbound socket to Android's VpnService over a unix
// socket instead of calling Java through cgo/JNI.
//
// VpnService.protect has no NDK equivalent -- only the Java API can ask netd to
// set the "protected from VPN" fwmark bit -- so the request has to reach the
// JVM one way or another. What it must not do is travel as a JNI upcall on
// whichever Go thread happens to be dialing: a goroutine blocked inside cgo
// owns its OS thread, so the runtime answers a reconnect storm by creating
// replacement threads, and every one of them gets attached to ART on its first
// callback. That is where the `Thread-N` high-water mark came from.
//
// Passing the descriptor with SCM_RIGHTS keeps the whole hot path in pure Go:
// the goroutine parks on the network poller instead of pinning an M, no Go
// thread is ever attached to the JVM, and the Java side answers from its own
// bounded worker pool.
type socketProtector struct {
	endpoint atomic.Pointer[string]
	degraded atomic.Bool

	// Counters, not gauges: the diagnostics sampler records them once a minute
	// and the interesting quantity is the delta. A reconnect storm shows up as
	// attempts climbing; a network that is up but unusable shows up as
	// rejections climbing; a broken endpoint shows up as transportErrors.
	attempts        atomic.Uint64
	rejections      atomic.Uint64
	transportErrors atomic.Uint64
}

const (
	// A protect round trip is a unix socket hop plus a netd fwmark round trip,
	// so it normally completes in well under a millisecond. The deadline only
	// exists so a wedged Java side fails the dial instead of stranding it.
	socketProtectTimeout = 5 * time.Second

	// Android's LocalServerSocket listens with a backlog of 50. A burst wide
	// enough to fill it makes connect report EAGAIN rather than queueing, so
	// retry briefly before giving up on the socket path.
	socketProtectDialAttempts = 3
	socketProtectDialBackoff  = time.Millisecond

	socketProtectRequest = 0x01
	socketProtectGranted = 0x01
)

var (
	errSocketProtectUnavailable = errors.New("Android protect endpoint is not configured")
	errSocketProtectRejected    = errors.New("Android VpnService refused to protect the socket")
)

func (p *socketProtector) setEndpoint(path string) {
	if path == "" {
		p.endpoint.Store(nil)
		return
	}
	p.endpoint.Store(&path)
}

func (p *socketProtector) currentEndpoint() string {
	path := p.endpoint.Load()
	if path == nil {
		return ""
	}
	return *path
}

func (p *socketProtector) enabled() bool {
	return p.currentEndpoint() != ""
}

// protect sends fileDescriptor to the Java protect worker and reports whether
// the socket was accepted. A rejection is a decision by VpnService and wraps
// errSocketProtectRejected; anything else means the endpoint itself failed and
// the caller may retry through another path.
func (p *socketProtector) protect(fileDescriptor int) error {
	endpoint := p.currentEndpoint()
	if endpoint == "" {
		return errSocketProtectUnavailable
	}
	p.attempts.Add(1)
	err := p.exchange(endpoint, fileDescriptor)
	switch {
	case err == nil:
	case errors.Is(err, errSocketProtectRejected):
		p.rejections.Add(1)
	default:
		p.transportErrors.Add(1)
	}
	return err
}

func (p *socketProtector) exchange(endpoint string, fileDescriptor int) error {
	connection, err := dialSocketProtect(endpoint)
	if err != nil {
		return err
	}
	defer connection.Close()

	if err := connection.SetDeadline(time.Now().Add(socketProtectTimeout)); err != nil {
		return fmt.Errorf("arm Android protect deadline: %w", err)
	}
	rights := syscall.UnixRights(fileDescriptor)
	if _, _, err := connection.WriteMsgUnix([]byte{socketProtectRequest}, rights, nil); err != nil {
		return fmt.Errorf("send socket to the Android protect endpoint: %w", err)
	}
	var reply [1]byte
	if _, err := io.ReadFull(connection, reply[:]); err != nil {
		return fmt.Errorf("await the Android protect result: %w", err)
	}
	if reply[0] != socketProtectGranted {
		return errSocketProtectRejected
	}
	return nil
}

// counters reports the protect outcome tallies for one diagnostics sample.
func (p *socketProtector) counters(into map[string]uint64) {
	into["protectAttempts"] = p.attempts.Load()
	into["protectRejections"] = p.rejections.Load()
	into["protectTransportErrors"] = p.transportErrors.Load()
}

func dialSocketProtect(endpoint string) (*net.UnixConn, error) {
	address := &net.UnixAddr{Name: endpoint, Net: "unix"}
	var err error
	for attempt := range socketProtectDialAttempts {
		var connection *net.UnixConn
		connection, err = net.DialUnix("unix", nil, address)
		if err == nil {
			return connection, nil
		}
		if attempt+1 < socketProtectDialAttempts {
			time.Sleep(socketProtectDialBackoff << attempt)
		}
	}
	return nil, fmt.Errorf("connect the Android protect endpoint: %w", err)
}

// noteDegraded reports whether this outcome changed the endpoint's health, so
// callers can log the transition instead of one line per failed dial.
func (p *socketProtector) noteDegraded(failed bool) bool {
	return p.degraded.Swap(failed) != failed
}
