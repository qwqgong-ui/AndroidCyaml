package io.github.qwqgong.androidcyaml.network

/** Retries failed native reconciliation even when Android emits no further change. */
class NetworkTransitionRetry(
    private val schedule: (Runnable, Long) -> Unit,
    private val remove: (Runnable) -> Unit,
    private val dispatch: (Runnable) -> Unit,
    private val retry: () -> Unit,
) {
    // All state is confined to the runtime executor. A timer only enqueues work.
    private var pending: Runnable? = null
    private var generation = 0L
    private var delayMillis = INITIAL_DELAY_MILLIS

    fun update(needsRetry: Boolean) {
        if (!needsRetry) {
            clear()
            return
        }
        if (pending != null) return
        val expectedGeneration = generation
        val task = Runnable {
            dispatch(Runnable {
                if (generation == expectedGeneration) {
                    pending = null
                    retry()
                }
            })
        }
        pending = task
        schedule(task, delayMillis)
        delayMillis = (delayMillis * 2).coerceAtMost(MAX_DELAY_MILLIS)
    }

    fun clear() {
        generation++
        pending?.let(remove)
        pending = null
        delayMillis = INITIAL_DELAY_MILLIS
    }

    private companion object {
        const val INITIAL_DELAY_MILLIS = 1_000L
        const val MAX_DELAY_MILLIS = 30_000L
    }
}
