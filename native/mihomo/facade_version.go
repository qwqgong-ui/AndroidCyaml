package main

import core "github.com/metacubex/mihomo/androidcyaml"

// expectedFacadeVersion is the androidcyaml contract this wrapper was written
// against. The build resolves mihomo's dev branch fresh on every invocation, so
// without this the first sign of a moved facade would be a core that compiled
// and then behaved wrongly on a device.
const expectedFacadeVersion = 3

// Both conversions are non-negative only when the versions match; a mismatch
// makes one of them a negative constant converted to uint, which does not
// compile. Update the constant above only after re-reading the facade.
const (
	_ = uint(core.FacadeVersion - expectedFacadeVersion)
	_ = uint(expectedFacadeVersion - core.FacadeVersion)
)
