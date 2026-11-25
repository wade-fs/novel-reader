package com.novel.reader.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.OpenableColumns
import android.speech.tts.Voice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.novel.reader.R
import com.novel.reader.databinding.ActivityMainBinding
import com.novel.reader.model.*
import com.novel.reader.repository.NovelRepository
import com.novel.reader.service.TtsReadingService
import com.novel.reader.util.BookLibraryManager
import com.novel.reader.util.GitHubLoadResult
import com.novel.reader.util.ReadingProgressManager
import com.novel.reader.util.SavedReadingProgress
import com.novel.reader.util.WebBookImportResult
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity(), TtsReadingService.PlaybackListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var contentAdapter: NovelContentAdapter
    private lateinit var repository: NovelRepository
    private lateinit var progressManager: ReadingProgressManager
    private lateinit var bookLibraryManager: BookLibraryManager

    private var ttsService: TtsReadingService? = null
    private var isBound = false
    private var availableVoices: List<Voice> = emptyList()
    private var pendingSavedProgress: SavedReadingProgress? = null
    private var isImmersiveFullScreen: Boolean = true

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsReadingService.LocalBinder
            ttsService = binder.getService()
            isBound = true
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                ttsService?.setAppInForeground(true)
            }
            ttsService?.addListener(this@MainActivity)

            if (ttsService?.chapters.isNullOrEmpty()) {
                loadInitialBookFromLibrary()
            } else {
                val activeTitle = ttsService?.currentNovelTitle
                if (!activeTitle.isNullOrBlank()) {
                    binding.toolbar.title = activeTitle
                }
                updatePlaybackControls(ttsService?.playbackState ?: TtsReadingService.PlaybackState.IDLE)
                updateSettingsBadges()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ttsService?.removeListener(this@MainActivity)
            ttsService = null
            isBound = false
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // 開啟外部資料夾 (SAF)
    private val openDocumentTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            lifecycleScope.launch {
                val externalChapters = repository.loadCustomDirectoryChapters(treeUri)
                if (externalChapters.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val entry = BookEntry(
                        id = repository.currentNovelId,
                        title = repository.currentNovelTitle,
                        sourceType = BookSourceType.LOCAL_DIRECTORY,
                        localPath = null,
                        sourceUriOrUrl = treeUri.toString(),
                        chapterCount = externalChapters.size,
                        addedAt = now,
                        lastOpenedAt = now
                    )
                    bookLibraryManager.addOrUpdateBook(entry)
                    bookLibraryManager.markOpened(entry.id)

                    val saved = progressManager.getSavedProgressForBook(entry.id)
                    val targetIdx = saved?.chapterIndex?.coerceIn(externalChapters.indices) ?: 0
                    val targetSeg = saved?.segmentIndex ?: 0

                    binding.toolbar.title = entry.title
                    ttsService?.setChapterList(
                        list = externalChapters,
                        initialIndex = targetIdx,
                        initialSegmentIndex = targetSeg,
                        startPlay = true,
                        novelId = entry.id,
                        novelTitle = entry.title
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "已加入書單「${entry.title}」（共 ${externalChapters.size} 章）",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "所選目錄中未找到小說章節檔案 (.md / .txt)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // 開啟 ZIP / TAR 壓縮檔
    private val openArchiveLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { archiveUri ->
        if (archiveUri != null) {
            val fileName = queryDisplayName(archiveUri) ?: "novel.zip"
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "正在讀取壓縮檔：$fileName...", Toast.LENGTH_SHORT).show()
                val chapters = repository.loadArchiveChapters(archiveUri, fileName)
                if (chapters.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val entry = BookEntry(
                        id = repository.currentNovelId,
                        title = repository.currentNovelTitle,
                        sourceType = BookSourceType.LOCAL_ARCHIVE,
                        localPath = repository.currentNovelLocalPath,
                        sourceUriOrUrl = fileName,
                        chapterCount = chapters.size,
                        addedAt = now,
                        lastOpenedAt = now
                    )
                    bookLibraryManager.addOrUpdateBook(entry)
                    bookLibraryManager.markOpened(entry.id)

                    val saved = progressManager.getSavedProgressForBook(entry.id)
                    val targetIdx = saved?.chapterIndex?.coerceIn(chapters.indices) ?: 0
                    val targetSeg = saved?.segmentIndex ?: 0

                    binding.toolbar.title = entry.title
                    ttsService?.setChapterList(
                        list = chapters,
                        initialIndex = targetIdx,
                        initialSegmentIndex = targetSeg,
                        startPlay = true,
                        novelId = entry.id,
                        novelTitle = entry.title
                    )
                    Toast.makeText(
                        this@MainActivity,
                        "已加入書單「${entry.title}」（共 ${chapters.size} 章）",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this@MainActivity, "無法從壓縮檔中讀取到章節檔案", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC

        // 啟用 Edge-to-Edge 全螢幕佈局，隱藏頂部 status bar 與底部 navigation bar
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        applyImmersiveSystemBars(hide = true)

        repository = NovelRepository(this)
        progressManager = ReadingProgressManager(this)
        bookLibraryManager = BookLibraryManager(this)

        checkNotificationPermission()
        setupRecyclerView()
        setupListeners()
        bindTtsService()
        checkResumeProgress()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isImmersiveFullScreen) {
            applyImmersiveSystemBars(hide = true)
        }
    }

    override fun onResume() {
        super.onResume()
        ttsService?.setAppInForeground(true)
    }

    override fun onPause() {
        super.onPause()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = powerManager?.isInteractive ?: true
        if (isInteractive) {
            ttsService?.setAppInForeground(false)
        } else {
            ttsService?.onScreenOffFromForeground()
        }
    }

    override fun onStop() {
        super.onStop()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val isInteractive = powerManager?.isInteractive ?: true
        if (isInteractive) {
            ttsService?.setAppInForeground(false)
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.appBarLayout) { v, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
            val topPadding = maxOf(statusBar, cutout)
            v.setPadding(v.paddingLeft, topPadding, v.paddingRight, v.paddingBottom)
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomPlayerCard) { v, insets ->
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val baseMargin = (12 * resources.displayMetrics.density).toInt()
            val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
            if (lp != null) {
                lp.setMargins(baseMargin, baseMargin, baseMargin, baseMargin + navBar)
                v.layoutParams = lp
            }
            insets
        }
    }

    private fun applyImmersiveSystemBars(hide: Boolean) {
        isImmersiveFullScreen = hide
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (hide) {
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            binding.btnToggleFullScreen.setIconResource(R.drawable.ic_fullscreen)
            binding.btnToggleFullScreen.contentDescription = getString(R.string.desc_exit_fullscreen)
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            binding.btnToggleFullScreen.setIconResource(R.drawable.ic_fullscreen_exit)
            binding.btnToggleFullScreen.contentDescription = getString(R.string.desc_enter_fullscreen)
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun bindTtsService() {
        val intent = Intent(this, TtsReadingService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupRecyclerView() {
        contentAdapter = NovelContentAdapter(
            onItemClick = { uiPosition ->
                ttsService?.seekToUiPosition(uiPosition)
            },
            onPrevChapterClick = {
                ttsService?.playPreviousChapter()
            },
            onNextChapterClick = {
                ttsService?.playNextChapter()
            }
        )
        binding.rvContent.layoutManager = LinearLayoutManager(this)
        binding.rvContent.adapter = contentAdapter
    }

    private fun setupListeners() {
        // 點擊頂部章節資訊橫條直接開啟「直達指定章節」彈窗
        binding.tvCurrentChapterInfo.setOnClickListener {
            showJumpChapterDialog()
        }

        // 點擊工具列標題區域或書單按鈕皆可開啟我的書單
        binding.toolbar.setOnClickListener {
            showBookListSheet()
        }
        binding.btnBookList.setOnClickListener {
            showBookListSheet()
        }

        // 匯入小說 (GitHub 網址 / 本機 ZIP / 本機目錄)
        binding.btnImportNovel.setOnClickListener {
            showImportSheet()
        }

        // 播放 / 暫停 大按鈕
        binding.fabPlayPause.setOnClickListener {
            val service = ttsService ?: return@setOnClickListener
            if (service.playbackState == TtsReadingService.PlaybackState.PLAYING) {
                service.pause()
            } else {
                service.play()
            }
        }

        // 快速切換：上一章
        binding.btnPrevChapter.setOnClickListener {
            ttsService?.playPreviousChapter()
        }

        // 快速切換：下一章
        binding.btnNextChapter.setOnClickListener {
            ttsService?.playNextChapter()
        }

        // 上一段
        binding.btnPrevParagraph.setOnClickListener {
            ttsService?.previousSegment()
        }

        // 下一段
        binding.btnNextParagraph.setOnClickListener {
            ttsService?.nextSegment()
        }

        // 章節目錄 BottomSheet
        binding.btnChapterList.setOnClickListener {
            val service = ttsService ?: return@setOnClickListener
            if (service.chapters.isEmpty()) {
                Toast.makeText(this, "目前尚無章節，請先匯入小說", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sheet = ChapterListBottomSheetDialogFragment(
                chapters = service.chapters,
                currentIndex = service.currentChapterIndex
            ) { selectedIndex ->
                service.loadChapter(selectedIndex, autoPlay = true)
            }
            sheet.show(supportFragmentManager, "ChapterListSheet")
        }

        // 設定 BottomSheet
        binding.btnSettings.setOnClickListener {
            val service = ttsService ?: return@setOnClickListener
            val sheet = SettingsBottomSheetDialogFragment(
                settings = service.settings,
                availableVoices = availableVoices
            ) { newSettings ->
                service.updateSettings(newSettings)
                updateSettingsBadges()
            }
            sheet.show(supportFragmentManager, "SettingsSheet")
        }

        // 切換沉浸全螢幕 (隱藏 / 顯示頂部 status bar 與底部 navigation bar)
        binding.btnToggleFullScreen.setOnClickListener {
            applyImmersiveSystemBars(hide = !isImmersiveFullScreen)
        }

        // 續讀橫條按鈕
        binding.btnResumePlay.setOnClickListener {
            val progress = pendingSavedProgress ?: return@setOnClickListener
            val service = ttsService ?: return@setOnClickListener
            if (service.currentNovelId == progress.novelId && service.chapters.isNotEmpty()) {
                service.loadChapter(
                    index = progress.chapterIndex.coerceIn(service.chapters.indices),
                    autoPlay = true,
                    targetSegmentIndex = progress.segmentIndex
                )
                binding.cardResumeBanner.visibility = View.GONE
            } else {
                val targetBook = bookLibraryManager.getBookById(progress.novelId)
                if (targetBook != null) {
                    binding.cardResumeBanner.visibility = View.GONE
                    openBookEntry(targetBook, autoPlay = true)
                } else {
                    binding.cardResumeBanner.visibility = View.GONE
                }
            }
        }

        binding.btnCloseResumeBanner.setOnClickListener {
            binding.cardResumeBanner.visibility = View.GONE
        }
    }

    private fun showBookListSheet() {
        val sheet = BookListBottomSheetDialogFragment(
            getBooks = { bookLibraryManager.getBooks() },
            getCurrentBookId = { ttsService?.currentNovelId?.ifBlank { bookLibraryManager.getLastActiveBookId() } },
            onSelectBook = { entry ->
                openBookEntry(entry, autoPlay = false)
            },
            onDeleteBook = { entry ->
                handleDeleteBook(entry)
            },
            onAddBook = {
                showImportSheet()
            }
        )
        sheet.show(supportFragmentManager, "BookListSheet")
    }

    private fun showImportSheet() {
        val sheet = ImportBottomSheetDialogFragment(
            onImportSuccess = { result ->
                handleGitHubImportSuccess(result)
            },
            onWebBookImported = { result ->
                handleWebBookImportSuccess(result)
            },
            onOpenWebBrowser = { url ->
                openWebNovelBrowserForImport(url)
            },
            onPickArchive = {
                openArchiveLauncher.launch(arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/x-tar",
                    "application/gzip",
                    "application/x-gzip",
                    "*/*"
                ))
            },
            onPickDirectory = {
                openDocumentTreeLauncher.launch(null)
            }
        )
        sheet.show(supportFragmentManager, "ImportSheet")
    }

    private fun openWebNovelBrowserForImport(url: String) {
        val browser = WebNovelBrowserDialogFragment(
            initialUrl = url,
            mode = WebNovelBrowserDialogFragment.Mode.IMPORT_BOOK,
            onBookImported = { result ->
                handleWebBookImportSuccess(result)
            }
        )
        browser.show(supportFragmentManager, "WebNovelBrowser")
    }

    private fun handleWebBookImportSuccess(result: WebBookImportResult.Success) {
        val now = System.currentTimeMillis()
        val entry = BookEntry(
            id = result.bookId,
            title = result.title,
            sourceType = BookSourceType.WEB_NOVEL,
            localPath = result.localDirPath,
            sourceUriOrUrl = result.sourceUrl,
            chapterCount = result.chapters.size,
            addedAt = now,
            lastOpenedAt = now
        )
        bookLibraryManager.addOrUpdateBook(entry)
        bookLibraryManager.markOpened(entry.id)

        val saved = progressManager.getSavedProgressForBook(entry.id)
        val targetIdx = saved?.chapterIndex?.coerceIn(result.chapters.indices) ?: 0
        val targetSeg = saved?.segmentIndex ?: 0

        binding.toolbar.title = entry.title
        ttsService?.setChapterList(
            list = result.chapters,
            initialIndex = targetIdx,
            initialSegmentIndex = targetSeg,
            startPlay = true,
            novelId = entry.id,
            novelTitle = entry.title,
            webBookDir = File(result.localDirPath),
            preferWebView = result.preferWebView
        )
    }

    private fun handleGitHubImportSuccess(result: GitHubLoadResult.Success) {
        val now = System.currentTimeMillis()
        val entry = BookEntry(
            id = result.bookId,
            title = result.title,
            sourceType = if (result.isSingleChapter) BookSourceType.GITHUB_SINGLE else BookSourceType.GITHUB_TREE,
            localPath = result.localDirPath,
            sourceUriOrUrl = result.sourceUrl,
            chapterCount = result.chapters.size,
            addedAt = now,
            lastOpenedAt = now
        )
        bookLibraryManager.addOrUpdateBook(entry)
        bookLibraryManager.markOpened(entry.id)

        val saved = progressManager.getSavedProgressForBook(entry.id)
        val targetIdx = saved?.chapterIndex?.coerceIn(result.chapters.indices) ?: 0
        val targetSeg = saved?.segmentIndex ?: 0

        binding.toolbar.title = entry.title
        ttsService?.setChapterList(
            list = result.chapters,
            initialIndex = targetIdx,
            initialSegmentIndex = targetSeg,
            startPlay = true,
            novelId = entry.id,
            novelTitle = entry.title
        )
    }

    private fun handleDeleteBook(entry: BookEntry) {
        val wasCurrent = (ttsService?.currentNovelId == entry.id)
        bookLibraryManager.deleteBook(entry.id)
        progressManager.clearProgressForBook(entry.id)

        if (pendingSavedProgress?.novelId == entry.id) {
            pendingSavedProgress = null
            binding.cardResumeBanner.visibility = View.GONE
        }

        Toast.makeText(this, "已從書單刪除「${entry.title}」", Toast.LENGTH_SHORT).show()

        if (wasCurrent) {
            val remaining = bookLibraryManager.getBooks()
            if (remaining.isNotEmpty()) {
                openBookEntry(remaining.first(), autoPlay = false)
            } else {
                ttsService?.clearChapters()
                showEmptyLibraryState()
            }
        }
    }

    private fun showEmptyLibraryState() {
        contentAdapter.clearContent()
        binding.toolbar.title = getString(R.string.app_name)
        binding.toolbar.subtitle = "書單為空"
        binding.tvCurrentChapterInfo.text = "書單目前為空，請點擊右上角匯入小說"
        binding.tvSpeakingStatus.text = "點擊右上角書單或匯入按鈕新增小說"
        updatePlaybackControls(TtsReadingService.PlaybackState.IDLE)
    }

    private fun showJumpChapterDialog() {
        val service = ttsService ?: return
        if (service.chapters.isEmpty()) return
        val sheet = JumpChapterBottomSheetDialogFragment(
            chapters = service.chapters,
            currentChapterIndex = service.currentChapterIndex
        ) { targetIndex ->
            service.loadChapter(targetIndex, autoPlay = true)
        }
        sheet.show(supportFragmentManager, "JumpChapterSheet")
    }

    private fun checkResumeProgress() {
        val saved = progressManager.getSavedProgress() ?: return
        val hasBookInLibrary = !bookLibraryManager.hasInitializedBuiltins() ||
            bookLibraryManager.getBookById(saved.novelId) != null
        if (hasBookInLibrary) {
            pendingSavedProgress = saved
            binding.cardResumeBanner.visibility = View.VISIBLE
            val bookPrefix = if (saved.novelTitle.isNotBlank()) "《${saved.novelTitle}》" else ""
            binding.tvResumeProgressText.text =
                "上次朗讀至：$bookPrefix 第 ${saved.chapterNumber} 章 ${saved.chapterTitle} · 段落 ${saved.uiPosition + 1}"
        }
    }

    private fun loadInitialBookFromLibrary() {
        lifecycleScope.launch {
            // 首次啟動：將預設內建小說加入書單（書名動態取自 book.yaml 或檔名）
            if (!bookLibraryManager.hasInitializedBuiltins() && bookLibraryManager.getBooks().isEmpty()) {
                val assetPath = "novels/yuan.zip"
                val chapters = repository.loadBuiltinNovelChapters(assetPath)
                bookLibraryManager.setInitializedBuiltins(true)
                if (chapters.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val entry = BookEntry(
                        id = repository.currentNovelId,
                        title = repository.currentNovelTitle,
                        sourceType = BookSourceType.BUILTIN_ASSET,
                        localPath = repository.currentNovelLocalPath,
                        sourceUriOrUrl = assetPath,
                        chapterCount = chapters.size,
                        addedAt = now,
                        lastOpenedAt = now
                    )
                    bookLibraryManager.addOrUpdateBook(entry)
                    bookLibraryManager.markOpened(entry.id)

                    val saved = progressManager.getSavedProgressForBook(entry.id)
                    val targetChapterIndex = saved?.chapterIndex?.coerceIn(chapters.indices) ?: 0
                    val targetSegmentIndex = saved?.segmentIndex ?: 0

                    binding.toolbar.title = entry.title
                    ttsService?.setChapterList(
                        list = chapters,
                        initialIndex = targetChapterIndex,
                        initialSegmentIndex = targetSegmentIndex,
                        startPlay = false,
                        novelId = entry.id,
                        novelTitle = entry.title
                    )
                    return@launch
                }
            }

            // 載入書單中最後閱讀的書籍
            val lastId = bookLibraryManager.getLastActiveBookId()
            val targetBook = lastId?.let { bookLibraryManager.getBookById(it) }
                ?: bookLibraryManager.getBooks().firstOrNull()

            if (targetBook != null) {
                openBookEntry(targetBook, autoPlay = false)
            } else {
                showEmptyLibraryState()
            }
        }
    }

    private fun openBookEntry(entry: BookEntry, autoPlay: Boolean) {
        lifecycleScope.launch {
            var webBookDir: File? = null
            var preferWebView = false

            val chapters = when (entry.sourceType) {
                BookSourceType.BUILTIN_ASSET -> {
                    val assetPath = entry.sourceUriOrUrl ?: "novels/yuan.zip"
                    repository.loadBuiltinNovelChapters(assetPath, entry.id)
                }
                BookSourceType.GITHUB_TREE,
                BookSourceType.GITHUB_SINGLE,
                BookSourceType.LOCAL_ARCHIVE -> {
                    val localPath = entry.localPath
                    if (!localPath.isNullOrBlank()) {
                        repository.loadLocalDirectoryChapters(File(localPath), entry.id, entry.title)
                    } else emptyList()
                }
                BookSourceType.LOCAL_DIRECTORY -> {
                    val uriStr = entry.sourceUriOrUrl
                    if (!uriStr.isNullOrBlank()) {
                        try {
                            repository.loadCustomDirectoryChapters(Uri.parse(uriStr))
                        } catch (_: Exception) {
                            emptyList()
                        }
                    } else emptyList()
                }
                BookSourceType.WEB_NOVEL -> {
                    val localPath = entry.localPath
                    if (!localPath.isNullOrBlank()) {
                        val dir = File(localPath)
                        val stored = repository.loadWebBookChapters(dir, entry.id)
                        if (stored != null) {
                            webBookDir = dir
                            preferWebView = stored.preferWebView
                            stored.chapters
                        } else emptyList()
                    } else emptyList()
                }
            }

            if (chapters.isEmpty()) {
                Toast.makeText(
                    this@MainActivity,
                    "無法開啟「${entry.title}」，檔案可能已遺失或權限過期",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val resolvedTitle = repository.currentNovelTitle.ifBlank { entry.title }
            val updatedEntry = entry.copy(
                title = resolvedTitle,
                chapterCount = chapters.size,
                localPath = repository.currentNovelLocalPath ?: entry.localPath,
                lastOpenedAt = System.currentTimeMillis()
            )
            bookLibraryManager.addOrUpdateBook(updatedEntry)
            bookLibraryManager.markOpened(updatedEntry.id)

            val saved = progressManager.getSavedProgressForBook(updatedEntry.id)
            val targetChapterIndex = saved?.chapterIndex?.coerceIn(chapters.indices) ?: 0
            val targetSegmentIndex = saved?.segmentIndex ?: 0

            binding.toolbar.title = resolvedTitle
            ttsService?.setChapterList(
                list = chapters,
                initialIndex = targetChapterIndex,
                initialSegmentIndex = targetSegmentIndex,
                startPlay = autoPlay,
                novelId = updatedEntry.id,
                novelTitle = resolvedTitle,
                webBookDir = webBookDir,
                preferWebView = preferWebView
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex != -1) {
                cursor.getString(nameIndex)
            } else null
        }
    }

    private fun updateSettingsBadges() {
        val service = ttsService ?: return
        val settings = service.settings

        val modeText = if (settings.filterConfig.readChapterTitle) {
            getString(R.string.mode_title_and_body)
        } else {
            getString(R.string.mode_body_only)
        }
        binding.chipFilterBadge.text = modeText

        val genderStr = when (settings.gender) {
            VoiceGender.MALE -> "男聲"
            VoiceGender.FEMALE -> "女聲"
            VoiceGender.DEFAULT -> "預設"
        }
        val autoNextStr = if (settings.autoNextChapter) "連播" else "單章"
        binding.tvVoiceInfo.text = String.format("%.2fx · %s · %s", settings.speechRate, genderStr, autoNextStr)
    }

    private fun updatePlaybackControls(state: TtsReadingService.PlaybackState) {
        val icon = if (state == TtsReadingService.PlaybackState.PLAYING) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        binding.fabPlayPause.setImageResource(icon)

        val service = ttsService
        if (service != null) {
            binding.btnPrevChapter.isEnabled = service.currentChapterIndex > 0
            binding.btnNextChapter.isEnabled = service.currentChapterIndex < service.chapters.size - 1
        } else {
            binding.btnPrevChapter.isEnabled = false
            binding.btnNextChapter.isEnabled = false
        }
    }

    // --- TtsReadingService.PlaybackListener 回呼 ---

    override fun onPlaybackStateChanged(state: TtsReadingService.PlaybackState) {
        runOnUiThread {
            updatePlaybackControls(state)
            when (state) {
                TtsReadingService.PlaybackState.PLAYING -> {
                    binding.cardResumeBanner.visibility = View.GONE
                }
                TtsReadingService.PlaybackState.PAUSED -> {
                    binding.tvSpeakingStatus.text = "朗讀已暫停，點擊播放繼續"
                }
                TtsReadingService.PlaybackState.IDLE -> {
                    if (ttsService?.chapters.isNullOrEmpty()) {
                        binding.tvSpeakingStatus.text = "點擊右上角書單或匯入按鈕新增小說"
                    } else {
                        binding.tvSpeakingStatus.text = "準備就緒，點擊播放開始朗讀"
                    }
                    contentAdapter.setActiveUiPosition(-1)
                }
            }
        }
    }

    override fun onChapterLoaded(chapter: Chapter, content: ChapterContent, segments: List<TtsSegment>, initialSegmentIndex: Int) {
        runOnUiThread {
            val service = ttsService
            val hasPrev = (service?.currentChapterIndex ?: 0) > 0
            val hasNext = (service?.currentChapterIndex ?: 0) < (service?.chapters?.size ?: 0) - 1

            val activeTitle = service?.currentNovelTitle
            if (!activeTitle.isNullOrBlank()) {
                binding.toolbar.title = activeTitle
            }

            binding.tvCurrentChapterInfo.text = "${chapter.volumeName} · 第 ${chapter.chapterNumber} 章 ${content.title}  (點擊直達)"
            binding.toolbar.subtitle = "${chapter.volumeName} (第 ${chapter.chapterNumber}/${service?.chapters?.size ?: 0} 章)"
            contentAdapter.setContent(content, hasPrev = hasPrev, hasNext = hasNext)

            if (initialSegmentIndex in segments.indices) {
                val targetSegment = segments[initialSegmentIndex]
                contentAdapter.setActiveUiPosition(targetSegment.uiPosition)
                val itemIdx = contentAdapter.getItemIndexByUiPosition(targetSegment.uiPosition)
                if (itemIdx != -1) {
                    binding.rvContent.scrollToPosition(itemIdx)
                }
            } else {
                binding.rvContent.scrollToPosition(0)
            }
            updateSettingsBadges()
        }
    }

    override fun onSegmentChanged(segmentIndex: Int, segment: TtsSegment?) {
        runOnUiThread {
            if (segment != null) {
                contentAdapter.setActiveUiPosition(segment.uiPosition)
                val preview = segment.displayText.replace("\n", " ").trim()
                binding.tvSpeakingStatus.text = "正在朗讀：$preview"

                val itemIdx = contentAdapter.getItemIndexByUiPosition(segment.uiPosition)
                if (itemIdx != -1) {
                    val lm = binding.rvContent.layoutManager as? LinearLayoutManager
                    val firstVisible = lm?.findFirstVisibleItemPosition() ?: 0
                    val lastVisible = lm?.findLastVisibleItemPosition() ?: 0
                    if (itemIdx !in (firstVisible + 1)..(lastVisible - 1)) {
                        binding.rvContent.smoothScrollToPosition(itemIdx)
                    }
                }
            } else {
                contentAdapter.setActiveUiPosition(-1)
            }
        }
    }

    override fun onTtsReady(availableVoices: List<Voice>) {
        runOnUiThread {
            this.availableVoices = availableVoices
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val service = ttsService

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1

            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (service != null) {
                    service.handleVolumeAction(direction, fromHardwareKey = true)
                    return true
                }
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onProgressActionNotification(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onWebChapterVerificationRequired(url: String, chapterIndex: Int, chapter: Chapter) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (supportFragmentManager.findFragmentByTag("WebChapterVerify") != null) return@runOnUiThread
            Toast.makeText(this, "網站需要瀏覽器驗證，請於內建防廣告畫面確認後自動接續朗讀", Toast.LENGTH_SHORT).show()
            val browser = WebNovelBrowserDialogFragment(
                initialUrl = url,
                mode = WebNovelBrowserDialogFragment.Mode.VERIFY_CHAPTER,
                onChapterHtmlReady = { html, finalUrl ->
                    ttsService?.onWebChapterHtmlVerified(chapterIndex, html, finalUrl, autoPlay = true)
                }
            )
            browser.show(supportFragmentManager, "WebChapterVerify")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            ttsService?.setAppInForeground(false)
            ttsService?.stopAndRelease()
        }
        if (isBound) {
            ttsService?.removeListener(this)
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
