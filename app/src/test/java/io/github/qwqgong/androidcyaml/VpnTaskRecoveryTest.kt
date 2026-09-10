package io.github.qwqgong.androidcyaml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnTaskRecoveryTest {
    @Test fun taskOnlyRecreationRecoversRequestedSession() {
        assertTrue(AndroidVpnService.shouldRecoverRemovedTask(true, false, false))
    }
    @Test fun removingUiDoesNotRestartLiveCore() {
        assertFalse(AndroidVpnService.shouldRecoverRemovedTask(true, false, true))
    }
    @Test fun stoppedOrRevokedSessionStaysStoppedAfterRecreation() {
        assertFalse(AndroidVpnService.shouldRecoverRemovedTask(false, false, false))
        assertFalse(AndroidVpnService.shouldRecoverRemovedTask(true, true, false))
    }
}
