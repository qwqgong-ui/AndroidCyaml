package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeLogLevelTest {
    @Test
    fun missingValueDefaultsToWarning() {
        assertEquals(RuntimeLogLevel.WARNING, RuntimeLogLevel.fromWireValue(null))
        assertEquals(RuntimeLogLevel.WARNING, RuntimeLogLevel.fromWireValue(""))
        assertEquals(RuntimeLogLevel.WARNING, RuntimeLogLevel.fromWireValue("warn"))
    }

    @Test
    fun parsesEveryMihomoLevel() {
        for (level in RuntimeLogLevel.values()) {
            assertEquals(level, RuntimeLogLevel.fromWireValue(level.wireValue))
        }
    }

    @Test
    fun rejectsUnknownLevel() {
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeLogLevel.fromWireValue("trace")
        }
    }

    @Test
    fun runtimeDefaultsAreWarningAndLocalOnly() {
        val defaults = RuntimeOverrideSettings.defaults()

        assertEquals(RuntimeLogLevel.WARNING, defaults.logLevel)
        assertFalse(defaults.lanWebUiPublic)
    }
}
