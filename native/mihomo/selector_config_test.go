package main

import (
	"testing"

	core "github.com/metacubex/mihomo/androidcyaml"
)

func TestFirstConfiguredSelectorUsesConfigOrder(t *testing.T) {
	raw := core.DefaultRawConfig()
	raw.ProxyGroup = []map[string]any{
		{"name": "automatic", "type": "url-test"},
		{"name": "first", "type": "select"},
		{"name": "second", "type": "selector"},
	}

	if actual := firstConfiguredSelector(raw); actual != "first" {
		t.Fatalf("firstConfiguredSelector() = %q, want first", actual)
	}
}
