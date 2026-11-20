package com.ibrahim.cevirgen

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import de.sciss.jump3r.lowlevel.LameEncoder
import java.io.File
import javax.sound.sampled.AudioFormat

/** Video ve ses işlemleri: Media3 Transformer (video/M4A) ve jump3r/LAME (MP3). Tamamen çevrimdışı. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MediaTools(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())

    fun info(path: String): Map<String, Any?> {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            fun m(k: Int) = r.extractMetadata(k)
            val rotation = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var w = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = m(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val t = w; w = h; h = t }
            return mapOf(
                "durationMs" to (m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L),
                "width" to w, "height" to h,
                "hasVideo" to (m(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"),
                "hasAudio" to (m(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"),
            )
        } finally {
            r.release()
        }
    }

    /**
     * Videoyu yeniden kodlar. targetHeight 0 ise çözünürlük korunur.
     * startMs/endMs < 0 ise kırpma yapılmaz. removeVideo = sadece ses (M4A).
     */
    fun transform(
        input: String, out: File, targetHeight: Int, startMs: Long, endMs: Long, removeVideo: Boolean,
        progress: (Double) -> Unit, done: (File?, String?) -> Unit,
        onCancelReady: ((() -> Unit) -> Unit)? = null,
    ) {
        main.post {
            try {
                val clip = MediaItem.ClippingConfiguration.Builder().apply {
                    if (startMs > 0) setStartPositionMs(startMs)
                    if (endMs > 0) setEndPositionMs(endMs)
                }.build()
                val item = MediaItem.Builder().setUri(android.net.Uri.fromFile(File(input))).setClippingConfiguration(clip).build()
                val videoEffects = if (!removeVideo && targetHeight > 0) listOf(Presentation.createForHeight(targetHeight)) else emptyList()
                val edited = EditedMediaItem.Builder(item)
                    .setRemoveVideo(removeVideo)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()

                val builder = Transformer.Builder(ctx).setAudioMimeType(MimeTypes.AUDIO_AAC)
                if (!removeVideo) {
                    builder.setVideoMimeType(MimeTypes.VIDEO_H264)
                    val bitrate = when {
                        targetHeight in 1..480 -> 1_200_000
                        targetHeight in 481..720 -> 2_500_000
                        targetHeight in 721..1080 -> 5_000_000
                        else -> 0
                    }
                    if (bitrate > 0) {
                        builder.setEncoderFactory(
                            DefaultEncoderFactory.Builder(ctx)
                                // CBR: telefon kodlayıcıları VBR'de istenen hızı ~2 kat aşabiliyor; sabit hız dosya boyutunu öngörülebilir yapar
                                .setRequestedVideoEncoderSettings(
                                    VideoEncoderSettings.Builder().setBitrate(bitrate)
                                        .setBitrateMode(android.media.MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR).build()
                                )
                                .build()
                        )
                    }
                }
                val holder = ProgressHolder()
                var transformer: Transformer? = null
                var finished = false
                val poll = object : Runnable {
                    override fun run() {
                        if (finished) return
                        val t = transformer ?: return
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100.0)
                        main.postDelayed(this, 400)
                    }
                }
                transformer = builder.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished = true
                        progress(1.0)
                        done(out, null)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        finished = true
                        out.delete()
                        done(null, "Dönüştürülemedi: ${exportException.errorCodeName}")
                    }
                }).build()
                transformer.start(edited, out.absolutePath)
                main.post(poll)
                // İptal: Transformer durdurulur, yarım dosya silinir (cancel() dinleyiciyi çağırmaz)
                onCancelReady?.invoke {
                    main.post {
                        if (!finished) {
                            finished = true
                            transformer?.cancel()
                            out.delete()
                            done(null, Jobs.CANCELLED)
                        }
                    }
                }
            } catch (e: Exception) {
                done(null, e.message ?: "Dönüştürülemedi")
            }
        }
    }

    /** Ses parçasını PCM'e çözüp LAME ile MP3'e kodlar. Arka plan iş parçacığında çağrılmalı. */
    fun toMp3(input: String, out: File, bitrateKbps: Int, progress: (Double) -> Unit): File {
        val ex = MediaExtractor()
        ex.setDataSource(input)
        var track = -1
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; break }
        }
        require(track >= 0) { "Bu dosyada ses yok." }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoder: LameEncoder? = null
        var mp3Buf = ByteArray(0)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        fun newEncoder(): LameEncoder {
            val outCh = channels.coerceAtMost(2)
            val src = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate.toFloat(), 16, outCh, outCh * 2, sampleRate.toFloat(), false)
            val e = LameEncoder(src, bitrateKbps, if (outCh == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_JOINT_STEREO, LameEncoder.QUALITY_MIDDLE, false)
            mp3Buf = ByteArray(maxOf(e.getMP3BufferSize(), 1 shl 16))
            return e
        }

        out.outputStream().buffered(1 shl 16).use { os ->
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                                if (durationUs > 0) progress((ex.sampleTime.toDouble() / durationUs).coerceIn(0.0, 0.99))
                                ex.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val of = codec.outputFormat
                            sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val enc = encoder ?: newEncoder().also { encoder = it }
                                val buf = codec.getOutputBuffer(outIdx)!!
                                buf.position(info.offset); buf.limit(info.offset + info.size)
                                var pcm = ByteArray(info.size).also { buf.get(it) }
                                if (channels > 2) pcm = downmixToStereo(pcm, channels)
                                var off = 0
                                val chunk = enc.getPCMBufferSize().coerceAtLeast(1152 * 4)
                                while (off < pcm.size) {
                                    val len = minOf(chunk, pcm.size - off)
                                    val n = enc.encodeBuffer(pcm, off, len, mp3Buf)
                                    if (n > 0) os.write(mp3Buf, 0, n)
                                    off += len
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                encoder?.let { e ->
                    val n = e.encodeFinish(mp3Buf)
                    if (n > 0) os.write(mp3Buf, 0, n)
                    e.close()
                }
            } finally {
                codec.stop(); codec.release(); ex.release()
            }
        }
        require(encoder != null) { "Ses çözülemedi." }
        progress(1.0)
        return out
    }

    /** 16 bit çok kanallı PCM'den ilk iki kanalı alır. */
    private fun downmixToStereo(pcm: ByteArray, channels: Int): ByteArray {
        val frames = pcm.size / (2 * channels)
        val out = ByteArray(frames * 4)
        for (f in 0 until frames) {
            System.arraycopy(pcm, f * 2 * channels, out, f * 4, 4)
        }
        return out
    }
}
