package com.app.vocalmaster

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/** MANAGE_EXTERNAL_STORAGE 권한 확인 및 요청 도우미 */
object StoragePermissionHelper {

    /** 모든 파일 접근 권한이 있는지 */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // API 29 이하는 requestLegacyExternalStorage 로 처리
        }
    }

    /** 설정의 "모든 파일 접근" 화면으로 이동하는 인텐트 */
    fun buildManageStorageIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }
}
