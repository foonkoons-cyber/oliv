package com.depthmaker.app.toon

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ResolutionPreset(val label: String, val longEdge: Int) {
    /** The headline target: 1280 on the long edge, aspect preserved. */
    P720("720p", 1280),
    SOURCE("Source", 0)
}

enum class FpsPreset(val label: String, val cap: Int) {
    /** Follows the source, up to 60. It cannot invent frames — a 30 fps source
     *  exports at 30 no matter what is selected here. */
    UP_TO_60("Up to 60", 60),
    CAP_30("30", 30)
}

data class ExportPlan(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitRate: Int,
    /** Set when the encoder could not do what was asked and we stepped down. */
    val downgradeReason: String? = null
) {
    fun summary(): String = "${width}×${height} · ${fps}fps · ${bitRate / 1_000_000} Mbps"
}

/** What the device's hardware encoder will actually accept. */
fun interface EncoderLimits {
    fun supports(width: Int, height: Int, fps: Int): Boolean
}

object ExportPlanner {

    /** Cartoon output is mostly flat colour and compresses well, but 60 fps
     *  doubles the frame budget, so this is per-pixel-per-second, not a table. */
    private const val BITS_PER_PIXEL = 0.12
    private const val MIN_BITRATE = 3_000_000
    private const val MAX_BITRATE = 20_000_000

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        sourceFps: Float,
        resolution: ResolutionPreset,
        fpsPreset: FpsPreset,
        limits: EncoderLimits
    ): ExportPlan {
        require(sourceWidth > 0 && sourceHeight > 0) { "source dimensions unknown" }

        val (w, h) = scaleTo(sourceWidth, sourceHeight, resolution.longEdge)
        // Never upsample the frame rate: duplicating frames gives a 60 fps
        // container with 30 fps motion, which is worse than being honest.
        val wanted = if (sourceFps > 1f) min(sourceFps.roundToInt(), fpsPreset.cap) else 30

        if (limits.supports(w, h, wanted)) {
            return ExportPlan(w, h, wanted, bitRateFor(w, h, wanted))
        }

        // Frame rate first: half the rate at full size beats full rate at a
        // smaller size for the kind of footage this app is for.
        val halved = max(24, wanted / 2)
        if (wanted > halved && limits.supports(w, h, halved)) {
            return ExportPlan(
                w, h, halved, bitRateFor(w, h, halved),
                "Is phone ka encoder ${w}×${h} @ ${wanted}fps nahi kar sakta — ${halved}fps par export hua."
            )
        }

        val (fw, fh) = scaleTo(sourceWidth, sourceHeight, ResolutionPreset.P720.longEdge)
        for (fps in intArrayOf(wanted, halved, 30, 24)) {
            if (limits.supports(fw, fh, fps)) {
                return ExportPlan(
                    fw, fh, fps, bitRateFor(fw, fh, fps),
                    "Is phone ka encoder ${w}×${h} @ ${wanted}fps nahi kar sakta — ${fw}×${fh} @ ${fps}fps par export hua."
                )
            }
        }

        // Nothing was reported as supported. Encode at 720p30 anyway rather than
        // refusing: the capability tables are advisory and sometimes wrong.
        return ExportPlan(
            fw, fh, 30, bitRateFor(fw, fh, 30),
            "Encoder ne koi supported size report nahi kiya — 720p30 par try kiya gaya."
        )
    }

    /** Downscale-only fit to [longEdge], both dimensions rounded to even because
     *  H.264 chroma subsampling cannot represent an odd size. */
    fun scaleTo(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
        if (longEdge <= 0 || max(width, height) <= longEdge) {
            return even(width) to even(height)
        }
        val scale = longEdge.toDouble() / max(width, height)
        return even((width * scale).roundToInt()) to even((height * scale).roundToInt())
    }

    fun bitRateFor(width: Int, height: Int, fps: Int): Int =
        (width.toDouble() * height * fps * BITS_PER_PIXEL)
            .toInt()
            .coerceIn(MIN_BITRATE, MAX_BITRATE)

    private fun even(v: Int): Int = max(2, v - (v % 2))
}
