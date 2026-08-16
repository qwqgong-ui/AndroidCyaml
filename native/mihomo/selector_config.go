package main

import (
	"fmt"
	"strings"

	"github.com/metacubex/mihomo/config"
)

func firstConfiguredSelector(rawCfg *config.RawConfig) string {
	if rawCfg == nil {
		return ""
	}
	for _, group := range rawCfg.ProxyGroup {
		groupType := strings.ToLower(strings.TrimSpace(fmt.Sprint(group["type"])))
		if groupType != "select" && groupType != "selector" {
			continue
		}
		name := strings.TrimSpace(fmt.Sprint(group["name"]))
		if name != "" {
			return name
		}
	}
	return ""
}
