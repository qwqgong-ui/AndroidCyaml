package main

import (
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestCallbackLimiterBoundsForeignRuntimeConcurrency(t *testing.T) {
	const (
		limit   = 4
		callers = 32
	)
	limiter := newCallbackLimiter(limit)
	entered := make(chan struct{}, callers)
	release := make(chan struct{})
	var active atomic.Int32
	var peak atomic.Int32
	var wait sync.WaitGroup

	for range callers {
		wait.Add(1)
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
				entered <- struct{}{}
				<-release
				active.Add(-1)
				return struct{}{}
			})
		}()
	}

	for range limit {
		select {
		case <-entered:
		case <-time.After(time.Second):
			t.Fatal("callback limiter did not admit its configured capacity")
		}
	}
	select {
	case <-entered:
		t.Fatal("callback limiter admitted more than its configured capacity")
	case <-time.After(50 * time.Millisecond):
	}

	close(release)
	wait.Wait()
	if got := peak.Load(); got != limit {
		t.Fatalf("peak foreign-runtime concurrency = %d, want %d", got, limit)
	}
}

func TestCallbackLimiterReleasesPermitAfterPanic(t *testing.T) {
	limiter := newCallbackLimiter(1)
	func() {
		defer func() {
			if recover() == nil {
				t.Fatal("callback panic was not propagated")
			}
		}()
		withCallbackPermit(limiter, func() struct{} {
			panic("test panic")
		})
	}()

	completed := make(chan struct{})
	go func() {
		withCallbackPermit(limiter, func() struct{} {
			close(completed)
			return struct{}{}
		})
	}()
	select {
	case <-completed:
	case <-time.After(time.Second):
		t.Fatal("callback permit leaked after panic")
	}
}
