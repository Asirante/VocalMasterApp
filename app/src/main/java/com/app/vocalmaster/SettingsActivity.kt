package com.app.vocalmaster

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 설정 화면.
 * - 저장소 권한(모든 파일 접근) 허용 유도
 * - 스캔 폴더 경로 (기본: 외부저장소/VocalMaster, 직접 입력 가능)
 * - 곡 목록 정렬 순서
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var tvPermStatus: TextView
    private lateinit var btnGrant: Button
    private lateinit var etFolder: android.widget.EditText
    private lateinit var btnSaveFolder: Button
    private lateinit var btnResetFolder: Button
    private lateinit var spinnerSort: Spinner

    private val sortOptions = SortOrder.entries.toList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        settings = SettingsManager(this)
        tvPermStatus = findViewById(R.id.tvPermStatus)
        btnGrant = findViewById(R.id.btnGrantPermission)
        etFolder = findViewById(R.id.etFolderPath)
        btnSaveFolder = findViewById(R.id.btnSaveFolder)
        btnResetFolder = findViewById(R.id.btnResetFolder)
        spinnerSort = findViewById(R.id.spinnerSort)

        etFolder.setText(settings.folderPath)

        btnGrant.setOnClickListener {
            startActivity(StoragePermissionHelper.buildManageStorageIntent(this))
        }
        btnSaveFolder.setOnClickListener {
            val path = etFolder.text.toString().trim()
            if (path.isNotEmpty()) {
                settings.folderPath = path
                toast("폴더 저장됨: $path")
            }
        }
        btnResetFolder.setOnClickListener {
            val def = SettingsManager.defaultFolder().absolutePath
            settings.folderPath = def
            etFolder.setText(def)
            toast("기본 폴더로 초기화")
        }

        val labels = sortOptions.map { it.label }
        spinnerSort.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        spinnerSort.setSelection(sortOptions.indexOf(settings.sortOrder))
        spinnerSort.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) { settings.sortOrder = sortOptions[position] }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // 전처리 웹 도구 (고정 주소)
        findViewById<Button>(R.id.btnOpenWeb).setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(CONVERTER_URL)
                    )
                )
            } catch (e: Exception) {
                toast("주소를 열 수 없습니다")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermStatus()
    }

    private fun updatePermStatus() {
        val granted = StoragePermissionHelper.hasAllFilesAccess()
        tvPermStatus.text = if (granted) "저장소 접근: 허용됨" else "저장소 접근: 허용 필요"
        btnGrant.isEnabled = !granted
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val CONVERTER_URL = "https://vocal-conv.cloud/"
    }
}
