package com.app.vocalmaster

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import java.io.File

/** 메인 화면: 검색창 + 곡 리스트 + 하단 탭(목록/즐겨찾기/설정) */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: SettingsManager
    private lateinit var vpm: VocalPackageManager

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var searchInput: TextInputEditText
    private lateinit var bottomNav: BottomNavigationView

    private val adapter = SongListAdapter { song -> showDetail(song) }

    private var allSongs: List<VocalSong> = emptyList()
    private var showFavoritesOnly = false
    private var query: String = ""

    // 곡 파일 선택 런처 (SAF) → 선택되면 메타 입력 다이얼로그
    private val pickVocalFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) showAddSongDialog(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = SettingsManager(this)
        vpm = VocalPackageManager(this)

        recycler = findViewById(R.id.recyclerSongs)
        tvEmpty = findViewById(R.id.tvEmpty)
        searchInput = findViewById(R.id.searchInput)
        bottomNav = findViewById(R.id.bottomNav)
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAdd)
            .setOnClickListener {
                // .vocal은 임의 zip이라 MIME이 불명확 → 모든 파일 허용
                pickVocalFile.launch(arrayOf("*/*"))
            }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                applyFilter()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // 검색 키 누르면 키보드 닫기 (필터는 실시간 적용되므로 별도 동작 불필요)
        searchInput.setOnEditorActionListener { v, _, _ ->
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
            true
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_list -> { showFavoritesOnly = false; applyFilter(); true }
                R.id.nav_favorites -> { showFavoritesOnly = true; applyFilter(); true }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); false
                }
                else -> false
            }
        }

        // 상세 시트에서 즐겨찾기가 바뀌면 즉시 리스트 갱신
        supportFragmentManager.setFragmentResultListener(
            SongDetailSheet.RESULT_KEY, this
        ) { _, _ -> loadSongs() }
    }

    override fun onResume() {
        super.onResume()
        ensurePermissionThenLoad()
    }

    private var storagePromptShown = false

    // 마이크 권한 런타임 요청 런처
    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 결과는 스코어 모드 진입 시 다시 확인하므로 별도 처리 불필요 */ }

    private fun ensurePermissionThenLoad() {
        if (!StoragePermissionHelper.hasAllFilesAccess()) {
            showEmpty("저장소 권한이 필요합니다.\n아래 안내에 따라 '모든 파일 접근'을 허용해 주세요.")
            // 첫 진입 시 한 번만 권한 화면으로 유도
            if (!storagePromptShown) {
                storagePromptShown = true
                AlertDialog.Builder(this)
                    .setTitle("저장소 접근 권한")
                    .setMessage("노래 파일(.vocal)을 읽으려면 '모든 파일 접근' 권한이 필요합니다. 설정 화면으로 이동할까요?")
                    .setPositiveButton("이동") { _, _ ->
                        startActivity(StoragePermissionHelper.buildManageStorageIntent(this))
                    }
                    .setNegativeButton("나중에", null)
                    .show()
            }
            return
        }
        // 저장소 권한이 있으면 마이크 권한도 미리 확보(스코어 모드용)
        requestMicIfNeeded()
        loadSongs()
    }

    private fun requestMicIfNeeded() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun loadSongs() {
        val folder = File(settings.folderPath)
        lifecycleScope.launch {
            allSongs = try {
                vpm.scanVocalFiles(folder, settings.sortOrder)
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "스캔 실패", e)
                emptyList()
            }
            applyFilter()
        }
    }

    /** 검색어 + 즐겨찾기 필터. 검색 형식: "(가수) - (곡명)" 부분일치 */
    private fun applyFilter() {
        var list = allSongs
        if (showFavoritesOnly) list = list.filter { it.stat.favorite }
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            list = list.filter { song ->
                val artist = song.meta.artist.lowercase()
                val title = song.meta.title.lowercase()
                val dash = q.indexOf(" - ")
                if (dash >= 0) {
                    val qa = q.substring(0, dash).trim()
                    val qt = q.substring(dash + 3).trim()
                    artist.contains(qa) && title.contains(qt)
                } else {
                    artist.contains(q) || title.contains(q)
                }
            }
        }
        adapter.submit(list)
        if (list.isEmpty()) {
            showEmpty(if (allSongs.isEmpty()) "이 폴더에 .vocal 파일이 없습니다." else "검색 결과가 없습니다.")
        } else {
            tvEmpty.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun showEmpty(msg: String) {
        adapter.submit(emptyList())
        recycler.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = msg
    }

    private fun showDetail(song: VocalSong) {
        SongDetailSheet.newInstance(song.file.absolutePath)
            .show(supportFragmentManager, "detail")
    }

    /** 선택한 파일의 표시 이름을 가수/곡명으로 자동 파싱 후 입력 다이얼로그 표시 */
    private fun showAddSongDialog(sourceUri: android.net.Uri) {
        val displayName = queryDisplayName(sourceUri) ?: "untitled.vocal"
        val parsed = SongMeta.parseFromFileName(displayName) // "가수 - 곡명" 파싱, 안되면 곡명만

        val dialogView = layoutInflater.inflate(R.layout.dialog_add_song, null)
        val etArtist = dialogView.findViewById<android.widget.EditText>(R.id.etArtist)
        val etTitle = dialogView.findViewById<android.widget.EditText>(R.id.etTitle)
        val cbFav = dialogView.findViewById<android.widget.CheckBox>(R.id.cbFavorite)

        // 자동 파싱 결과 채우기 (가수 파싱 실패 시 Unknown → 비워두고 곡명만 채움)
        if (parsed.artist != "Unknown") etArtist.setText(parsed.artist)
        etTitle.setText(parsed.title)

        AlertDialog.Builder(this)
            .setTitle("곡 추가")
            .setView(dialogView)
            .setPositiveButton("추가") { _, _ ->
                val artist = etArtist.text.toString().ifBlank { "Unknown" }
                val title = etTitle.text.toString().ifBlank { parsed.title }
                importSong(sourceUri, artist, title, cbFav.isChecked)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun importSong(uri: android.net.Uri, artist: String, title: String, favorite: Boolean) {
        val folder = File(settings.folderPath)
        lifecycleScope.launch {
            try {
                val dest = vpm.importSong(uri, folder, artist, title)
                if (favorite) {
                    // 복사된 파일의 해시 ID로 즐겨찾기 설정
                    val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { VocalPackageManager.computeSongId(dest) }.getOrNull()
                    }
                    id?.let { SongStatsStore(this@MainActivity).toggleFavorite(it) }
                }
                android.widget.Toast.makeText(
                    this@MainActivity, "추가됨: ${dest.name}", android.widget.Toast.LENGTH_SHORT
                ).show()
                loadSongs()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "곡 추가 실패", e)
                android.widget.Toast.makeText(
                    this@MainActivity, "곡 추가 실패: ${e.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /** content Uri에서 표시 이름(파일명) 조회 */
    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) { null }
    }
}
