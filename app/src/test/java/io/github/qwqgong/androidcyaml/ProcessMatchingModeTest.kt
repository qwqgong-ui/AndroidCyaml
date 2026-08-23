package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProcessMatchingModeTest {
    @Test
    fun missingValuePreservesLegacyEnabledDefault() {
        assertEquals(ProcessMatchingMode.ALWAYS, ProcessMatchingMode.fromWireValue(null))
        assertEquals(ProcessMatchingMode.ALWAYS, ProcessMatchingMode.fromWireValue(""))
    }

    @Test
    fun parsesEveryMihomoMode() {
        for (mode in ProcessMatchingMode.values()) {
            assertEquals(mode, ProcessMatchingMode.fromWireValue(mode.wireValue))
        }
        assertEquals(ProcessMatchingMode.STRICT, ProcessMatchingMode.fromWireValue(" STRICT "))
    }

    @Test
    fun rejectsUnknownMode() {
        assertThrows(IllegalArgumentException::class.java) {
            ProcessMatchingMode.fromWireValue("enabled")
        }
    }

    @Test
    fun runtimeDefaultsToAlways() {
        assertEquals(
            ProcessMatchingMode.ALWAYS,
            RuntimeOverrideSettings.defaults().processMatchingMode,
        )
    }
}
