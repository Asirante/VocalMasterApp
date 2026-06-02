package com.app.vocalmaster

/**
 * 곡의 표시용 메타데이터.
 * - artist/title: 파일명에서 파싱 ("(가수) - (곡명).vocal")
 * - durationMs/avgKeyHz: meta.json 등에서 보강 (없으면 null)
 */
data class SongMeta(
    val artist: String,
    val title: String,
    val durationMs: Long? = null,
    val avgKeyHz: Float? = null
) {
    val displayName: String get() = "$artist - $title"

    companion object {
        private const val UNKNOWN_ARTIST = "Unknown"

        /** "(가수) - (곡명).vocal" 형식 파싱. 구분자 없으면 전체를 곡명, 가수는 Unknown */
        fun parseFromFileName(fileName: String): SongMeta {
            val base = fileName.removeSuffix(".vocal").trim()
            val idx = base.indexOf(" - ")
            return if (idx >= 0) {
                val artist = base.substring(0, idx).trim().ifEmpty { UNKNOWN_ARTIST }
                val title = base.substring(idx + 3).trim().ifEmpty { base }
                SongMeta(artist = artist, title = title)
            } else {
                SongMeta(artist = UNKNOWN_ARTIST, title = base)
            }
        }
    }
}
