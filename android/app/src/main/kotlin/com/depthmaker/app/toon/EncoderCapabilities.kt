package com.depthmaker.app.toon

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Asks the platform whether a hardware AVC encoder can do a given size and rate
 * before we commit to it. Cheap to call and worth calling: 720p60 is fine on
 * nearly everything shipped since ~2016, but "nearly" is not "all", and the
 * failure mode without this probe is a configure() exception at export time.
 */
object EncoderCapabilities {

    const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    private val videoCaps: MediaCodecInfo.VideoCapabilities? by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .asSequence()
                .filter { it.isEncoder }
                .filter { MIME in it.supportedTypes.map(String::lowercase) }
                .mapNotNull { runCatching { it.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull() }
                // Pick the most capable encoder rather than the first: some
                // devices list a software AVC encoder ahead of the hardware one.
                .maxByOrNull { it.supportedWidths.upper.toLong() * it.supportedHeights.upper }
        }.onFailure { Log.w(TAG, "encoder probe failed", it) }.getOrNull()
    }

    val limits = EncoderLimits { width, height, fps ->
        val caps = videoCaps ?: return@EncoderLimits false
        runCatching { caps.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)
    }

    /** For the settings screen: "720p60 supported on this device?" */
    fun supports720p60(): Boolean = limits.supports(1280, 720, 60)

    private const val TAG = "EncoderCapabilities"
}
