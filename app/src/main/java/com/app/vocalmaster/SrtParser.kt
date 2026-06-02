package com.app.vocalmaster

import java.io.File

data class Subtitle(val startMs: Long, val endMs: Long, val text: String)

object SrtParser {
    /**
     * SRT 형식: "HH:MM:SS,mmm --> HH:MM:SS,mmm"
     * 예: "00:00:05,200 --> 00:00:08,400"
     */
    fun parse(srtFile: File): List<Subtitle> {
        val subtitles = mutableListOf<Subtitle>()
        val content = try {
            srtFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e("SrtParser", "SRT 파일 읽기 실패: ${srtFile.path}", e)
            return emptyList()
        }
        // 빈 줄 기준으로 블록 분리
        val blocks = content.trim().split(Regex("\\n\\n|\\r\\n\\r\\n"))

        for (block in blocks) {
            val lines = block.trim().lines()
            if (lines.size < 3) continue
            val timeLine = lines[1]
            val parts = timeLine.split(" --> ")
            if (parts.size != 2) continue
            subtitles.add(
                Subtitle(
                    startMs = parseSrtTime(parts[0].trim()),
                    endMs = parseSrtTime(parts[1].trim()),
                    text = lines.drop(2).joinToString("\n")
                )
            )
        }
        return subtitles
    }

    // "HH:MM:SS,mmm" → milliseconds
    private fun parseSrtTime(s: String): Long {
        val parts = s.split(",")
        if (parts.size != 2) return 0L
        val (hms, msStr) = parts
        val hmsParts = hms.split(":").mapNotNull { it.trim().toLongOrNull() }
        if (hmsParts.size != 3) return 0L
        val (h, m, sec) = hmsParts
        val ms = msStr.trim().toLongOrNull() ?: 0L
        return h * 3600000L + m * 60000L + sec * 1000L + ms
    }

    // 현재 재생 위치에 맞는 자막 반환
    // lastOrNull: Whisper 출력에서 범위가 겹치는 블록이 있을 때 최신(더 가까운) 자막 우선
    fun getCurrentSubtitle(subtitles: List<Subtitle>, currentMs: Long): Subtitle? =
        subtitles.lastOrNull { currentMs in it.startMs..it.endMs }
}
