package com.zeta.board

import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.perf.metrics.Trace

/**
 * Release monitoring (Firebase Performance Monitoring). Safe to call any time: does nothing when the build has no google-services.json.
 * PRIVACY: only timings and counts are recorded, with a short result word like "ok". Never typed text, never URLs.
 * The Performance Gradle plugin is left out on purpose, because that plugin is what records network request addresses.
 */
object Perf {
    private fun fp(): FirebasePerformance? = try {
        FirebasePerformance.getInstance()
    } catch (e: Throwable) {
        null
    }

    /** Remote Config can switch monitoring off for everyone (key perf_on). */
    fun setEnabled(on: Boolean) {
        try {
            fp()?.setPerformanceCollectionEnabled(on)
        } catch (e: Throwable) {
        }
    }

    /** Starts a timing called [name]. Give the result back to [end]. */
    fun begin(name: String): Trace? = try {
        fp()?.newTrace(name)?.also { it.start() }
    } catch (e: Throwable) {
        null
    }

    fun end(t: Trace?, result: String? = null) {
        try {
            if (t == null) return
            if (result != null) t.putAttribute("result", result)
            t.stop()
        } catch (e: Throwable) {
        }
    }

    // ---- how smoothly the keyboard draws: counted per typing session, sent when the keyboard closes ----
    private var draws = 0L
    private var slow = 0L
    private var frozen = 0L

    /** One keyboard frame took [nanos] to record on the main thread. */
    fun draw(nanos: Long) {
        draws++
        val ms = nanos / 1_000_000L
        if (ms > 16L) slow++
        if (ms > 50L) frozen++
    }

    fun flushDraw() {
        val d = draws
        val s = slow
        val z = frozen
        draws = 0L
        slow = 0L
        frozen = 0L
        if (d < 30L) return
        try {
            val t = begin("keyboard_draw") ?: return
            t.putMetric("draws", d)
            t.putMetric("slow_draws", s)
            t.putMetric("frozen_draws", z)
            t.stop()
        } catch (e: Throwable) {
        }
    }
}
