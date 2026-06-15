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

    // 민감도 테스트용 (설정 화면에서만 잠깐 동작)
    private val percussion = PercussionSynth()
    private var testShake: ShakeDetector? = null
    private var testLight: LightSensorMonitor? = null

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

        // 센서 민감도 (가속도계 흔들기 / 조도) — 변경 즉시 저장.
        // 테스트 중이면 변경된 임계값으로 즉시 재시작해 바로 체감할 수 있게 한다.
        setupSensitivity(
            findViewById(R.id.seekShake), findViewById(R.id.tvShakeLabel),
            "흔들어 타악기 민감도", settings.shakeLevel
        ) { settings.shakeLevel = it; if (testShake != null) startShakeTest() }
        setupSensitivity(
            findViewById(R.id.seekLight), findViewById(R.id.tvLightLabel),
            "무대 조명 민감도", settings.lightLevel
        ) { settings.lightLevel = it; if (testLight != null) startLightTest() }

        setupSensorTests()

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
        // 화면을 벗어나면 테스트 센서도 정리 (버튼 상태 원복)
        if (testShake != null) { stopShakeTest(); findViewById<Button>(R.id.btnShakeTest).text = "흔들어 테스트" }
        if (testLight != null) { stopLightTest(); findViewById<Button>(R.id.btnLightTest).text = "조도 테스트" }
    }

    override fun onDestroy() {
        super.onDestroy()
        percussion.release()
    }

    /** 흔들기/조도 테스트 버튼 연결 */
    private fun setupSensorTests() {
        val btnShake = findViewById<Button>(R.id.btnShakeTest)
        btnShake.setOnClickListener {
            if (testShake == null) { startShakeTest(); btnShake.text = "테스트 중지" }
            else { stopShakeTest(); btnShake.text = "흔들어 테스트" }
        }
        val btnLight = findViewById<Button>(R.id.btnLightTest)
        btnLight.setOnClickListener {
            if (testLight == null) { startLightTest(); btnLight.text = "테스트 중지" }
            else { stopLightTest(); btnLight.text = "조도 테스트" }
        }
    }

    private val shakeIdleText get() = "📳 폰을 흔들어 보세요 (민감도 ${settings.shakeLevel}/5)"
    private val shakeResetRunnable = Runnable {
        if (testShake != null) findViewById<TextView>(R.id.tvShakeTest).text = shakeIdleText
    }

    /** 현재 흔들기 민감도로 테스트 감지기 시작(또는 재시작) */
    private fun startShakeTest() {
        testShake?.stop()
        val tv = findViewById<TextView>(R.id.tvShakeTest)
        tv.text = shakeIdleText
        testShake = ShakeDetector(this, settings.shakeThreshold) { intensity ->
            // 감지되면 탬버린 소리 + 시각 피드백 (잠시 후 안내 문구로 복귀)
            percussion.play(PercussionSynth.Instrument.TAMBOURINE, intensity)
            tv.text = "🔔 감지됨! (세기 ${(intensity * 100).toInt()}%)"
            tv.removeCallbacks(shakeResetRunnable)
            tv.postDelayed(shakeResetRunnable, 700)
        }.also { it.start() }
    }

    private fun stopShakeTest() {
        testShake?.stop(); testShake = null
        val tv = findViewById<TextView>(R.id.tvShakeTest)
        tv.removeCallbacks(shakeResetRunnable)
        tv.text = ""
    }

    /** 현재 조도 민감도로 측정 시작(또는 재시작) — 실시간 lux + 무대 효과 on/off 표시 */
    private fun startLightTest() {
        testLight?.stop()
        val tv = findViewById<TextView>(R.id.tvLightTest)
        tv.text = "조도 측정 중…"
        testLight = LightSensorMonitor(this, settings.darkLuxCeil) { lux, darkness ->
            val on = darkness > 0.15f
            tv.text = "현재 ${lux.toInt()} lux · 무대 효과 ${if (on) "● 켜짐" else "○ 꺼짐"}"
        }.also { it.start() }
    }

    private fun stopLightTest() {
        testLight?.stop(); testLight = null
        findViewById<TextView>(R.id.tvLightTest).text = ""
    }

    /** 1~5 민감도 SeekBar 구성: 라벨 표시 + 변경 시 저장 콜백 */
    private fun setupSensitivity(
        seek: android.widget.SeekBar,
        label: TextView,
        title: String,
        initial: Int,
        onChange: (Int) -> Unit
    ) {
        fun render(level: Int) { label.text = "$title: $level / 5" }
        seek.progress = initial
        render(initial)
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                val level = value.coerceIn(1, 5)
                render(level)
                if (fromUser) onChange(level)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
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
