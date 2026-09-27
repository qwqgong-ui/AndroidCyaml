package io.github.qwqgong.androidcyaml.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkTransitionRetryTest {
    private class Harness {
        val timers = mutableListOf<Pair<Runnable, Long>>()
        val dispatched = ArrayDeque<Runnable>()
        var retries = 0
        var fail = true
        val retry: NetworkTransitionRetry = NetworkTransitionRetry(
            schedule = { task, delay -> timers.add(task to delay) },
            remove = { task -> timers.removeAll { it.first === task } },
            dispatch = { dispatched.addLast(it) },
            retry = {
                retries++
                completeAttempt()
            },
        )
        private fun completeAttempt() { retry.update(fail) }
        fun fire() {
            timers.removeAt(0).first.run()
            dispatched.removeFirst().run()
        }
    }

    @Test fun failedTransitionRetriesWithoutAnotherNetworkCallback() {
        val h = Harness()
        h.retry.update(true)
        assertEquals(1_000L, h.timers.single().second)
        h.fail = false
        h.fire()
        assertEquals(1, h.retries)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun repeatedFailuresBackOffAndDoNotDuplicateTimers() {
        val h = Harness()
        h.retry.update(true)
        for (delay in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)) {
            h.retry.update(true)
            assertEquals(delay, h.timers.single().second)
            h.fire()
        }
        h.retry.clear()
        h.retry.update(true)
        assertEquals(1_000L, h.timers.single().second)
    }

    @Test fun stopOrRestartInvalidatesAnAlreadyDispatchedRetry() {
        val h = Harness()
        h.retry.update(true)
        h.timers.removeAt(0).first.run()
        h.retry.clear()
        h.retry.update(true)
        h.dispatched.removeFirst().run()
        assertEquals(0, h.retries)
        assertEquals(1, h.timers.size)
        h.fail = false
        h.fire()
        assertEquals(1, h.retries)
        assertTrue(h.timers.isEmpty())
    }

    @Test fun successfulNewNetworkCancelsOldRetry() {
        val h = Harness()
        h.retry.update(true)
        h.retry.update(false)
        assertTrue(h.timers.isEmpty())
        assertEquals(0, h.retries)
    }
}
