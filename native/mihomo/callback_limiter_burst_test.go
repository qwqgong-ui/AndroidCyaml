package main

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// TestCallbackLimiterHoldsUnderMeasuredBurst uses the burst this bound exists
// for. On device, one CDN hostname with two A records produced 470 simultaneous
// half-open sockets, so 470 dials wanted to protect at the same instant.
//
// Every one of them now enters cgo through this limiter, and a goroutine inside
// cgo owns its OS thread. If the bound leaked under a burst the Go runtime would
// answer by creating replacement threads, which is the failure this replaced.
// So the property is not "usually 8": it is never more than 8, and everyone
// still finishes.
func TestCallbackLimiterHoldsUnderMeasuredBurst(t *testing.T) {
	const (
		limit   = maxConcurrentPlatformCallbacks
		callers = 470
	)

	limiter := newCallbackLimiter(limit)
	var active atomic.Int32
	var peak atomic.Int32
	var completed atomic.Int32
	var wait sync.WaitGroup

	wait.Add(callers)
	for range callers {
		go func() {
			defer wait.Done()
			withCallbackPermit(limiter, func() struct{} {
				current := active.Add(1)
				for {
					observed := peak.Load()
					if current <= observed || peak.CompareAndSwap(observed, current) {
						break
					}
				}
				// Stand in for the Binder round trip into netd.
				time.Sleep(200 * time.Microsecond)
				active.Add(-1)
				completed.Add(1)
				return struct{}{}
			})
		}()
	}

	done := make(chan struct{})
	go func() {
		wait.Wait()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(30 * time.Second):
		t.Fatalf("burst did not drain: %d of %d completed", completed.Load(), callers)
	}

	if got := peak.Load(); got > limit {
		t.Fatalf("peak concurrency into the foreign runtime = %d, exceeds the bound of %d", got, limit)
	}
	if got := completed.Load(); got != callers {
		t.Fatalf("completed %d callbacks, want %d", got, callers)
	}
	if active.Load() != 0 {
		t.Fatalf("%d permits leaked", active.Load())
	}
}

// TestPlatformCallbackBoundMatchesReferenceClients pins the value itself.
// ClashMetaForAndroid and FlClash both bound the same callbacks with a
// semaphore; an unbounded value here reintroduces the thread explosion.
func TestPlatformCallbackBoundMatchesReferenceClients(t *testing.T) {
	if maxConcurrentPlatformCallbacks <= 0 {
		t.Fatal("platform callbacks are unbounded")
	}
	if maxConcurrentPlatformCallbacks > 16 {
		t.Fatalf("platform callback bound %d is far above the reference clients", maxConcurrentPlatformCallbacks)
	}
}
