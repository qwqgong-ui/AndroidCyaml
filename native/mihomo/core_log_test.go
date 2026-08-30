package main

import "testing"

func TestClassifyCoreLogSortsRealMihomoFailures(t *testing.T) {
	cases := map[string]string{
		"[TCP] dial DIRECT error: dial tcp 10.0.0.1:443: i/o timeout": "coreErrTimeout",
		"dial tcp 10.0.0.1:443: connect: connection refused":          "coreErrRefused",
		"dial tcp: connect: network is unreachable":                   "coreErrUnreachable",
		"read tcp: read: connection reset by peer":                    "coreErrReset",
		"lookup failed: no such host":                                 "coreErrDns",
		"remote error: tls: handshake failure":                        "coreErrTls",
		"unexpected EOF while reading response":                       "coreErrClosed",
		"Android protect endpoint unusable, falling back to JNI":      "coreErrProtect",
		"something the classifier has never seen before":              "coreErrOther",
	}
	for payload, expected := range cases {
		got := coreLogBuckets[classifyCoreLog(payload)].key
		if got != expected {
			t.Errorf("classify(%q) = %s, want %s", payload, got, expected)
		}
	}
}

func TestCoreLogCountersReportEveryBucket(t *testing.T) {
	var counters coreLogCounters
	counters.observe(true, "dial tcp: i/o timeout")
	counters.observe(false, "dial tcp: i/o timeout")
	counters.observe(false, "totally unclassified")

	sample := make(map[string]uint64)
	counters.counters(sample)
	if sample["coreWarnings"] != 1 {
		t.Errorf("coreWarnings = %d, want 1", sample["coreWarnings"])
	}
	if sample["coreErrors"] != 2 {
		t.Errorf("coreErrors = %d, want 2", sample["coreErrors"])
	}
	if sample["coreErrTimeout"] != 2 {
		t.Errorf("coreErrTimeout = %d, want 2", sample["coreErrTimeout"])
	}
	if sample["coreErrOther"] != 1 {
		t.Errorf("coreErrOther = %d, want 1", sample["coreErrOther"])
	}
	for _, bucket := range coreLogBuckets {
		if _, found := sample[bucket.key]; !found {
			t.Errorf("sample is missing %s", bucket.key)
		}
	}
}
