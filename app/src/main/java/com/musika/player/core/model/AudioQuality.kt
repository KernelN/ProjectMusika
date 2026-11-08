package com.musika.player.core.model

import java.util.Locale
import kotlinx.serialization.Serializable

@Serializable
data class AudioQuality(
    val format: String, // "FLAC", "ALAC", "WAV", "OPUS", "OGG", "AAC", "MP3", "UNKNOWN"
    val bitrateKbps: Int = 0,
    val sampleRateHz: Int = 44100,
    val bitDepth: Int = 16,
    val isLossless: Boolean = false
) : Comparable<AudioQuality> {

    /**
     * Ranks audio formats based on the locked project rule:
     * Lossless FLAC/ALAC/WAV > Opus/OGG/AAC > MP3 by bitrate.
     */
    private val formatTier: Int
        get() = when (format.uppercase(Locale.US)) {
            "FLAC", "ALAC", "WAV", "APE" -> 3
            "OPUS", "OGG", "AAC", "M4A" -> 2
            "MP3", "WMA" -> 1
            else -> 0
        }

    override fun compareTo(other: AudioQuality): Int {
        val tierDiff = this.formatTier.compareTo(other.formatTier)
        if (tierDiff != 0) return tierDiff

        if (this.isLossless && other.isLossless) {
            val bitDepthDiff = this.bitDepth.compareTo(other.bitDepth)
            if (bitDepthDiff != 0) return bitDepthDiff
            return this.sampleRateHz.compareTo(other.sampleRateHz)
        }

        return this.bitrateKbps.compareTo(other.bitrateKbps)
    }

    val sampleRateKhzFormatted: String
        get() = String.format(Locale.US, "%.1f kHz", sampleRateHz / 1000.0)

    val bitrateFormatted: String
        get() = if (bitrateKbps > 0) "${bitrateKbps} kbps" else "VBR"

    val telemetrySummary: String
        get() = "$format | $bitrateFormatted | $sampleRateKhzFormatted | ${bitDepth}bit"
}
