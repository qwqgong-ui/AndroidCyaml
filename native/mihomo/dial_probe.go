package main

import (
	"sort"
	"strings"
	"sync"
)

// dialProbe attributes a reconnect storm instead of leaving it to be guessed at.
//
// The counters next to this one say how many dials failed and how they failed.
// They cannot say *what* was being dialed, and without that the only way to
// identify a storm is to read `ss` from outside and match IP addresses against
// guesses -- which is how two wrong conclusions were reached before this
// existed. mihomo already logs everything needed: its dial-failure line carries
// the outbound, the matched rule and the destination as the client asked for it,
// which is the domain rather than the address it resolved to.
//
// This keeps a bounded tally of that, and the diagnostics sampler reports the
// busiest entries. One sample then answers "which host, matched by which rule,
// through which outbound" directly.
//
// PRIVACY: unlike the bucket counters, this records destinations. It is
// populated only while diagnostics sampling is on -- an explicit, user-facing
// toggle -- and only the top few entries are ever emitted. Anyone exporting a
// diagnostics log with sampling enabled is exporting the hosts that failed.
type dialProbe struct {
	mu        sync.Mutex
	failures  map[string]uint64
	rules     map[string]uint64
	outbounds map[string]uint64
	addresses map[string]uint64
	dropped   uint64
}

const (
	// Enough to see a storm and its neighbours without letting a long tail of
	// one-off destinations grow without bound.
	dialProbeCapacity = 512

	// How many entries reach a diagnostics sample. A storm is concentrated by
	// definition: if the top few do not show it, the tally is not the problem.
	dialProbeReported = 6
)

var platformDialProbe = newDialProbe()

func newDialProbe() *dialProbe {
	return &dialProbe{
		failures:  make(map[string]uint64),
		rules:     make(map[string]uint64),
		outbounds: make(map[string]uint64),
		addresses: make(map[string]uint64),
	}
}

// observeAddress records one dialed socket. The socket hook only ever sees the
// resolved address, so this is the IP view; observeFailure below carries the
// name the client actually asked for.
func (p *dialProbe) observeAddress(address string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.bump(p.addresses, address)
}

// observeFailure parses one mihomo dial-failure line. The format is
//
//	[TCP] dial OUTBOUND (match TYPE/PAYLOAD) SOURCE --> DEST error: REASON
//
// with the rule clause absent when nothing matched. Parsing is deliberately
// tolerant: an upstream wording change must cost a missing tally, never a panic
// on a log call site that blocks the core.
func (p *dialProbe) observeFailure(payload string) {
	destination := between(payload, " --> ", " error:")
	if destination == "" {
		return
	}
	outbound := between(payload, " dial ", " ")
	rule := between(payload, "(match ", ")")

	p.mu.Lock()
	defer p.mu.Unlock()
	p.bump(p.failures, destination)
	if rule != "" {
		p.bump(p.rules, rule)
	}
	if outbound != "" {
		p.bump(p.outbounds, outbound)
	}
}

// bump requires p.mu.
func (p *dialProbe) bump(into map[string]uint64, key string) {
	if _, known := into[key]; !known && len(into) >= dialProbeCapacity {
		p.dropped++
		return
	}
	into[key]++
}

// counters reports the busiest entries. Keys carry the destination itself so the
// existing sampler, which renders every metric as `key=value`, needs no change.
func (p *dialProbe) counters(into map[string]uint64) {
	p.mu.Lock()
	defer p.mu.Unlock()

	top(into, "dialFailDst.", p.failures)
	top(into, "dialFailRule.", p.rules)
	top(into, "dialFailOut.", p.outbounds)
	top(into, "dialAddr.", p.addresses)
	into["dialProbeDistinct"] = uint64(len(p.failures))
	into["dialProbeAddresses"] = uint64(len(p.addresses))
	into["dialProbeDropped"] = p.dropped
}

// reset clears the tally, so each diagnostics window is attributable on its own
// rather than being dominated forever by one early burst.
func (p *dialProbe) reset() {
	p.mu.Lock()
	defer p.mu.Unlock()
	clear(p.failures)
	clear(p.rules)
	clear(p.outbounds)
	clear(p.addresses)
	p.dropped = 0
}

func top(into map[string]uint64, prefix string, from map[string]uint64) {
	if len(from) == 0 {
		return
	}
	keys := make([]string, 0, len(from))
	for key := range from {
		keys = append(keys, key)
	}
	sort.Slice(keys, func(a, b int) bool {
		if from[keys[a]] != from[keys[b]] {
			return from[keys[a]] > from[keys[b]]
		}
		return keys[a] < keys[b]
	})
	if len(keys) > dialProbeReported {
		keys = keys[:dialProbeReported]
	}
	for _, key := range keys {
		into[prefix+sanitizeMetricKey(key)] = from[key]
	}
}

// between returns the text between the first open and the next close after it.
func between(payload, open, close string) string {
	start := strings.Index(payload, open)
	if start < 0 {
		return ""
	}
	start += len(open)
	end := strings.Index(payload[start:], close)
	if end < 0 {
		return ""
	}
	return strings.TrimSpace(payload[start : start+end])
}

// sanitizeMetricKey keeps a destination readable while making sure it cannot
// break the `key=value` shape the diagnostics line is parsed with.
func sanitizeMetricKey(key string) string {
	replaced := strings.Map(func(r rune) rune {
		switch {
		case r == ' ', r == '=', r == '\n', r == '\r', r == '\t':
			return '_'
		default:
			return r
		}
	}, key)
	if len(replaced) > 80 {
		replaced = replaced[:80]
	}
	return replaced
}
