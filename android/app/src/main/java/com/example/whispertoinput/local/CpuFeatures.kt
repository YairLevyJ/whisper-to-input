package com.example.whispertoinput.local

import android.os.Build
import android.util.Log
import java.io.File

// The native inference library is compiled against an ARM baseline above the ABI minimum
// (armv8.2-a+fp16+dotprod for arm64-v8a, neon-vfpv4 for armeabi-v7a) because those instructions
// are worth roughly an order of magnitude in transcription speed - see the comment in
// src/main/cpp/CMakeLists.txt. The cost is that a pre-~2017 chip would hit SIGILL somewhere inside
// a matmul kernel instead of failing cleanly, so the feature flags are checked before the library
// is ever loaded and local transcription is reported unavailable when they're missing.
object CpuFeatures {
    private const val TAG = "CpuFeatures"

    val supportsLocalInference: Boolean by lazy { checkRequiredFeatures() }

    private fun checkRequiredFeatures(): Boolean {
        val features = readCpuFeatures()
        if (features.isEmpty()) {
            // Some hardened devices don't expose /proc/cpuinfo flags. Assume a 64-bit device is
            // modern enough (arm64 phones predating FEAT_FP16 are rare and increasingly extinct)
            // and that a 32-bit one is not.
            val is64Bit = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
            Log.w(TAG, "Could not read CPU features; assuming supported=$is64Bit")
            return is64Bit
        }

        return if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) {
            val ok = features.contains("asimdhp") && features.contains("asimddp")
            if (!ok) Log.w(TAG, "CPU lacks asimdhp/asimddp; local inference unavailable")
            ok
        } else {
            val ok = features.contains("vfpv4") && features.contains("neon")
            if (!ok) Log.w(TAG, "CPU lacks vfpv4/neon; local inference unavailable")
            ok
        }
    }

    // The "Features" line of /proc/cpuinfo lists the kernel's hwcap names, e.g.
    // "Features : fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimddp".
    private fun readCpuFeatures(): Set<String> = try {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("Features") || it.startsWith("flags") }
            .flatMap { line -> line.substringAfter(':', "").trim().split(Regex("\\s+")) }
            .filter { it.isNotEmpty() }
            .toSet()
    } catch (e: Exception) {
        Log.w(TAG, "Failed to read /proc/cpuinfo: ${e.message}")
        emptySet()
    }
}
