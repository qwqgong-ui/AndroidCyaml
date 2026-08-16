package main

import (
	"testing"

	"github.com/metacubex/mihomo/config"
)

func TestFirstConfiguredSelectorUsesConfigOrder(t *testing.T) {
	raw := config.DefaultRawConfig()
	raw.ProxyGroup = []map[string]any{
		{"name": "automatic", "type": "url-test"},
		{"name": "first", "type": "select"},
		{"name": "second", "type": "selector"},
	}

	if actual := firstConfiguredSelector(raw); actual != "first" {
		t.Fatalf("firstConfiguredSelector() = %q, want first", actual)
	}
}
