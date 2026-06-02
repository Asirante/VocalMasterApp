package com.app.vocalmaster

import android.os.Handler
import android.os.Looper
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.io.android.AudioDispatcherFactory
import be.tarsos.dsp.pitch.PitchDetectionHandler
import be.tarsos.dsp.pitch.PitchProcessor
import be.tarsos.dsp.pitch.PitchProcessor.PitchEstimationAlgorithm
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log2

/** 정답 피치 한 프레임 (time, hz). pitch.json 파싱 결과 단위로 사용. */
data class PitchPoint(val timeMs: Long, val hz: Float)

class ScoringEngine {

    private var dispatcher: AudioDispatcher? = null
    private var totalScore = 0.0
    private var scoredCount = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // 판정별 누적 카운트 (결과 화면용)
    @Volatile private var perfectCount = 0
    @Volatile private var greatCount = 0
    @Volatile private var goodCount = 0
    @Volatile private var missCount = 0

    /** 최종 결과 스냅샷 */
    data class Result(
        val avgScore: Int,
        val perfect: Int,
        val great: Int,
        val good: Int,
        val miss: Int
    )

    fun getResult(): Result {
        val avg = if (scoredCount == 0) 0 else (totalScore / scoredCount).toInt()
        return Result(avg, perfectCount, greatCount, goodCount, missCount)
    }

    // ✅ Thread-safety: player.currentPosition은 메인 스레드 전용.
    //    TarsosDSP 콜백은 오디오 스레드에서 실행되므로 AtomicLong으로 위치를 공유.
    private val currentPositionMs = AtomicLong(0L)

    // 메인 스레드(ExoPlayer Listener 또는 100ms 폴링)에서 주기적으로 갱신
    fun updatePosition(ms: Long) {
        currentPositionMs.set(ms)
    }

    @Volatile
    private var keyMultiplier = 1.0f // 오디오 스레드에서 읽힘 — 가시성 보장

    fun setKeyMultiplier(m: Float) {
        keyMultiplier = m
    }

    fun start(
        targets: List<PitchPoint>,
        onScoreUpdate: (score: Int, cents: Float, detectedHz: Float, targetHz: Float, judgment: String) -> Unit
    ) {
        stop() // 중복 호출 방어 — 기존 dispatcher 먼저 정리

        // 샘플레이트 22050Hz, 버퍼 1024 샘플 (~46ms 단위 분석)
        val d = AudioDispatcherFactory.fromDefaultMicrophone(22050, 1024, 0)
        if (d == null) {
            android.util.Log.e("ScoringEngine", "마이크 초기화 실패")
            return
        }
        dispatcher = d

        val handler = PitchDetectionHandler { result, event ->
            val detectedHz = result.pitch
            if (detectedHz <= 0) return@PitchDetectionHandler // 무음 구간 스킵

            // 음량 게이트: 일정 크기 이상 소리(노래)일 때만 채점.
            // event.getdBSPL()는 대략적인 음압(dB). 너무 조용하면(무음/잡음) 채점 안 함.
            val db = event.getdBSPL()
            if (db < MIN_DB_TO_SCORE) return@PitchDetectionHandler

            // ✅ AtomicLong으로 읽기 — 오디오 스레드에서 안전
            val currentMs = currentPositionMs.get()
            val rawTargetHz = findClosestTarget(currentMs, targets) ?: return@PitchDetectionHandler
            val targetHz = rawTargetHz * keyMultiplier // Key 조절 보정 적용

            // Cent 오차 계산: 양수면 음정 높음, 음수면 낮음
            val rawCents = (1200.0 * log2(detectedHz.toDouble() / targetHz)).toFloat()
            // 옥타브 무시: 1200 cents(한 옥타브) 단위로 접어, -600~+600 범위로 환산.
            // → 정확히 한 옥타브 높거나 낮게 불러도(또는 추출 옥타브 오류) 정답 처리.
            var cents = rawCents % 1200f
            if (cents > 600f) cents -= 1200f
            if (cents < -600f) cents += 1200f

            // 점수 환산: 오차 범위별 점수 + 판정 카운트 (관용도 완화)
            val frameScore = when {
                abs(cents) <= 40f -> { perfectCount++; 100 }  // Perfect
                abs(cents) <= 70f -> { greatCount++; 80 }     // Great
                abs(cents) <= 120f -> { goodCount++; 50 }     // Good
                else -> { missCount++; 10 }                   // Miss
            }
            val judgment = when {
                abs(cents) <= 40f -> "PERFECT"
                abs(cents) <= 70f -> "GREAT"
                abs(cents) <= 120f -> "GOOD"
                else -> "MISS"
            }
            totalScore += frameScore
            scoredCount++

            // UI 업데이트는 Main 스레드에서
            val avgScore = (totalScore / scoredCount).toInt()
            mainHandler.post { onScoreUpdate(avgScore, cents, detectedHz, targetHz, judgment) }
        }

        d.addAudioProcessor(PitchProcessor(PitchEstimationAlgorithm.YIN, 22050f, 1024, handler))
        Thread(d).start()
    }

    // 현재 재생 시간과 가장 가까운 정답 피치 탐색 — O(log n) 이진 탐색
    // targets는 시간순 정렬이 보장된 리스트 (Librosa 출력 순서)
    private fun findClosestTarget(currentMs: Long, targets: List<PitchPoint>): Float? {
        if (targets.isEmpty()) return null
        var lo = 0
        var hi = targets.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (targets[mid].timeMs < currentMs) lo = mid + 1 else hi = mid
        }
        // lo는 currentMs 이상인 첫 인덱스 — 앞뒤 두 후보 중 더 가까운 것 선택
        val candidates = listOfNotNull(
            targets.getOrNull(lo - 1),
            targets.getOrNull(lo)
        ).filter { abs(it.timeMs - currentMs) <= 200L }
        return candidates
            .minByOrNull { abs(it.timeMs - currentMs) }
            ?.hz
            ?.takeIf { it > 0f } // 0Hz = 무음 구간 제외
    }

    // 반드시 호출 — 메모리 릭 방지
    // keyMultiplier는 여기서 초기화하지 않음 — KeyController.resetKey() 콜백으로 일관되게 관리
    fun stop() {
        dispatcher?.stop()
        dispatcher = null
        totalScore = 0.0
        scoredCount = 0
        perfectCount = 0
        greatCount = 0
        goodCount = 0
        missCount = 0
    }

    companion object {
        // 이 음압(dB SPL 근사값) 미만이면 채점하지 않음.
        // TarsosDSP getdBSPL()은 음수값(예: -120 ~ 0)을 반환. 너무 조용한 입력 차단용.
        // 환경(마이크 감도)에 따라 -40 ~ -30 사이로 조정 가능.
        private const val MIN_DB_TO_SCORE = -40.0
    }
}
