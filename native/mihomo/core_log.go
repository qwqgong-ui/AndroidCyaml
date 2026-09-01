package main

import (
	"strings"
	"sync/atomic"
)

// coreLogCounters classifies mihomo's own warnings and errors into buckets.
//
// The raw messages are deliberately not copied into the diagnostics log. They
// name the destination that failed, and that log exists to be exported and
// shared. Over a long window the useful question is which kind of failure
// spiked and when, and a bucket count answers it without writing down where the
// user was going. The live text stays available in the dashboard's log view.
//
// Every mihomo log call publishes to its observable regardless of the
// configured level, and a slow subscriber blocks the call site, so observe must
// stay a classification and an atomic add -- nothing else.
type coreLogCounters struct {
	warnings atomic.Uint64
	errors   atomic.Uint64
	buckets  [len(coreLogBuckets)]atomic.Uint64
}

var coreLogBuckets = [...]struct {
	key     string
	needles []string
}{
	{"coreErrTimeout", []string{"timeout", "deadline exceeded", "timed out"}},
	{"coreErrRefused", []string{"connection refused", "refused"}},
	{"coreErrUnreachable", []string{"unreachable", "no route to host", "no suitable"}},
	{"coreErrReset", []string{"connection reset", "broken pipe", "aborted"}},
	{"coreErrDns", []string{"no such host", "nxdomain", "resolve", "dns"}},
	{"coreErrTls", []string{"tls", "certificate", "handshake"}},
	{"coreErrClosed", []string{"eof", "closed"}},
	{"coreErrProtect", []string{"protect"}},
	// Keep last: the fallback bucket matches nothing and catches the rest.
	{"coreErrOther", nil},
}

func (c *coreLogCounters) observe(warning bool, payload string) {
	if warning {
		c.warnings.Add(1)
	} else {
		c.errors.Add(1)
	}
	c.buckets[classifyCoreLog(payload)].Add(1)
}

func classifyCoreLog(payload string) int {
	lowered := strings.ToLower(payload)
	for index, bucket := range coreLogBuckets {
		for _, needle := range bucket.needles {
			if strings.Contains(lowered, needle) {
				return index
			}
		}
	}
	return len(coreLogBuckets) - 1
}

func (c *coreLogCounters) counters(into map[string]uint64) {
	into["coreWarnings"] = c.warnings.Load()
	into["coreErrors"] = c.errors.Load()
	for index, bucket := range coreLogBuckets {
		into[bucket.key] = c.buckets[index].Load()
	}
}
