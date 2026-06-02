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

    /** 전처리 웹 도구 주소 (Cloudflare 도메인 등). 비어 있으면 버튼 비활성. */
    var webUrl: String
        get() = prefs.getString(KEY_WEB_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEB_URL, value).apply()

    companion object {
        private const val KEY_FOLDER_PATH = "folder_path"
        private const val KEY_SORT_ORDER = "sort_order"
        private const val KEY_WEB_URL = "web_url"

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
