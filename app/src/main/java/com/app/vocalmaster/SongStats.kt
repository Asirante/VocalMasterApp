package com.app.vocalmaster

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** 곡별 누적 통계 (해시 ID로 식별) */
data class SongStat(
    val songId: String,
    var playCount: Int = 0,
    var bestScore: Int = 0,
    var favorite: Boolean = false
)

/**
 * 곡별 통계를 SharedPreferences(JSON)로 저장.
 * songId = .vocal 핵심 콘텐츠 해시 → 파일명을 바꿔도 통계 유지.
 */
class SongStatsStore(context: Context) {

    private val prefs = context.getSharedPreferences("vocal_master_stats", Context.MODE_PRIVATE)
    private val gson = Gson()

    private fun load(): MutableMap<String, SongStat> {
        val json = prefs.getString(KEY, null) ?: return mutableMapOf()
        val type = object : TypeToken<MutableMap<String, SongStat>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }

    private fun save(map: Map<String, SongStat>) {
        prefs.edit().putString(KEY, gson.toJson(map)).apply()
    }

    fun get(songId: String): SongStat = load()[songId] ?: SongStat(songId)

    fun recordPlay(songId: String, score: Int) {
        val map = load()
        val stat = map[songId] ?: SongStat(songId)
        stat.playCount += 1
        if (score > stat.bestScore) stat.bestScore = score
        map[songId] = stat
        save(map)
    }

    fun toggleFavorite(songId: String): Boolean {
        val map = load()
        val stat = map[songId] ?: SongStat(songId)
        stat.favorite = !stat.favorite
        map[songId] = stat
        save(map)
        return stat.favorite
    }

    companion object {
        private const val KEY = "stats_map"
    }
}
