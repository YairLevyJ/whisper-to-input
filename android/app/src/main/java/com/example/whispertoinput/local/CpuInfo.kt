package com.example.whispertoinput.local

import java.io.File

// Picks how many threads local transcription should use, based on the actual device rather than
// a flat constant. A fixed cap (e.g. 4) wastes most of a flagship's cores and needlessly throttles
// a mid-range phone the same amount, so this instead reads each CPU core's maximum clock speed
// from sysfs and counts how many are "fast" cores.
//
// Most phone SoCs are heterogeneous (ARM big.LITTLE or similar): a handful of high-clock
// performance/prime cores alongside several low-clock efficiency cores. Efficiency cores are
// disproportionately slow at the dense matrix multiplies whisper.cpp is bottlenecked on, so
// counting all of them equally would overestimate useful parallelism. cpuinfo_max_freq gives a
// stable, static way to tell the clusters apart without needing to sample load at runtime.
object CpuInfo {
    // Cores clocked at or above this fraction of the fastest core's max frequency are counted as
    // "fast". 0.8 groups a chip's prime + performance clusters together (typically within ~10-15%
    // of each other) while excluding a low-power efficiency cluster (typically 40-60% of peak).
    private const val FAST_CORE_FREQ_RATIO = 0.8

    val recommendedThreadCount: Int by lazy { computeRecommendedThreadCount() }

    private fun computeRecommendedThreadCount(): Int {
        val totalCores = Runtime.getRuntime().availableProcessors()
        if (totalCores <= 1) return 1

        val maxFreqsKhz = (0 until totalCores).map { core -> readMaxFreqKhz(core) }

        // Some devices restrict sysfs access, or one core temporarily reports a bogus value; if
        // topology can't be read reliably, fall back to "all cores but one" rather than guessing
        // which ones are fast, leaving a little headroom for the UI/system.
        if (maxFreqsKhz.any { it == null }) {
            return (totalCores - 1).coerceAtLeast(1)
        }

        val peak = maxFreqsKhz.filterNotNull().max()
        val fastCoreCount = maxFreqsKhz.count { it != null && it >= peak * FAST_CORE_FREQ_RATIO }

        return fastCoreCount.coerceIn(1, totalCores)
    }

    private fun readMaxFreqKhz(core: Int): Long? = try {
        File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq")
            .readText()
            .trim()
            .toLongOrNull()
    } catch (e: Exception) {
        null
    }
}
