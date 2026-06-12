package com.app.vocalmaster

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 곡 터치 시 뜨는 상세 바텀시트: 세부정보 + 즐겨찾기 + 스코어/연습 모드 진입 */
class SongDetailSheet : BottomSheetDialogFragment() {

    private lateinit var statsStore: SongStatsStore
    private var songId: String? = null
    private var preKey: Int = 0  // 사전 설정 반음 (-6 ~ +6)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_song_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        statsStore = SongStatsStore(ctx)

        val path = arguments?.getString(ARG_PATH) ?: run { dismiss(); return }
        val file = File(path)
        val meta = SongMeta.parseFromFileName(file.name)

        val tvTitle = view.findViewById<TextView>(R.id.tvDetailTitle)
        val tvInfo = view.findViewById<TextView>(R.id.tvDetailInfo)
        val btnFav = view.findViewById<ImageButton>(R.id.btnFavorite)
        val btnScore = view.findViewById<Button>(R.id.btnScoreMode)
        val btnPractice = view.findViewById<Button>(R.id.btnPracticeMode)
        val btnKeyUp = view.findViewById<ImageButton>(R.id.btnKeyUpPre)
        val btnKeyDown = view.findViewById<ImageButton>(R.id.btnKeyDownPre)
        val tvKeyPre = view.findViewById<TextView>(R.id.tvKeyPre)

        fun renderKey() {
            tvKeyPre.text = when {
                preKey > 0 -> "+$preKey"
                preKey < 0 -> "$preKey"
                else -> "원키"
            }
        }
        btnKeyUp.setOnClickListener { preKey = (preKey + 1).coerceIn(-6, 6); renderKey() }
        btnKeyDown.setOnClickListener { preKey = (preKey - 1).coerceIn(-6, 6); renderKey() }
        renderKey()

        tvTitle.text = meta.displayName
        tvInfo.text = "정보를 불러오는 중…"

        // 해시 ID + 메타는 IO에서 (zip 읽기)
        lifecycleScope.launch {
            val (id, meta2) = withContext(Dispatchers.IO) {
                // songIdOf: 캐시 우선 — 시트를 열 때마다 비디오 전체를 해싱하지 않음
                val sid = try { VocalPackageManager(ctx).songIdOf(file) } catch (e: Exception) { null }
                val m = VocalPackageManager.readMeta(file)
                Pair(sid, m)
            }
            songId = id
            val stat = id?.let { statsStore.get(it) }
            val (durMs, keyHz) = meta2
            tvInfo.text = buildString {
                append("곡 길이: ").append(SongListAdapter.formatDuration(durMs)).append("\n")
                append("평균 키: ").append(SongListAdapter.formatKey(keyHz)).append("\n")
                val plays = stat?.playCount ?: 0
                if (plays > 0) {
                    append("부른 횟수: $plays\n")
                    append("최고 점수: ${stat?.bestScore ?: 0}")
                } else {
                    append("아직 부른 기록이 없습니다")
                }
            }
            updateFavIcon(btnFav, stat?.favorite == true)
        }

        btnFav.setOnClickListener {
            val id = songId ?: return@setOnClickListener
            val nowFav = statsStore.toggleFavorite(id)
            updateFavIcon(btnFav, nowFav)
            // 메인 화면에 변경 알림 → 리스트 즉시 갱신
            parentFragmentManager.setFragmentResult(RESULT_KEY, android.os.Bundle.EMPTY)
        }

        btnScore.setOnClickListener { launchPlayer(file, meta, PlayerMode.SCORE) }
        btnPractice.setOnClickListener { launchPlayer(file, meta, PlayerMode.PRACTICE) }

        view.findViewById<Button>(R.id.btnDelete).setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("곡 삭제")
                .setMessage("'${meta.displayName}' 을(를) 삭제할까요?\n이 작업은 되돌릴 수 없습니다.")
                .setPositiveButton("삭제") { _, _ ->
                    val ok = VocalPackageManager(requireContext()).deleteSong(file)
                    android.widget.Toast.makeText(
                        requireContext(),
                        if (ok) "삭제됨" else "삭제 실패",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    if (ok) {
                        parentFragmentManager.setFragmentResult(RESULT_KEY, android.os.Bundle.EMPTY)
                        dismiss()
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    private fun launchPlayer(file: File, meta: SongMeta, mode: PlayerMode) {
        val intent = Intent(requireContext(), PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_VOCAL_PATH, file.absolutePath)
            putExtra(PlayerActivity.EXTRA_VOCAL_TITLE, meta.displayName)
            putExtra(PlayerActivity.EXTRA_MODE, mode.name)
            putExtra(PlayerActivity.EXTRA_KEY, preKey)
        }
        startActivity(intent)
        dismiss()
    }

    private fun updateFavIcon(btn: ImageButton, fav: Boolean) {
        btn.setImageResource(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
    }

    companion object {
        private const val ARG_PATH = "arg_path"
        const val RESULT_KEY = "song_detail_changed"
        fun newInstance(path: String) = SongDetailSheet().apply {
            arguments = Bundle().apply { putString(ARG_PATH, path) }
        }
    }
}

/** 플레이어 모드 */
enum class PlayerMode { SCORE, PRACTICE }
