package main

import (
	"fmt"
	"sort"
	"strings"
	"sync"

	core "github.com/metacubex/mihomo/androidcyaml"
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
// toggle -- and reported only to the core log, which the log-level switch
// already governs. It is deliberately kept out of the diagnostics metrics line,
// because that file is the one meant to be exported and shared.
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

	// Entries per log line. The window is reported in full -- a truncated view
	// is what made two earlier attributions wrong -- so this only decides how
	// the full list is wrapped, not how much of it survives.
	dialProbePerLine = 8
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

// counters reports only the shape of the window. The destinations themselves go
// to the log instead -- see report.
func (p *dialProbe) counters(into map[string]uint64) {
	p.mu.Lock()
	defer p.mu.Unlock()

	into["dialProbeDistinct"] = uint64(len(p.failures))
	into["dialProbeAddresses"] = uint64(len(p.addresses))
	into["dialProbeDropped"] = p.dropped
}

// report writes the window to mihomo's own log, in full.
//
// The destinations belong here rather than in the diagnostics metrics line for
// two reasons. They are text, and that line is numeric -- carrying them as
// synthetic `key=value` names worked but made every destination a permanent
// column in a file meant for counters. And the log already has the control this
// question needs: the log-level switch decides whether any of it is emitted.
//
// Every entry the window collected is reported, wrapped across lines. A ranked
// excerpt is what produced two wrong attributions before this existed: the
// address that explains a storm is not reliably in the top few, and the long
// tail is what tells a P2P peer list apart from a single dead CDN. Bounding
// happens once, at the capacity cap, and how much was lost to it is stated.
//
// Nothing is logged for a quiet window, so leaving the level raised does not
// turn into a stream of empty lines.
func (p *dialProbe) report() {
	p.mu.Lock()
	failures := ranked(p.failures)
	rules := ranked(p.rules)
	outbounds := ranked(p.outbounds)
	addresses := ranked(p.addresses)
	dropped := p.dropped
	p.mu.Unlock()

	if len(failures) == 0 && len(addresses) == 0 {
		return
	}

	emit("dial probe failures", failures)
	emit("dial probe rules", rules)
	emit("dial probe outbounds", outbounds)
	emit("dial probe addresses", addresses)
	if dropped != 0 {
		core.Warnln(
			"dial probe: %d entries dropped past the %d cap; the window is incomplete",
			dropped, dialProbeCapacity,
		)
	}
}

// emit writes one section, wrapped, with each line saying which part of the
// whole it carries so a truncated view is never mistaken for the whole.
func emit(label string, entries []string) {
	if len(entries) == 0 {
		return
	}
	for start := 0; start < len(entries); start += dialProbePerLine {
		end := min(start+dialProbePerLine, len(entries))
		core.Infoln(
			"%s [%d-%d of %d]: %s",
			label, start+1, end, len(entries), strings.Join(entries[start:end], " "),
		)
	}
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

// ranked renders every entry as `name=count`, busiest first. Nothing is
// dropped here; the only bound on the window is the capacity cap.
func ranked(from map[string]uint64) []string {
	if len(from) == 0 {
		return nil
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
	entries := make([]string, 0, len(keys))
	for _, key := range keys {
		entries = append(entries, fmt.Sprintf("%s=%d", sanitizeEntry(key), from[key]))
	}
	return entries
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

// sanitizeEntry keeps a destination readable while making sure it cannot break
// the `name=count` shape the log line is read with, or run to an unbounded
// length because something upstream put an unexpected string in the payload.
func sanitizeEntry(key string) string {
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
