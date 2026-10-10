package com.yayin.player

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Oynatıcı bileşenleri: cihazın kendi çözücüleri + YALNIZCA SES için FFmpeg.
 *
 * FFmpeg kütüphanesindeki deneysel video çözücü bazı cihazlarda çökmeye yol açtığı için
 * hiç yüklenmez. FFmpeg ses çözücüsü listenin SONUNA eklenir: cihaz bir sesi (MP2, AC3,
 * DTS…) kendisi çözebiliyorsa onu kullanır, çözemiyorsa FFmpeg devreye girer.
 */
@OptIn(UnstableApi::class)
class YayinRenderers(context: Context, private val useFfmpeg: Boolean) : DefaultRenderersFactory(context) {

    init {
        // Kütüphanelerin kendiliğinden (yansıma ile) yüklenmesini kapat; FFmpeg sesi aşağıda elle eklenir
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
        setEnableDecoderFallback(true)
        // Çözücüyü eşzamansız kuyrukla çalıştır: daha hızlı ilk kare ve daha akıcı oynatma
        forceEnableMediaCodecAsynchronousQueueing()
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)
        if (useFfmpeg && ffmpegUsable()) {
            try { out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink)) } catch (_: Throwable) { broken = true }
        }
    }

    companion object {
        @Volatile var broken = false
        @Volatile private var checked = false
        @Volatile private var ok = false

        /** FFmpeg'i güvenle sına: kütüphane yoksa ya da uyumsuzsa çökmek yerine kapat. */
        fun ffmpegUsable(): Boolean {
            if (broken) return false
            if (!checked) {
                checked = true
                ok = try {
                    FfmpegLibrary.isAvailable() &&
                        FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_AC3) &&
                        FfmpegAudioRenderer().supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_MPEG_L2).setChannelCount(2).setSampleRate(48000).build()).let { true }
                } catch (_: Throwable) { false }
            }
            return ok
        }
    }
}
