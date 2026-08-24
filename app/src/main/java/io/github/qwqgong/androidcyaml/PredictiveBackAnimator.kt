package io.github.qwqgong.androidcyaml

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import android.window.BackEvent

/**
 * Drives the predictive back transform on the dashboard surface while a back
 * gesture is in flight, so returning to the previous dashboard page reads as a
 * return instead of a silent content swap.
 */
class PredictiveBackAnimator(private val target: View) {

    private val edgeShift = dp(EDGE_SHIFT_DP)
    private val cornerRadius = dp(CORNER_RADIUS_DP)
    private val settleInterpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

    private var settleAnimator: ValueAnimator? = null
    private var scaleProgress = 0f
    private var appliedRadius = 0f

    init {
        target.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, appliedRadius)
            }
        }
    }

    /** Begins a gesture, taking over from any settle animation still running. */
    fun start(event: BackEvent) {
        settleAnimator?.cancel()
        settleAnimator = null
        target.clipToOutline = true
        apply(event)
    }

    /** Tracks the in-flight gesture. */
    fun update(event: BackEvent) {
        if (settleAnimator != null) {
            return
        }
        apply(event)
    }

    /**
     * Releases the surface back to rest. Callers commit the navigation first so
     * the incoming page settles in underneath the transform.
     */
    fun settle() {
        val from = scaleProgress
        if (from <= 0f) {
            reset()
            return
        }
        settleAnimator?.cancel()
        val animator = ValueAnimator.ofFloat(from, 0f)
        animator.duration = SETTLE_DURATION_MS
        animator.interpolator = settleInterpolator
        val translation = target.translationX
        animator.addUpdateListener { update ->
            val fraction = update.animatedValue as Float
            val ratio = fraction / from
            scaleProgress = fraction
            applyScale(fraction)
            target.translationX = translation * ratio
            applyRadius(cornerRadius * fraction)
        }
        animator.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) {
                    reset()
                }
            }
        })
        settleAnimator = animator
        animator.start()
    }

    /** Drops the transform immediately, e.g. when the surface is torn down. */
    fun reset() {
        settleAnimator?.cancel()
        settleAnimator = null
        scaleProgress = 0f
        target.scaleX = 1f
        target.scaleY = 1f
        target.translationX = 0f
        target.pivotX = target.width / 2f
        target.pivotY = target.height / 2f
        target.clipToOutline = false
        applyRadius(0f)
    }

    private fun apply(event: BackEvent) {
        val width = target.width
        val height = target.height
        if (width <= 0 || height <= 0) {
            return
        }
        val progress = event.progress.coerceIn(0f, 1f)
        val fromLeftEdge = event.swipeEdge == BackEvent.EDGE_LEFT
        scaleProgress = progress
        // Pivot on the edge opposite the gesture so the surface pulls away from
        // the finger, and follow the touch vertically the way the system does.
        target.pivotX = if (fromLeftEdge) width.toFloat() else 0f
        target.pivotY = event.touchY.coerceIn(0f, height.toFloat())
        applyScale(progress)
        target.translationX = edgeShift * progress * if (fromLeftEdge) 1f else -1f
        applyRadius(cornerRadius * progress)
    }

    private fun applyScale(progress: Float) {
        val scale = 1f - MAX_SCALE_DELTA * progress
        target.scaleX = scale
        target.scaleY = scale
    }

    private fun applyRadius(radius: Float) {
        if (appliedRadius == radius) {
            return
        }
        appliedRadius = radius
        target.invalidateOutline()
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        target.resources.displayMetrics,
    )

    private companion object {
        const val MAX_SCALE_DELTA = 0.1f
        const val EDGE_SHIFT_DP = 8f
        const val CORNER_RADIUS_DP = 28f
        const val SETTLE_DURATION_MS = 220L
    }
}
