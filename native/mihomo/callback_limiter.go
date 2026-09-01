package main

// maxConcurrentPlatformCallbacks bounds how many callers may be inside the
// Android runtime at once.
//
// Both platform callbacks -- VpnService.protect and the process-owner lookup --
// are Binder round trips, and a goroutine waiting inside cgo owns its OS thread.
// This bound is the whole reason the thread count stays flat under a reconnect
// storm: excess callers park in the Go scheduler before entering cgo, so the
// runtime never creates replacement Ms for them.
//
// An earlier design routed protect over a unix socket instead, to keep the dial
// path out of cgo entirely. It had no admission control, so a burst of several
// hundred simultaneous dials overran the endpoint listen backlog, and every dial
// that lost that race fell back to this same JNI path anyway -- unbounded,
// because the bound was only ever applied to the fallback. Bounding the
// callbacks is what solves the problem that transport was built to avoid, which
// makes the transport itself unnecessary. ClashMetaForAndroid and FlClash both
// do exactly this: a semaphore and a direct upcall.
//
// It lives here rather than next to its caller so that it, and the burst test
// that pins it, build on every platform instead of only under android/cgo.
const maxConcurrentPlatformCallbacks = 8

// callbackLimiter bounds synchronous calls from Go into a foreign runtime.
//
// A goroutine blocked inside cgo occupies its current Go M (OS thread). Without
// a bound, a dial or process-lookup burst can therefore make the Go scheduler
// create hundreds of replacement M threads while those callbacks wait in JNI.
// Waiting for a permit here parks excess goroutines in the Go scheduler before
// they enter cgo, keeping the native-thread high-water mark bounded.
type callbackLimiter struct {
	permits chan struct{}
}

func newCallbackLimiter(limit int) callbackLimiter {
	if limit <= 0 {
		panic("callback limiter requires a positive limit")
	}
	return callbackLimiter{permits: make(chan struct{}, limit)}
}

func withCallbackPermit[T any](limiter callbackLimiter, callback func() T) T {
	limiter.permits <- struct{}{}
	defer func() { <-limiter.permits }()
	return callback()
}
