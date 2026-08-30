package com.depthmaker.app.toon

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class StylizeCancelled : Exception("cancelled")

data class StylizeResult(val file: File, val plan: ExportPlan, val framesWritten: Int)

/**
 * Surface-to-surface transcode: decoder → SurfaceTexture → GL filter → encoder
 * input Surface → muxer. No frame ever crosses into Java heap or CPU memory,
 * which is what makes 720p60 comfortably faster than real time on mid-range
 * hardware.
 *
 * The audio track, if any, is remuxed byte-for-byte — it is never decoded.
 */
class VideoStylizer(
    private val context: Context,
    private val input: Uri,
    private val output: File,
    private val filter: ToonFilter,
    private val strength: Float,
    private val resolution: ResolutionPreset,
    private val fpsPreset: FpsPreset,
    /** MediaExtractor often omits or mis-reports the frame rate; the picker
     *  already measured it from the container, so pass that in as a fallback. */
    private val sourceFpsHint: Float = 0f,
    private val limits: EncoderLimits = EncoderCapabilities.limits
) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    fun stylize(onProgress: (Float) -> Unit): StylizeResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, input, null)
        try {
            val videoTrack = extractor.findTrack("video/")
            require(videoTrack >= 0) { "no video track" }
            val audioTrack = extractor.findTrack("audio/")
            val videoFormat = extractor.getTrackFormat(videoTrack)

            val rawWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = videoFormat.optInt(KEY_ROTATION, 0)
            val portraitSwap = rotation == 90 || rotation == 270
            val srcWidth = if (portraitSwap) rawHeight else rawWidth
            val srcHeight = if (portraitSwap) rawWidth else rawHeight
            val srcFps = videoFormat.optFrameRate().takeIf { it > 1f } ?: sourceFpsHint
            val durationUs = videoFormat.optLong(MediaFormat.KEY_DURATION, 0L)

            val plan = ExportPlanner.plan(srcWidth, srcHeight, srcFps, resolution, fpsPreset, limits)
            Log.i(TAG, "source ${srcWidth}x$srcHeight @${srcFps}fps rot=$rotation → ${plan.summary()}")

            val framesWritten = transcode(
                extractor, videoTrack, audioTrack, videoFormat, rotation, plan, durationUs, onProgress
            )
            return StylizeResult(output, plan, framesWritten)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun transcode(
        extractor: MediaExtractor,
        videoTrack: Int,
        audioTrack: Int,
        videoFormat: MediaFormat,
        rotation: Int,
        plan: ExportPlan,
        durationUs: Long,
        onProgress: (Float) -> Unit
    ): Int {
        val encoderFormat = MediaFormat.createVideoFormat(EncoderCapabilities.MIME, plan.width, plan.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, plan.fps)
            // One keyframe per second: seeking and trimming in other apps stays
            // usable without paying much in bitrate on flat cartoon frames.
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(EncoderCapabilities.MIME)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderSurface = encoder.createInputSurface()

        val egl = EglCore(encoderSurface)
        egl.makeCurrent()
        val renderer = FrameRenderer(plan.width, plan.height, filter, strength, rotation)
        renderer.setup()

        // Strip the container rotation before the decoder sees it: some decoders
        // honour it and rotate the buffer themselves, and combined with the
        // renderer's own rotation the frame would come out rotated twice.
        val decoderFormat = MediaFormat().apply { copyFrom(videoFormat) }
        decoderFormat.setInteger(KEY_ROTATION, 0)
        val decoder = MediaCodec.createDecoderByType(videoFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(decoderFormat, renderer.inputSurface, null, 0)

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerVideoTrack = -1
        var muxerAudioTrack = -1
        var muxerStarted = false
        var framesWritten = 0

        val gate = FrameRateGate(plan.fps)
        val decoderInfo = MediaCodec.BufferInfo()
        val encoderInfo = MediaCodec.BufferInfo()

        try {
            encoder.start()
            decoder.start()
            extractor.selectTrack(videoTrack)

            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (cancelled.get()) throw StylizeCancelled()

                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    when (val index = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_US)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        else -> if (index >= 0) {
                            val eos = decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            val keep = decoderInfo.size > 0 && gate.accept(decoderInfo.presentationTimeUs)
                            decoder.releaseOutputBuffer(index, keep)
                            if (keep) {
                                renderer.awaitFrame()
                                renderer.drawFrame()
                                egl.setPresentationTime(decoderInfo.presentationTimeUs * 1000L)
                                egl.swapBuffers()
                                framesWritten++
                                if (durationUs > 0) {
                                    onProgress(
                                        (decoderInfo.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                    )
                                }
                            }
                            if (eos) {
                                decoderDone = true
                                encoder.signalEndOfInputStream()
                            }
                        }
                    }
                }

                when (val index = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "encoder format changed twice" }
                        muxerVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioTrack >= 0) {
                            muxerAudioTrack = muxer.addTrack(extractor.getTrackFormat(audioTrack))
                        }
                        muxer.start()
                        muxerStarted = true
                    }
                    else -> if (index >= 0) {
                        val buffer = encoder.getOutputBuffer(index)!!
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            // Already carried in the muxer's track format.
                            encoderInfo.size = 0
                        }
                        if (encoderInfo.size > 0 && muxerStarted) {
                            buffer.position(encoderInfo.offset)
                            buffer.limit(encoderInfo.offset + encoderInfo.size)
                            muxer.writeSampleData(muxerVideoTrack, buffer, encoderInfo)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }

            check(framesWritten > 0) { "no frames were encoded" }
            if (muxerAudioTrack >= 0) {
                copyAudio(extractor, videoTrack, audioTrack, muxer, muxerAudioTrack)
            }
            onProgress(1f)
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            renderer.release()
            egl.release()
            runCatching { encoderSurface.release() }
        }
        return framesWritten
    }

    /**
     * Remux the audio unchanged. Written after the video rather than
     * interleaved: for the clip lengths this app accepts the resulting file is
     * still well within what every player buffers, and it keeps the decode loop
     * above single-purpose.
     */
    private fun copyAudio(
        extractor: MediaExtractor,
        videoTrack: Int,
        audioTrack: Int,
        muxer: MediaMuxer,
        muxerAudioTrack: Int
    ) {
        extractor.unselectTrack(videoTrack)
        extractor.selectTrack(audioTrack)
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        val format = extractor.getTrackFormat(audioTrack)
        val capacity = format.optInt(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024).coerceAtLeast(64 * 1024)
        val buffer = ByteBuffer.allocate(capacity)
        val info = MediaCodec.BufferInfo()

        while (true) {
            if (cancelled.get()) throw StylizeCancelled()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags and MediaCodec.BUFFER_FLAG_KEY_FRAME
            muxer.writeSampleData(muxerAudioTrack, buffer, info)
            extractor.advance()
        }
    }

    private companion object {
        const val TAG = "VideoStylizer"
        const val TIMEOUT_US = 10_000L
        const val KEY_ROTATION = "rotation-degrees"
    }
}

/**
 * Drops frames when the source runs faster than the export rate. Purely
 * subtractive — it never duplicates a frame to reach a higher rate, because a
 * duplicated frame costs bitrate and adds no motion.
 */
class FrameRateGate(fps: Int) {
    private val intervalUs = if (fps > 0) 1_000_000L / fps else 0L
    private var nextUs = Long.MIN_VALUE

    fun accept(presentationTimeUs: Long): Boolean {
        if (intervalUs == 0L) return true
        if (nextUs == Long.MIN_VALUE) {
            nextUs = presentationTimeUs + intervalUs
            return true
        }
        // Half-interval tolerance: without it a 30.00 fps source against a
        // 30 fps target drops every other frame to jitter in the timestamps.
        if (presentationTimeUs + intervalUs / 2 < nextUs) return false
        nextUs = presentationTimeUs + intervalUs
        return true
    }
}

private fun MediaExtractor.findTrack(prefix: String): Int {
    for (i in 0 until trackCount) {
        val mime = getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
        if (mime.startsWith(prefix)) return i
    }
    return -1
}

/** KEY_FRAME_RATE is an Int in the encoder's world but arrives as a Float from
 *  some extractors, and getInteger throws rather than converting. */
private fun MediaFormat.optFrameRate(): Float {
    if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return 0f
    runCatching { return getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
    runCatching { return getFloat(MediaFormat.KEY_FRAME_RATE) }
    return 0f
}

private fun MediaFormat.optInt(key: String, fallback: Int): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(fallback) else fallback

private fun MediaFormat.optLong(key: String, fallback: Long): Long =
    if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(fallback) else fallback
