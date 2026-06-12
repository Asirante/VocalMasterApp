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
 * - 뒤집어서 일시정지 켜기/끄기 (연습 모드, 가속도계)
 * - 연습 환경: 온도/습도 센서로 보컬 컨디션 안내 (센서 없으면 안내 문구)
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var tvPermStatus: TextView
    private lateinit var btnGrant: Button
    private lateinit var etFolder: android.widget.EditText
    private lateinit var btnSaveFolder: Button
    private lateinit var btnResetFolder: Button
    private lateinit var spinnerSort: Spinner
    private lateinit var tvEnvValues: TextView
    private lateinit var tvEnvAdvice: TextView
    private lateinit var envMonitor: EnvironmentMonitor

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

        // 뒤집어서 일시정지 (연습 모드)
        val switchFlipPause =
            findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchFlipPause)
        switchFlipPause.isChecked = settings.flipToPause
        switchFlipPause.setOnCheckedChangeListener { _, checked ->
            settings.flipToPause = checked
        }

        // 연습 환경 (온도/습도 → 보컬 컨디션)
        tvEnvValues = findViewById(R.id.tvEnvValues)
        tvEnvAdvice = findViewById(R.id.tvEnvAdvice)
        envMonitor = EnvironmentMonitor(this) { tempC, humidity ->
            updateEnvironment(tempC, humidity)
        }
        if (envMonitor.isAvailable) {
            updateEnvironment(null, null) // 측정 전 초기 표시
        } else {
            tvEnvValues.text = "이 기기에는 온도/습도 센서가 없습니다."
            tvEnvAdvice.text = "성대 보호를 위해 습도 40~60%의 환경에서 연습하는 것이 좋아요."
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
        envMonitor.start()
    }

    override fun onPause() {
        super.onPause()
        envMonitor.stop()
    }

    /** 온도/습도 값 표시 + 성대 컨디션 안내 갱신 */
    private fun updateEnvironment(tempC: Float?, humidity: Float?) {
        val parts = mutableListOf<String>()
        if (envMonitor.hasTemperature) {
            parts += "온도 " + (tempC?.let { "%.1f℃".format(it) } ?: "측정 중…")
        }
        if (envMonitor.hasHumidity) {
            parts += "습도 " + (humidity?.let { "%.0f%%".format(it) } ?: "측정 중…")
        }
        tvEnvValues.text = parts.joinToString("  ·  ")
        tvEnvAdvice.text = buildVocalAdvice(tempC, humidity)
    }

    /** 성대 건강 기준(습도 40~60% 적정)에 따른 안내 문구 */
    private fun buildVocalAdvice(tempC: Float?, humidity: Float?): String {
        val tips = mutableListOf<String>()
        if (humidity != null) {
            tips += when {
                humidity < 30f -> "매우 건조해요. 성대 보호를 위해 물을 자주 마시고 가습을 권장합니다."
                humidity < 40f -> "조금 건조한 편이에요. 연습 전 물 한 잔 어떠세요?"
                humidity <= 60f -> "성대에 적당한 습도예요."
                else -> "습도가 다소 높아요. 환기를 한 번 해 주세요."
            }
        }
        if (tempC != null) {
            when {
                tempC < 15f -> tips += "쌀쌀하네요. 노래 전 목을 충분히 풀어 주세요."
                tempC > 28f -> tips += "더운 환경에선 수분 보충을 잊지 마세요."
            }
        }
        return tips.joinToString(" ")
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
