package com.app.vocalmaster

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File

/**
 * 앱 설정 저장소.
 * - folderPath: 스캔 폴더 절대경로 (기본값: 외부저장소/VocalMaster)
 * - sortOrder: 곡 목록 정렬 순서
 */
class SettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("vocal_master_prefs", Context.MODE_PRIVATE)

    /** 스캔 폴더 경로 (미설정 시 기본 경로 반환) */
    var folderPath: String
        get() = prefs.getString(KEY_FOLDER_PATH, null) ?: defaultFolder().absolutePath
        set(value) = prefs.edit().putString(KEY_FOLDER_PATH, value).apply()

    var sortOrder: SortOrder
        get() = SortOrder.fromKey(prefs.getString(KEY_SORT_ORDER, SortOrder.NAME_ASC.key))
        set(value) = prefs.edit().putString(KEY_SORT_ORDER, value.key).apply()

    /** 연습 모드에서 폰을 엎어두면 일시정지 (가속도계). 기본 켜짐. */
    var flipToPause: Boolean
        get() = prefs.getBoolean(KEY_FLIP_TO_PAUSE, true)
        set(value) = prefs.edit().putBoolean(KEY_FLIP_TO_PAUSE, value).apply()

    /** 흔들어 타악기 민감도 레벨 1(둔감)~5(민감). 기본 3. */
    var shakeLevel: Int
        get() = prefs.getInt(KEY_SHAKE_LEVEL, 3).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_SHAKE_LEVEL, value.coerceIn(1, 5)).apply()

    /** 무대 조명(조도) 민감도 레벨 1(많이 어두워야)~5(조금만 어두워도). 기본 3. */
    var lightLevel: Int
        get() = prefs.getInt(KEY_LIGHT_LEVEL, 3).coerceIn(1, 5)
        set(value) = prefs.edit().putInt(KEY_LIGHT_LEVEL, value.coerceIn(1, 5)).apply()

    /** 흔들기 트리거 임계값(m/s²). 레벨이 높을수록 작은 흔들림에도 반응(낮은 임계값). */
    val shakeThreshold: Float
        get() = when (shakeLevel) {
            1 -> 8f; 2 -> 6f; 3 -> 4f; 4 -> 2.5f; 5 -> 1.5f; else -> 4f
        }

    /** 무대 효과가 켜지는 조도 상한(lux). 레벨이 높을수록 밝은 환경에서도 켜짐. */
    val darkLuxCeil: Float
        get() = when (lightLevel) {
            1 -> 100f; 2 -> 150f; 3 -> 200f; 4 -> 280f; 5 -> 400f; else -> 200f
        }

    companion object {
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_FLIP_TO_PAUSE = "flip_to_pause"
        private const val KEY_SHAKE_LEVEL = "shake_level"
        private const val KEY_LIGHT_LEVEL = "light_level"

        /** 기본 추천 경로: 내부 공용 저장소/VocalMaster */
        fun defaultFolder(): File =
            File(Environment.getExternalStorageDirectory(), "VocalMaster")
    }
}

enum class SortOrder(val key: String, val label: String) {
    NAME_ASC("name_asc", "이름 (오름차순)"),
    NAME_DESC("name_desc", "이름 (내림차순)"),
    DATE_DESC("date_desc", "최신순"),
    DATE_ASC("date_asc", "오래된순");

    companion object {
        fun fromKey(key: String?): SortOrder =
            entries.firstOrNull { it.key == key } ?: NAME_ASC
    }
}
