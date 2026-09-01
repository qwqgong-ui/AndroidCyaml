package main

import (
	"strings"
	"sync"
)

// coreLogCapture retains mihomo's own log lines so the platform's diagnostics
// log can carry them.
//
// The counters next to this one say how many warnings the core produced and how
// they classify. That is enough to notice a problem and useless for diagnosing
// one: the line naming the destination, the matched rule and the outbound only
// exists in the core's log stream, which lives in the dashboard's log view and
// is gone as soon as the process restarts. The diagnostics log is the artefact
// that survives, rotates and can be exported -- so the core's lines have to
// reach it, rather than the platform's events being pushed the other way into
// a stream that is not kept.
//
// Capture is a bounded ring drained once per diagnostics sample. Handing each
// line to Java as it arrives would put a JNI upcall on mihomo's log call site,
// which blocks the core while it waits, and would reintroduce exactly the
// unbounded cgo traffic the callback limiter exists to prevent.
type coreLogCapture struct {
	mu      sync.Mutex
	lines   []string
	dropped uint64
	// info gates the volume. Warnings and errors are always worth keeping;
	// mihomo's INFO stream carries one line per connection, which would fill
	// the ring during ordinary browsing and push out the failures.
	info bool
}

// coreLogCaptureCapacity bounds the ring between drains. A sample is one minute
// apart, and a storm produces thousands of lines a minute -- so this is not
// meant to hold everything, it is meant to hold a readable window of it and say
// how much it could not.
const coreLogCaptureCapacity = 2048

var capturedCoreLog coreLogCapture

// setInfo chooses whether ordinary connection lines are retained.
func (c *coreLogCapture) setInfo(enabled bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.info = enabled
}

func (c *coreLogCapture) wantsInfo() bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.info
}

// record keeps one line. Called from the log pump, so it does no I/O and takes
// the lock only long enough to append.
func (c *coreLogCapture) record(level, payload string) {
	if payload == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.lines) >= coreLogCaptureCapacity {
		c.dropped++
		return
	}
	c.lines = append(c.lines, level+" "+strings.TrimSpace(payload))
}

// drain hands over everything retained since the last call and reports how many
// lines the cap discarded, so a truncated window is never mistaken for a quiet
// one.
func (c *coreLogCapture) drain() ([]string, uint64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.lines) == 0 && c.dropped == 0 {
		return nil, 0
	}
	lines, dropped := c.lines, c.dropped
	c.lines, c.dropped = nil, 0
	return lines, dropped
}

// reset drops anything retained, for a runtime that is going down.
func (c *coreLogCapture) reset() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.lines, c.dropped = nil, 0
}
