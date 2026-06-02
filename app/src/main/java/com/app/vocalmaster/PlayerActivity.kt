package com.app.vocalmaster

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import java.io.File

/**
 * 영상 재생 화면. 모드에 따라 동작 분기.
 * - SCORE: 영상 재생 + 마이크 채점. 진행 중 점수 숨김, 곡 끝나면 결과 다이얼로그. 탐색바/컨트롤 없음.
 * - PRACTICE: 점수 없음. 탐색 바 + ±10초 + 10초 점프(이전/다음). 자유 연습.
 * 음정 그래프/가사 오버레이는 없음(가사는 영상 자체에 포함).
 */
class PlayerActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var scoringEngine: ScoringEngine
    private lateinit var keyController: KeyController
    private lateinit var vocalPackageManager: VocalPackageManager

    private lateinit var playerView: PlayerView
    private lateinit var tvSongTitle: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvJudgment: TextView
    private lateinit var tvKeyLabel: TextView
    private lateinit var btnKeyUp: ImageButton
    private lateinit var btnKeyDown: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var layoutSeekbar: View
    private lateinit var layoutControls: View

    private var readyListener: Player.Listener? = null

    private var mode: PlayerMode = PlayerMode.SCORE
    private var currentSongId: String? = null
    private var lastScore: Int = 0
    private var resultShown = false
    private var userSeeking = false
    private var preKey: Int = 0
    private val statsStore by lazy { SongStatsStore(this) }

    // 센서 악기 (가속도계로 흔들면 켜진 악기 소리)
    private val percussion = PercussionSynth()
    private lateinit var shakeDetector: ShakeDetector
    private val activeInstruments = linkedSetOf<PercussionSynth.Instrument>()

    // 조도 센서 무대 효과
    private lateinit var lightMonitor: LightSensorMonitor
    private lateinit var stageGlow: View
    private var currentDarkness = 0f

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val pos = player.currentPosition
            scoringEngine.updatePosition(pos)

            if (!userSeeking) seekBar.progress = pos.toInt()
            tvCurrentTime.text = formatTime(pos)

            // 스코어 모드: 곡 종료 감지 → 결과
            if (mode == PlayerMode.SCORE && player.duration > 0 &&
                pos >= player.duration - 250 && !resultShown
            ) {
                showResult()
            }
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { finish() }

        playerView = findViewById(R.id.playerView)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvScore = findViewById(R.id.tvScore)
        tvJudgment = findViewById(R.id.tvJudgment)
        tvKeyLabel = findViewById(R.id.tvKeyLabel)
        btnKeyUp = findViewById(R.id.btnKeyUp)
        btnKeyDown = findViewById(R.id.btnKeyDown)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
        layoutSeekbar = findViewById(R.id.layoutSeekbar)
        layoutControls = findViewById(R.id.layoutControls)

        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        scoringEngine = ScoringEngine()
        vocalPackageManager = VocalPackageManager(this)
        keyController = KeyController(player) { m -> scoringEngine.setKeyMultiplier(m) }

        mode = runCatching { PlayerMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "SCORE") }
            .getOrDefault(PlayerMode.SCORE)
        preKey = intent.getIntExtra(EXTRA_KEY, 0)
        applyModeVisibility()

        btnKeyUp.setOnClickListener { keyController.shiftKey(+1); updateKeyLabel() }
        btnKeyDown.setOnClickListener { keyController.shiftKey(-1); updateKeyLabel() }
        btnPlayPause.setOnClickListener {
            if (player.isPlaying) player.pause() else player.play()
        }

        // 연습 모드: 이전/다음 = ±10초
        btnPrev.setOnClickListener { seekBy(-10_000) }
        btnNext.setOnClickListener { seekBy(+10_000) }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) tvCurrentTime.text = formatTime(p.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                userSeeking = false
                player.seekTo(seekBar.progress.toLong())
            }
        })

        val vocalPath = intent.getStringExtra(EXTRA_VOCAL_PATH)
        val vocalTitle = intent.getStringExtra(EXTRA_VOCAL_TITLE) ?: ""
        if (vocalPath != null) loadSong(File(vocalPath), vocalTitle)

        setupInstruments()
    }

    /** 악기 토글 + 흔들기 감지 연결 */
    private fun setupInstruments() {
        val tambourine = findViewById<ToggleButton>(R.id.btnTambourine)
        val maraca = findViewById<ToggleButton>(R.id.btnMaraca)
        val cowbell = findViewById<ToggleButton>(R.id.btnCowbell)

        fun bind(btn: ToggleButton, inst: PercussionSynth.Instrument) {
            btn.setOnCheckedChangeListener { _, checked ->
                if (checked) activeInstruments.add(inst) else activeInstruments.remove(inst)
            }
        }
        bind(tambourine, PercussionSynth.Instrument.TAMBOURINE)
        bind(maraca, PercussionSynth.Instrument.MARACA)
        bind(cowbell, PercussionSynth.Instrument.COWBELL)

        // 흔들면 켜진 악기를 세기(intensity)에 맞춰 동시 재생 + 무대 글로우 번쩍임
        shakeDetector = ShakeDetector(this) { intensity ->
            if (currentDarkness > 0.15f) pulseGlow(intensity)
            if (activeInstruments.isEmpty()) return@ShakeDetector
            for (inst in activeInstruments) {
                percussion.play(inst, intensity)
            }
        }

        setupStageEffect()
    }

    /** 조도 센서 → 어두우면 화면 밝기 ↑ + 무대 글로우 표시 */
    private fun setupStageEffect() {
        stageGlow = findViewById(R.id.stageGlow)
        lightMonitor = LightSensorMonitor(this) { _, darkness ->
            currentDarkness = darkness
            applyStageEffect(darkness)
        }
    }

    private fun applyStageEffect(darkness: Float) {
        // 1) 화면 밝기: 어두울수록 밝게 (교수님 요청). 0.5~1.0 범위로.
        val lp = window.attributes
        lp.screenBrightness = (0.5f + 0.5f * darkness).coerceIn(0.5f, 1f)
        window.attributes = lp

        // 2) 무대 글로우: 어두우면 은은하게 켜둠(베이스 알파), 밝으면 끔
        if (darkness > 0.15f) {
            stageGlow.visibility = View.VISIBLE
            stageGlow.alpha = 0.15f + 0.35f * darkness  // 기본 은은한 발광
        } else {
            stageGlow.visibility = View.GONE
        }
    }

    /** 흔들 때 글로우를 잠깐 번쩍이게 (세기 비례) */
    private fun pulseGlow(intensity: Float) {
        stageGlow.visibility = View.VISIBLE
        val peak = (0.5f + 0.5f * intensity).coerceIn(0f, 1f)
        stageGlow.animate().cancel()
        stageGlow.alpha = peak
        stageGlow.animate()
            .alpha(0.15f + 0.35f * currentDarkness)
            .setDuration(180)
            .start()
    }

    /**
     * 모드별 UI:
     * - SCORE: 진행 중 점수/판정 숨김(결과는 끝나고 다이얼로그), 탐색바·컨트롤 숨김(쭉 부르기)
     * - PRACTICE: 점수/판정 숨김, 탐색바·컨트롤 표시(자유 연습)
     */
    private fun applyModeVisibility() {
        tvScore.visibility = View.GONE
        tvJudgment.visibility = View.GONE
        val practice = mode == PlayerMode.PRACTICE
        layoutSeekbar.visibility = if (practice) View.VISIBLE else View.GONE
        layoutControls.visibility = if (practice) View.VISIBLE else View.GONE
    }

    private fun loadSong(vocalFile: File, title: String) {
        tvSongTitle.text = title
        lifecycleScope.launch {
            currentSongId = runCatching { VocalPackageManager.computeSongId(vocalFile) }.getOrNull()
            val data = vocalPackageManager.extractAndLoad(vocalFile)
            onSongChanged()
            startEngine(data)
        }
    }

    private fun startEngine(data: UnpackedData) {
        val targetPitches = VocalPackageManager.parseJson(data.pitchFile)

        readyListener?.let { player.removeListener(it) }
        var hasStarted = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !hasStarted) {
                    hasStarted = true
                    val dur = player.duration.coerceAtLeast(0)
                    seekBar.max = dur.toInt()
                    tvTotalTime.text = formatTime(dur)
                    player.play()
                    if (mode == PlayerMode.SCORE) startScoring(targetPitches)
                }
                if (state == Player.STATE_ENDED && mode == PlayerMode.SCORE && !resultShown) {
                    showResult()
                }
            }
        }
        readyListener = listener
        player.setMediaItem(MediaItem.fromUri(data.videoFile.toUri()))
        player.addListener(listener)
        player.prepare()

        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    private fun startScoring(targetPitches: List<PitchPoint>) {
        // 마이크 권한이 없으면 채점을 시작하지 않음 (크래시/무점수 방지)
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!micGranted) return
        // 점수/판정은 화면에 실시간 표시하지 않고 내부 누적만 (결과는 끝나고 표시)
        scoringEngine.start(targetPitches) { score, _, _, _, _ ->
            lastScore = score
        }
    }

    private fun seekBy(deltaMs: Long) {
        val target = (player.currentPosition + deltaMs)
            .coerceIn(0, player.duration.coerceAtLeast(0))
        player.seekTo(target)
    }

    private fun onSongChanged() {
        keyController.resetKey()
        if (preKey != 0) keyController.shiftKey(preKey) // 사전 설정 키 적용
        updateKeyLabel()
        resultShown = false
    }

    private fun updateKeyLabel() {
        val k = keyController.getCurrentKey()
        tvKeyLabel.text = when {
            k > 0 -> "+$k"
            k < 0 -> "$k"
            else -> "원키"
        }
    }

    private fun showResult() {
        resultShown = true
        scoringEngine.stop()
        player.pause()
        val r = scoringEngine.getResult()
        currentSongId?.let { statsStore.recordPlay(it, r.avgScore) }

        val msg = buildString {
            append("평균 점수: ${r.avgScore}\n\n")
            append("Perfect: ${r.perfect}\n")
            append("Great: ${r.great}\n")
            append("Good: ${r.good}\n")
            append("Miss: ${r.miss}")
        }
        AlertDialog.Builder(this)
            .setTitle("결과")
            .setMessage(msg)
            .setPositiveButton("확인") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun formatTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onResume() {
        super.onResume()
        if (::shakeDetector.isInitialized) shakeDetector.start()
        if (::lightMonitor.isInitialized) lightMonitor.start()
    }

    override fun onStop() {
        super.onStop()
        if (::shakeDetector.isInitialized) shakeDetector.stop()
        if (::lightMonitor.isInitialized) lightMonitor.stop()
        scoringEngine.stop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 결과를 표시하지 않고 빠져나간 경우(중도 이탈)에도 스코어 모드면 기록
        if (mode == PlayerMode.SCORE && !resultShown) {
            currentSongId?.let { statsStore.recordPlay(it, lastScore) }
        }
        handler.removeCallbacks(tick)
        playerView.player = null
        player.release()
        vocalPackageManager.clearCache()
    }

    companion object {
        const val EXTRA_VOCAL_PATH = "extra_vocal_path"
        const val EXTRA_VOCAL_TITLE = "extra_vocal_title"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_KEY = "extra_key"
    }
}
