package main

import (
	"runtime/metrics"
	"testing"
)

// A metric this Go version stopped exporting would otherwise turn into a log
// full of absent fields that nobody notices for days. Fail the build instead.
func TestSampledRuntimeMetricsAllResolve(t *testing.T) {
	available := make(map[string]metrics.ValueKind)
	for _, description := range metrics.All() {
		available[description.Name] = description.Kind
	}
	for _, wanted := range sampledRuntimeMetrics {
		kind, found := available[wanted.name]
		if !found {
			t.Errorf("%s (%s) is not exported by this Go version", wanted.name, wanted.key)
			continue
		}
		if kind != metrics.KindUint64 {
			t.Errorf("%s has kind %v, want KindUint64", wanted.name, kind)
		}
	}
	if missing := unavailableRuntimeMetrics(); len(missing) != 0 {
		t.Errorf("diagnostics reader dropped %v", missing)
	}
}

func TestCollectRuntimeMetricsReportsEverySampledKey(t *testing.T) {
	sample := collectRuntimeMetrics()
	for _, wanted := range sampledRuntimeMetrics {
		if _, found := sample[wanted.key]; !found {
			t.Errorf("sample is missing %s", wanted.key)
		}
	}
	if _, found := sample["cgoCalls"]; !found {
		t.Error("sample is missing cgoCalls")
	}
	if sample["goTotal"] == 0 {
		t.Error("goTotal is zero, so the runtime reported nothing mapped")
	}
	if sample["goroutines"] == 0 {
		t.Error("goroutines is zero, which cannot be true inside a test")
	}
}

// The reader owns its sample slice, so a second read must refresh values rather
// than accumulate or clear them.
func TestCollectRuntimeMetricsIsRepeatable(t *testing.T) {
	first := collectRuntimeMetrics()
	second := collectRuntimeMetrics()
	if len(first) != len(second) {
		t.Fatalf("sample sizes differ across reads: %d then %d", len(first), len(second))
	}
	if second["goGcCycles"] < first["goGcCycles"] {
		t.Errorf("GC cycle counter went backwards: %d then %d", first["goGcCycles"], second["goGcCycles"])
	}
}
