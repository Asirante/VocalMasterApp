package com.app.vocalmaster

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** 라이브러리 목록에 표시할 곡 한 개 (File 기반) */
data class VocalSong(
    val file: File,
    val songId: String,          // 콘텐츠 해시 — 파일명 변경에도 안정적
    val meta: SongMeta,          // 파일명 파싱 결과 (가수/곡명)
    val durationMs: Long?,       // meta.json에서 (없으면 null → 재생 시 보강)
    val avgKeyHz: Float?,        // meta.json 또는 pitch.json 평균
    val lastModified: Long,
    val stat: SongStat           // 누적 통계 (횟수/최고점/즐겨찾기)
)

// 압축 해제된 파일 경로 묶음
data class UnpackedData(
    val videoFile: File,
    val pitchFile: File
)

class VocalPackageManager(private val context: Context) {

    private val statsStore = SongStatsStore(context)

    companion object {
        private val gson = Gson()

        fun parseJson(pitchFile: File): List<PitchPoint> {
            val json = try {
                pitchFile.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.e("VocalPackageManager", "pitch.json 읽기 실패: ${pitchFile.path}", e)
                return emptyList()
            }
            val type = object : TypeToken<List<Map<String, Double>>>() {}.type
            val raw: List<Map<String, Double>> = gson.fromJson(json, type) ?: return emptyList()
            return raw.mapNotNull { map ->
                val time = map["time"] ?: return@mapNotNull null
                val hz = map["hz"] ?: return@mapNotNull null
                if (time.isNaN() || hz.isNaN()) return@mapNotNull null
                PitchPoint(timeMs = (time * 1000).toLong(), hz = hz.toFloat())
            }
        }

        /** pitch.json + video.mp4 내용으로 안정적 ID 생성 (파일명 무관) */
        fun computeSongId(vocalFile: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            ZipFile(vocalFile).use { zip ->
                listOf("pitch.json", "video.mp4").forEach { name ->
                    zip.getEntry(name)?.let { entry ->
                        zip.getInputStream(entry).use { it.copyToDigest(md) }
                    }
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }.take(16)
        }

        private fun InputStream.copyToDigest(md: MessageDigest) {
            val buf = ByteArray(8192)
            var n = read(buf)
            while (n >= 0) { md.update(buf, 0, n); n = read(buf) }
        }

        /** meta.json이 있으면 (durationMs, avgKeyHz) 반환. 없으면 (null, null) */
        fun readMeta(vocalFile: File): Pair<Long?, Float?> {
            return try {
                ZipFile(vocalFile).use { zip ->
                    val entry = zip.getEntry("meta.json") ?: return Pair(null, null)
                    val json = zip.getInputStream(entry).bufferedReader().readText()
                    val type = object : TypeToken<Map<String, Double>>() {}.type
                    val map: Map<String, Double> = gson.fromJson(json, type) ?: return Pair(null, null)
                    val dur = map["duration_ms"]?.toLong() ?: map["duration"]?.let { (it * 1000).toLong() }
                    val key = map["avg_key_hz"]?.toFloat()
                    Pair(dur, key)
                }
            } catch (e: Exception) {
                Pair(null, null)
            }
        }
    }

    /** 폴더의 .vocal 파일 스캔 → 메타/해시/통계 결합 */
    suspend fun scanVocalFiles(folder: File, sortOrder: SortOrder): List<VocalSong> =
        withContext(Dispatchers.IO) {
            val files = folder.listFiles { f ->
                f.isFile && f.name.endsWith(".vocal", ignoreCase = true)
            }?.toList() ?: emptyList()

            val songs = files.mapNotNull { f ->
                try {
                    val songId = computeSongId(f)
                    val (duration, avgKey) = readMetaJson(f)
                    VocalSong(
                        file = f,
                        songId = songId,
                        meta = SongMeta.parseFromFileName(f.name),
                        durationMs = duration,
                        avgKeyHz = avgKey,
                        lastModified = f.lastModified(),
                        stat = statsStore.get(songId)
                    )
                } catch (e: Exception) {
                    android.util.Log.e("VocalPackageManager", "곡 읽기 실패: ${f.name}", e)
                    null
                }
            }
            sortSongs(songs, sortOrder)
        }

    private fun sortSongs(songs: List<VocalSong>, order: SortOrder): List<VocalSong> = when (order) {
        SortOrder.NAME_ASC -> songs.sortedBy { it.meta.displayName.lowercase() }
        SortOrder.NAME_DESC -> songs.sortedByDescending { it.meta.displayName.lowercase() }
        SortOrder.DATE_DESC -> songs.sortedByDescending { it.lastModified }
        SortOrder.DATE_ASC -> songs.sortedBy { it.lastModified }
    }

    /** meta.json이 있으면 duration/avgKey 읽기 (없으면 null,null) */
    private fun readMetaJson(vocalFile: File): Pair<Long?, Float?> = readMeta(vocalFile)

    /** 재생 전 캐시 폴더에 압축 해제 */
    suspend fun extractAndLoad(vocalFile: File): UnpackedData = withContext(Dispatchers.IO) {
        val extractDir = File(context.cacheDir, "current_play")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        ZipInputStream(vocalFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(extractDir, entry.name)
                require(outFile.canonicalPath.startsWith(extractDir.canonicalPath + File.separator)) {
                    "Zip path traversal detected: ${entry.name}"
                }
                outFile.outputStream().use { fos -> zis.copyTo(fos) }
                entry = zis.nextEntry
            }
        }
        UnpackedData(
            videoFile = File(extractDir, "video.mp4"),
            pitchFile = File(extractDir, "pitch.json")
        )
    }

    fun clearCache() {
        File(context.cacheDir, "current_play").deleteRecursively()
    }

    /** .vocal 파일 삭제. 성공 여부 반환. */
    fun deleteSong(vocalFile: File): Boolean {
        return try {
            vocalFile.exists() && vocalFile.delete()
        } catch (e: Exception) {
            android.util.Log.e("VocalPackageManager", "삭제 실패: ${vocalFile.path}", e)
            false
        }
    }

    /**
     * SAF로 고른 .vocal(또는 zip) 파일을 스캔 폴더에 "artist - title.vocal" 이름으로 복사.
     * @return 복사된 파일 (실패 시 예외)
     */
    suspend fun importSong(
        sourceUri: android.net.Uri,
        folder: File,
        artist: String,
        title: String
    ): File = withContext(Dispatchers.IO) {
        if (!folder.exists()) folder.mkdirs()
        val safeArtist = sanitize(artist).ifEmpty { "Unknown" }
        val safeTitle = sanitize(title).ifEmpty { "untitled" }
        var dest = File(folder, "$safeArtist - $safeTitle.vocal")
        // 동일 이름이 있으면 (1), (2) … 붙여 중복 회피
        var n = 1
        while (dest.exists()) {
            dest = File(folder, "$safeArtist - $safeTitle ($n).vocal")
            n++
        }
        val input = context.contentResolver.openInputStream(sourceUri)
            ?: throw java.io.IOException("파일을 열 수 없습니다: $sourceUri")
        input.use { ins -> dest.outputStream().use { out -> ins.copyTo(out) } }
        dest
    }

    /** 파일명에 못 쓰는 문자 제거 */
    private fun sanitize(s: String): String =
        s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").replace(Regex("\\s+"), " ")
}
