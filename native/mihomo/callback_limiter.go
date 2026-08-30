package main

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
