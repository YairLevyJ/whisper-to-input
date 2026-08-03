package com.example.whispertoinput.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

// A cheap, best-effort estimate of whether the network or the local model will finish
// transcription sooner, used by "Auto" transcription mode. This is deliberately not a real race
// between the two backends: running both for every recording would double battery/CPU/network
// cost, which contradicts the point of an automatic low-effort choice. Instead it uses figures
// Android already tracks (link type, OS-reported bandwidth estimate) plus the audio file size, so
// there is no extra network round trip and no measurable added latency before transcription starts.
object NetworkEstimator {
    // Rough allowance for request/response overhead on top of the raw upload time: TLS handshake,
    // server-side queueing, and the model's own processing time on the server. Not measured per
    // request, just a conservative constant so very small/fast links aren't treated as instant.
    private const val API_FIXED_OVERHEAD_SECONDS = 1.5f

    fun isNetworkUsable(context: Context): Boolean {
        val capabilities = activeNetworkCapabilities(context) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // Returns null when Android hasn't got a usable bandwidth estimate (some devices/links don't
    // report one), in which case the caller should fall back to its own default.
    fun estimateApiSeconds(context: Context, audioBytes: Long): Float? {
        val capabilities = activeNetworkCapabilities(context) ?: return null
        val upstreamKbps = capabilities.linkUpstreamBandwidthKbps
        if (upstreamKbps <= 0) return null

        val bytesPerSecond = upstreamKbps * 1000.0 / 8.0
        return (audioBytes / bytesPerSecond).toFloat() + API_FIXED_OVERHEAD_SECONDS
    }

    fun estimateLocalSeconds(audioDurationSeconds: Float, variant: ModelVariant): Float =
        audioDurationSeconds / variant.estimatedSpeedFactor

    private fun activeNetworkCapabilities(context: Context): NetworkCapabilities? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getNetworkCapabilities(network)
    }
}
