package com.app.vocalmaster

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 타악기 소리를 코드로 합성해 즉시 재생하는 엔진.
 * 음원 파일 없이 노이즈/사인파 + 감쇠 엔벨로프로 만든다.
 * 흔드는 세기(intensity 0~1)에 따라 음량·밝기·길이가 달라진다 → 센서 반응.
 *
 * 지원 악기:
 *  - TAMBOURINE: 밝은 금속성 노이즈 + 살짝의 징글
 *  - MARACA: 부드러운 쉐이커 노이즈
 *  - COWBELL: 두 사인파(부조화) 금속음
 */
class PercussionSynth {

    enum class Instrument { TAMBOURINE, MARACA, COWBELL }

    private val sampleRate = 44100
    private val releaseHandler = Handler(Looper.getMainLooper())

    /** 한 번 타격음 재생 (intensity 0~1). 짧은 1회성 사운드. */
    fun play(instrument: Instrument, intensity: Float) {
        val amp = 0.25f + 0.75f * intensity.coerceIn(0f, 1f) // 세기 → 음량
        val samples = when (instrument) {
            Instrument.TAMBOURINE -> tambourine(intensity, amp)
            Instrument.MARACA -> maraca(intensity, amp)
            Instrument.COWBELL -> cowbell(intensity, amp)
        }
        playPcm(samples)
    }

    // ── 합성기들 ──────────────────────────────────────────────

    /** 탬버린: 밝은 고역 노이즈 + 빠른 감쇠 + 약간의 고주파 사인 '쨍' */
    private fun tambourine(intensity: Float, amp: Float): ShortArray {
        val durSec = 0.18f + 0.12f * intensity
        val n = (sampleRate * durSec).toInt()
        val out = ShortArray(n)
        // 밝기: 세게 흔들수록 고역 강조(하이패스 느낌으로 직전 샘플 차분)
        var prev = 0f
        val bright = 0.5f + 0.5f * intensity
        for (i in 0 until n) {
            val t = i.toFloat() / sampleRate
            val env = exp(-t * (22f + 10f * (1 - intensity))) // 빠른 감쇠
            val noise = Random.nextFloat() * 2f - 1f
            val hp = noise - prev * 0.9f * bright // 간이 하이패스 → 밝은 소리
            prev = noise
            // 5kHz 근처 쨍 성분 살짝
            val shimmer = 0.2f * sin(2.0 * Math.PI * 5200.0 * t).toFloat()
            val s = (hp * 0.8f + shimmer) * env * amp
            out[i] = toPcm(s)
        }
        return out
    }

    /** 마라카스/쉐이커: 중역 노이즈, 부드러운 감쇠 */
    private fun maraca(intensity: Float, amp: Float): ShortArray {
        val durSec = 0.12f + 0.08f * intensity
        val n = (sampleRate * durSec).toInt()
        val out = ShortArray(n)
        var lp = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / sampleRate
            val env = exp(-t * (30f + 12f * (1 - intensity)))
            val noise = Random.nextFloat() * 2f - 1f
            // 간이 로우패스 → 사각거리는 모래 소리 느낌
            lp += (noise - lp) * 0.45f
            val s = lp * env * amp
            out[i] = toPcm(s)
        }
        return out
    }

    /** 카우벨: 두 부조화 사인파 + 금속성 감쇠 */
    private fun cowbell(intensity: Float, amp: Float): ShortArray {
        val durSec = 0.25f + 0.15f * intensity
        val n = (sampleRate * durSec).toInt()
        val out = ShortArray(n)
        val f1 = 540.0   // 기본
        val f2 = 800.0   // 부조화 (정수배 아님 → 금속성)
        for (i in 0 until n) {
            val t = i.toFloat() / sampleRate
            val env = exp(-t * (8f + 6f * (1 - intensity)))
            val tone = (sin(2.0 * Math.PI * f1 * t) + 0.6 * sin(2.0 * Math.PI * f2 * t)).toFloat()
            val s = tone * 0.5f * env * amp
            out[i] = toPcm(s)
        }
        return out
    }

    private fun toPcm(v: Float): Short {
        val clamped = max(-1f, min(1f, v))
        return (clamped * 32767f).toInt().toShort()
    }

    /**
     * PCM 샘플을 일회성 AudioTrack으로 즉시 재생하고 끝나면 해제.
     * 연속 흔들기로 트랙이 한꺼번에 많이 생기면 시스템 한도에 걸려 생성/재생이
     * 실패할 수 있으므로, 그 경우 소리만 건너뛰고 크래시하지 않게 방어한다.
     * 해제는 재생 길이에 맞춘 지연 해제로 보장한다 — MODE_STATIC의 마커 콜백은
     * 일부 기기에서 발화하지 않아, 그것에 의존하면 트랙이 누수되어 32개 한도에
     * 걸린 뒤로는 소리가 전혀 나지 않게 되기 때문.
     */
    private fun playPcm(samples: ShortArray) {
        val track = try {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                max(samples.size * 2, AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )),
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (e: Exception) {
            android.util.Log.w("PercussionSynth", "AudioTrack 생성 실패", e)
            return
        }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return
            }
            track.write(samples, 0, samples.size)
            track.play()
            // 재생 길이 + 여유(80ms) 뒤 확정 해제 (마커 콜백 미발화 기기 대비)
            val durationMs = (samples.size * 1000L / sampleRate) + 80L
            releaseHandler.postDelayed({
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }, durationMs)
        } catch (e: Exception) {
            android.util.Log.w("PercussionSynth", "재생 실패", e)
            try { track.release() } catch (_: Exception) {}
        }
    }
}
