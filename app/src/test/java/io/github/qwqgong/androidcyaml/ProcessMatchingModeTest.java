package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class ProcessMatchingModeTest {
    @Test
    public void missingValuePreservesLegacyEnabledDefault() {
        assertEquals(ProcessMatchingMode.ALWAYS, ProcessMatchingMode.fromWireValue(null));
        assertEquals(ProcessMatchingMode.ALWAYS, ProcessMatchingMode.fromWireValue(""));
    }

    @Test
    public void parsesEveryMihomoMode() {
        for (ProcessMatchingMode mode : ProcessMatchingMode.values()) {
            assertEquals(mode, ProcessMatchingMode.fromWireValue(mode.wireValue()));
        }
        assertEquals(ProcessMatchingMode.STRICT, ProcessMatchingMode.fromWireValue(" STRICT "));
    }

    @Test
    public void rejectsUnknownMode() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProcessMatchingMode.fromWireValue("enabled")
        );
    }

    @Test
    public void runtimeDefaultsToAlways() {
        assertEquals(
                ProcessMatchingMode.ALWAYS,
                RuntimeOverrideSettings.defaults().processMatchingMode()
        );
    }
}
