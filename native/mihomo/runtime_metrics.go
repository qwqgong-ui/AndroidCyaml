package main

import (
	"runtime"
	"runtime/metrics"
	"sort"
)

// Everything sampled here has to be cheap enough to run on a timer forever.
// runtime/metrics reads a snapshot the runtime already maintains, unlike
// runtime.ReadMemStats, which stops the world.
//
// `goTotal` is the number to watch: it is everything the Go runtime has mapped,
// which is what Android's `dumpsys meminfo` reports in its `Unknown` row. The
// heap breakdown next to it answers the follow-up question -- whether growth is
// live objects or GC headroom the scavenger has not returned yet.
var sampledRuntimeMetrics = []struct {
	name string
	key  string
}{
	{"/memory/classes/total:bytes", "goTotal"},
	{"/memory/classes/heap/objects:bytes", "goHeapObjects"},
	{"/memory/classes/heap/unused:bytes", "goHeapUnused"},
	{"/memory/classes/heap/free:bytes", "goHeapFree"},
	{"/memory/classes/heap/released:bytes", "goHeapReleased"},
	{"/memory/classes/heap/stacks:bytes", "goHeapStacks"},
	{"/memory/classes/os-stacks:bytes", "goOsStacks"},
	{"/memory/classes/metadata/other:bytes", "goMetadata"},
	{"/gc/heap/live:bytes", "goGcLive"},
	{"/gc/cycles/total:gc-cycles", "goGcCycles"},
	{"/sched/goroutines:goroutines", "goroutines"},
}

// runtimeMetricsReader binds the wanted metrics to the ones this Go version
// actually exports. A name the runtime dropped or renamed then costs one absent
// field instead of a log full of zeros nobody notices for days.
type runtimeMetricsReader struct {
	samples   []metrics.Sample
	keys      []string
	supported bool
	missing   []string
}

func newRuntimeMetricsReader() *runtimeMetricsReader {
	available := make(map[string]metrics.ValueKind, len(sampledRuntimeMetrics))
	for _, description := range metrics.All() {
		available[description.Name] = description.Kind
	}
	reader := &runtimeMetricsReader{}
	for _, wanted := range sampledRuntimeMetrics {
		if available[wanted.name] != metrics.KindUint64 {
			reader.missing = append(reader.missing, wanted.name)
			continue
		}
		reader.samples = append(reader.samples, metrics.Sample{Name: wanted.name})
		reader.keys = append(reader.keys, wanted.key)
	}
	reader.supported = len(reader.samples) != 0
	return reader
}

// read is not safe for concurrent use: metrics.Read writes into the sample
// slice this reader owns. The diagnostics sampler is the only caller and it
// samples from a single timer.
func (r *runtimeMetricsReader) read(into map[string]uint64) {
	if !r.supported {
		return
	}
	metrics.Read(r.samples)
	for index, sample := range r.samples {
		if sample.Value.Kind() != metrics.KindUint64 {
			continue
		}
		into[r.keys[index]] = sample.Value.Uint64()
	}
}

var diagnosticRuntimeMetrics = newRuntimeMetricsReader()

// collectRuntimeMetrics reports the Go runtime half of one diagnostics sample.
func collectRuntimeMetrics() map[string]uint64 {
	sample := make(map[string]uint64, len(sampledRuntimeMetrics)+2)
	diagnosticRuntimeMetrics.read(sample)
	// A cumulative cgo counter is the precise counterpart of Android's
	// `Thread-N` odometer: it says whether the dial path still crosses into
	// Java at all, without depending on which threads happen to be alive.
	sample["cgoCalls"] = uint64(runtime.NumCgoCall())
	return sample
}

// unavailableRuntimeMetrics names the metrics this Go version does not export,
// so a sample missing a field is explainable rather than mysterious.
func unavailableRuntimeMetrics() []string {
	missing := append([]string(nil), diagnosticRuntimeMetrics.missing...)
	sort.Strings(missing)
	return missing
}
