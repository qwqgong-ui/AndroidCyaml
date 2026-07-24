package io.github.qwqgong.androidcyaml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class RuntimeLogLevelTest {
    @Test
    public void missingValueDefaultsToWarning() {
        assertEquals(RuntimeLogLevel.WARNING, RuntimeLogLevel.fromWireValue(null));
        assertEquals(RuntimeLogLevel.WARNING, RuntimeLogLevel.fromWireValue(""));
        assertEquals(RuntimeLogLevel.WARNING, RuntimeLogLevel.fromWireValue("warn"));
    }

    @Test
    public void parsesEveryMihomoLevel() {
        for (RuntimeLogLevel level : RuntimeLogLevel.values()) {
            assertEquals(level, RuntimeLogLevel.fromWireValue(level.wireValue()));
        }
    }

    @Test
    public void rejectsUnknownLevel() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RuntimeLogLevel.fromWireValue("trace")
        );
    }

    @Test
    public void runtimeDefaultsAreWarningAndLocalOnly() {
        RuntimeOverrideSettings defaults = RuntimeOverrideSettings.defaults();

        assertEquals(RuntimeLogLevel.WARNING, defaults.logLevel());
        assertFalse(defaults.lanWebUiPublic());
    }
}
