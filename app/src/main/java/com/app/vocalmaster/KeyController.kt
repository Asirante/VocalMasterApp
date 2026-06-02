package com.app.vocalmaster

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.pow

/**
 * ExoPlayer의 pitch 파라미터를 반음 단위로 조절하는 컨트롤러.
 *
 * @param onKeyChanged Key 변경 시 pitchMultiplier를 ScoringEngine에 전달하는 콜백
 */
class KeyController(
    private val player: ExoPlayer,
    private val onKeyChanged: (pitchMultiplier: Float) -> Unit = {}
) {
    private var currentKey = 0 // 0 = 원키, +1 = 반음 올림, -3 = 3반음 내림
    private val semitoneRatio = 2.0.pow(1.0 / 12.0) // ≈ 1.05946

    // 현재 키 기준 반음 단위 변경 (범위 제한: -6 ~ +6 반음)
    fun shiftKey(semitones: Int) {
        currentKey = (currentKey + semitones).coerceIn(-6, 6)
        applyPitch()
    }

    fun resetKey() {
        currentKey = 0
        applyPitch()
    }

    fun getCurrentKey(): Int = currentKey

    fun getCurrentMultiplier(): Float = semitoneRatio.pow(currentKey).toFloat()

    private fun applyPitch() {
        val pitchMultiplier = getCurrentMultiplier()
        player.playbackParameters = PlaybackParameters(
            /* speed = */ 1.0f,
            /* pitch = */ pitchMultiplier
        )
        onKeyChanged(pitchMultiplier) // ScoringEngine에 보정값 전달
    }
}
