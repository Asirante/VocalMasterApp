package com.app.vocalmaster

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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

    // 재생은 스트림 write가 블로킹하므로 메인(센서) 스레드가 아닌 워커에서 처리.
    // 최대 4개까지 동시 재생하고, 그 이상 몰리면(마구 흔들 때) 큐에 쌓아 지연되게 두지 말고
    // 그냥 버린다(DiscardPolicy) — 타악기는 즉시성이 중요하므로 밀린 소리는 의미 없음.
    private val playbackPool = ThreadPoolExecutor(
        0, 4, 1L, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadPoolExecutor.DiscardPolicy()
    )

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
     * PCM 샘플을 일회성 AudioTrack(MODE_STREAM)으로 재생.
     * MODE_STREAM은 play() 후 write()로 데이터를 밀어 넣는 방식이라 짧은 생성음에서
     * MODE_STATIC보다 기기 호환성이 좋다. write가 블로킹하므로 워커 스레드에서 실행하고,
     * 재생이 끝나면 stop/release로 확실히 정리한다. 트랙 생성/재생 실패 시에는
     * 소리만 건너뛰고 크래시하지 않는다.
     */
    private fun playPcm(samples: ShortArray) {
        // 워커가 모두 바쁘면(한꺼번에 너무 많이 흔든 경우) 이번 타격음은 조용히 건너뜀
        try {
            playbackPool.execute { renderOnce(samples) }
        } catch (_: Exception) { /* 풀 포화/종료 — 무시 */ }
    }

    private fun renderOnce(samples: ShortArray) {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(samples.size * 2, if (minBuf > 0) minBuf else samples.size * 2)
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
                bufBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (e: Exception) {
            android.util.Log.w("PercussionSynth", "AudioTrack 생성 실패", e)
            return
        }
        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                android.util.Log.w("PercussionSynth", "AudioTrack 미초기화 (state=${track.state})")
                track.release()
                return
            }
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val written = track.write(samples, offset, samples.size - offset)
                if (written <= 0) {
                    android.util.Log.w("PercussionSynth", "write 실패 코드=$written")
                    break
                }
                offset += written
            }
            // 버퍼에 남은 샘플이 모두 재생되도록 잠깐 대기 후 정리
            val tailMs = (samples.size * 1000L / sampleRate) + 60L
            try { Thread.sleep(tailMs) } catch (_: InterruptedException) {}
            try { track.stop() } catch (_: Exception) {}
        } catch (e: Exception) {
            android.util.Log.w("PercussionSynth", "재생 실패", e)
        } finally {
            try { track.release() } catch (_: Exception) {}
        }
    }

    /** 화면 종료 시 워커 풀 정리 */
    fun release() {
        runCatching { playbackPool.shutdown() }
    }
}
