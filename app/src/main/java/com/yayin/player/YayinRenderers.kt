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
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * Oynatıcı bileşenleri: cihazın kendi çözücüleri + YALNIZCA SES için FFmpeg.
 *
 * FFmpeg kütüphanesindeki deneysel video çözücü bazı cihazlarda çökmeye yol açtığı için
 * hiç yüklenmez. FFmpeg ses çözücüsü listenin SONUNA eklenir: cihaz bir sesi (MP2, AC3,
 * DTS…) kendisi çözebiliyorsa onu kullanır, çözemiyorsa FFmpeg devreye girer.
 */
@OptIn(UnstableApi::class)
class YayinRenderers(context: Context, private val useFfmpeg: Boolean, private val applyOffset: Boolean = false) : DefaultRenderersFactory(context) {

    /**
     * Ses gecikmesi: bazı kanallarda ses görüntüden önce geliyor (sağlayıcının yayını).
     * Ses saatini kaydırarak görüntüyü sesle hizalar. Pozitif değer = ses o kadar geç duyulur gibi.
     */
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val sink = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        if (!applyOffset) return sink
        return object : ForwardingAudioSink(sink) {
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val p = super.getCurrentPositionUs(sourceEnded)
                if (p == AudioSink.CURRENT_POSITION_NOT_SET || !offsetActive) return p
                return p + audioOffsetUs
            }
        }
    }

    /** Oynatıcıdaki FFmpeg ses çözücüsünün sırası (-1: yok). Tek kanalda kapatmak için. */
    var ffmpegIndex = -1
        private set

    init {
        // Kütüphanelerin kendiliğinden (yansıma ile) yüklenmesini kapat; FFmpeg sesi aşağıda elle eklenir
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_OFF)
        setEnableDecoderFallback(true)
        // Not: eşzamansız çözücü kuyruğu (forceEnableMediaCodecAsynchronousQueueing) Fire OS'ta
        // bazı kanalların açılmamasına / geç açılmasına yol açtığı için kullanılmıyor.
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
            try { ffmpegIndex = out.size; out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink)) } catch (_: Throwable) { broken = true; ffmpegIndex = -1 }
        }
    }

    companion object {
        /** Canlı yayın için ses gecikmesi (mikrosaniye) ve yalnızca canlı yayında uygulanması */
        @Volatile var audioOffsetUs = 0L
        @Volatile var offsetActive = false
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
