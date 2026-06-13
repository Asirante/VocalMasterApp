package com.app.vocalmaster

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.addCallback
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
    private lateinit var tvKeyLabel: TextView
    private lateinit var btnKeyUp: ImageButton
    private lateinit var btnKeyDown: ImageButton
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnPrev: android.widget.Button
    private lateinit var btnNext: android.widget.Button
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView
    private lateinit var layoutSeekbar: View
    private lateinit var layoutControls: View
    private lateinit var progressLoading: View

    private var readyListener: Player.Listener? = null

    private var mode: PlayerMode = PlayerMode.SCORE
    private var currentSongId: String? = null
    private var resultShown = false
    private var userSeeking = false
    private var preKey: Int = 0
    private val statsStore by lazy { SongStatsStore(this) }

    // 백그라운드 복귀 시 스코어 모드 재개용 상태
    private var targetPitches: List<PitchPoint>? = null
    private var playbackStarted = false
    private var unpacked: UnpackedData? = null
    private var micNoticeShown = false

    // 센서 악기 (가속도계로 흔들면 켜진 악기 소리)
    private val percussion = PercussionSynth()
    private lateinit var shakeDetector: ShakeDetector
    private val activeInstruments = linkedSetOf<PercussionSynth.Instrument>()

    // 조도 센서 무대 효과
    private lateinit var lightMonitor: LightSensorMonitor
    private lateinit var stageGlow: View
    private var currentDarkness = 0f

    // 뒤집어서 일시정지 (연습 모드, 가속도계)
    private val settings by lazy { SettingsManager(this) }
    private lateinit var flipPauseDetector: FlipPauseDetector
    private var pausedByFlip = false
    private var flipHintShown = false

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

        // 노래 부르는 동안 터치가 없어도 화면이 꺼지지 않도록
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
            .setNavigationOnClickListener { confirmExit() }
        // 시스템 뒤로가기도 동일하게 — 스코어 모드 중도 이탈 실수 방지
        onBackPressedDispatcher.addCallback(this) { confirmExit() }

        playerView = findViewById(R.id.playerView)
        tvSongTitle = findViewById(R.id.tvSongTitle)
        tvKeyLabel = findViewById(R.id.tvKeyLabel)
        progressLoading = findViewById(R.id.progressLoading)
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
        // 재생 상태에 맞춰 재생/일시정지 아이콘 동기화
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        })
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
        setupFlipPause()
    }

    /** 연습 모드: 폰을 엎어두면 일시정지, 다시 들면 이어서 재생 (가속도계) */
    private fun setupFlipPause() {
        flipPauseDetector = FlipPauseDetector(
            this,
            onFaceDown = {
                if (player.isPlaying) {
                    player.pause()
                    pausedByFlip = true
                    if (!flipHintShown) {
                        flipHintShown = true
                        android.widget.Toast.makeText(
                            this, "일시정지 — 다시 들면 이어서 재생됩니다",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            },
            onFaceUp = {
                if (pausedByFlip) {
                    pausedByFlip = false
                    player.play()
                }
            }
        )
    }

    /** 악기 토글 + 흔들기 감지 연결 */
    private fun setupInstruments() {
        val tambourine = findViewById<ToggleButton>(R.id.btnTambourine)
        val maraca = findViewById<ToggleButton>(R.id.btnMaraca)
        val cowbell = findViewById<ToggleButton>(R.id.btnCowbell)

        fun bind(btn: ToggleButton, inst: PercussionSynth.Instrument) {
            btn.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    activeInstruments.add(inst)
                    // 켜는 즉시 미리듣기 한 번 — "흔들면 이 소리가 난다"를 바로 들려준다.
                    percussion.play(inst, 0.7f)
                } else {
                    activeInstruments.remove(inst)
                }
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
        //    밝은 환경에서는 강제하지 않고 시스템/사용자 설정에 맡긴다.
        val lp = window.attributes
        lp.screenBrightness = if (darkness > 0.15f) {
            (0.5f + 0.5f * darkness).coerceIn(0.5f, 1f)
        } else {
            android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
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
     * - SCORE: 탐색바·컨트롤 숨김(쭉 부르기, 점수는 곡 끝나고 다이얼로그)
     * - PRACTICE: 탐색바·컨트롤 표시(자유 연습)
     */
    private fun applyModeVisibility() {
        val practice = mode == PlayerMode.PRACTICE
        layoutSeekbar.visibility = if (practice) View.VISIBLE else View.GONE
        layoutControls.visibility = if (practice) View.VISIBLE else View.GONE
    }

    private fun loadSong(vocalFile: File, title: String) {
        tvSongTitle.text = title
        progressLoading.visibility = View.VISIBLE // 압축 해제 동안 로딩 표시
        lifecycleScope.launch {
            // 해싱/캐시 조회는 IO에서 (메인 스레드 디스크 I/O 방지)
            currentSongId = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { vocalPackageManager.songIdOf(vocalFile) }.getOrNull()
            }
            // .vocal은 외부에서 들여오는 파일 — 손상/비정상 zip이면 크래시 대신 안내 후 종료
            val data = try {
                vocalPackageManager.extractAndLoad(vocalFile)
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "곡 파일 압축 해제 실패: ${vocalFile.name}", e)
                android.widget.Toast.makeText(
                    this@PlayerActivity, "곡 파일을 열 수 없습니다 (손상되었거나 잘못된 형식)",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            progressLoading.visibility = View.GONE
            unpacked = data
            onSongChanged()
            startEngine(data)
        }
    }

    private fun startEngine(data: UnpackedData) {
        val pitches = VocalPackageManager.parseJson(data.pitchFile)
        targetPitches = pitches

        readyListener?.let { player.removeListener(it) }
        playbackStarted = false
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !playbackStarted) {
                    playbackStarted = true
                    val dur = player.duration.coerceAtLeast(0)
                    seekBar.max = dur.toInt()
                    tvTotalTime.text = formatTime(dur)
                    player.play()
                    if (mode == PlayerMode.SCORE) startScoring(pitches)
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
        if (!micGranted) {
            // 끝까지 부른 뒤 0점을 받지 않도록 미리 알림 (한 번만)
            if (!micNoticeShown) {
                micNoticeShown = true
                android.widget.Toast.makeText(
                    this, "마이크 권한이 없어 이번 곡은 채점되지 않습니다",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // 점수/판정은 화면에 실시간 표시하지 않고 내부 누적만 (결과는 끝나고 표시)
        scoringEngine.start(targetPitches) { _, _, _, _, _ -> }
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
        scoringEngine.reset() // 새 판 시작 — 누적 점수 초기화
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
            .setNegativeButton("다시 부르기") { _, _ -> restartSong() }
            .setCancelable(false)
            .show()
    }

    /** 결과 확인 후 같은 곡을 처음부터 다시 (재도전). 이전 판 점수는 이미 기록됨. */
    private fun restartSong() {
        resultShown = false
        scoringEngine.reset()
        player.seekTo(0)
        player.play()
        if (mode == PlayerMode.SCORE) targetPitches?.let { startScoring(it) }
    }

    /**
     * 나가기 처리. 스코어 모드로 부르는 중이면 실수 방지를 위해 한 번 확인.
     * (연습 모드/결과 확인 후/재생 시작 전에는 바로 종료)
     */
    private fun confirmExit() {
        if (mode != PlayerMode.SCORE || resultShown || !playbackStarted) {
            finish()
            return
        }
        val wasPlaying = player.isPlaying
        player.pause()
        AlertDialog.Builder(this)
            .setTitle("그만 부를까요?")
            .setMessage("지금 나가면 현재까지의 점수만 기록됩니다.")
            .setPositiveButton("나가기") { _, _ -> finish() }
            .setNegativeButton("계속 부르기") { _, _ -> if (wasPlaying) player.play() }
            .setOnCancelListener { if (wasPlaying) player.play() }
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
        // 스코어 모드는 '끊김 없이 쭉 부르기' 설계라 연습 모드에서만 사용
        if (::flipPauseDetector.isInitialized &&
            mode == PlayerMode.PRACTICE && settings.flipToPause
        ) {
            flipPauseDetector.start()
        }

        // onStop에서 멈춘 진행 폴링 재개
        handler.removeCallbacks(tick)
        handler.post(tick)

        // 스코어 모드는 재생 컨트롤이 없으므로, 백그라운드 복귀 시
        // 재생과 채점(마이크)을 자동으로 이어서 시작한다. 누적 점수는 유지됨.
        if (mode == PlayerMode.SCORE && playbackStarted && !resultShown) {
            player.play()
            targetPitches?.let { startScoring(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (::shakeDetector.isInitialized) shakeDetector.stop()
        if (::lightMonitor.isInitialized) lightMonitor.stop()
        if (::flipPauseDetector.isInitialized) flipPauseDetector.stop()
        pausedByFlip = false // 화면 이탈 후 복귀 시 자동 재생 방지
        handler.removeCallbacks(tick) // 백그라운드 폴링 중단
        scoringEngine.stop() // 마이크만 중단 — 누적 점수는 유지
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 결과를 표시하지 않고 빠져나간 경우(중도 이탈)에도 스코어 모드면 기록
        if (mode == PlayerMode.SCORE && !resultShown) {
            currentSongId?.let { statsStore.recordPlay(it, scoringEngine.getResult().avgScore) }
        }
        handler.removeCallbacks(tick)
        playerView.player = null
        player.release()
        percussion.release()
        unpacked?.let { vocalPackageManager.clearExtracted(it) }
    }

    companion object {
        const val EXTRA_VOCAL_PATH = "extra_vocal_path"
        const val EXTRA_VOCAL_TITLE = "extra_vocal_title"
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_KEY = "extra_key"
    }
}
