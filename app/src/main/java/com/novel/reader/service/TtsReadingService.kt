package com.novel.reader.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.novel.reader.R
import com.novel.reader.model.*
import com.novel.reader.parser.NovelChapterParser
import com.novel.reader.repository.NovelRepository
import com.novel.reader.ui.MainActivity
import com.novel.reader.util.FastVolumeController
import com.novel.reader.util.ReadingProgressManager
import kotlinx.coroutines.*
import java.util.*

class TtsReadingService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val CHANNEL_ID = "novel_tts_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.novel.reader.ACTION_PLAY"
        const val ACTION_PAUSE = "com.novel.reader.ACTION_PAUSE"
        const val ACTION_PREV_CHAPTER = "com.novel.reader.ACTION_PREV_CHAPTER"
        const val ACTION_NEXT_CHAPTER = "com.novel.reader.ACTION_NEXT_CHAPTER"
        const val ACTION_STOP = "com.novel.reader.ACTION_STOP"

        val TAIWAN_LOCALE = Locale("zh", "TW")
    }

    enum class PlaybackState {
        IDLE, PLAYING, PAUSED
    }

    inner class LocalBinder : Binder() {
        fun getService(): TtsReadingService = this@TtsReadingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var tts: TextToSpeech? = null
    var isTtsReady = false
        private set

    lateinit var repository: NovelRepository
        private set
    lateinit var progressManager: ReadingProgressManager
        private set
    lateinit var audioManager: AudioManager
        private set
    lateinit var mediaSession: MediaSessionCompat
        private set

    var isAppInForeground: Boolean = false
        private set
    var wasForegroundBeforeScreenOff: Boolean = false
        private set
    private var isDreamingState: Boolean = false
    private var isMediaSessionReleased = false
    private var isScreenReceiverRegistered = false
    private var isVolumeReceiverRegistered = false

    lateinit var fastVolumeController: FastVolumeController
        private set

    private var lastHardwareKeyDispatchTime = 0L
    private var baselineMusicVolume: Int = -1
    private var isRestoringVolume: Boolean = false

    /**
     * 判定當前閱讀器運作環境狀態（四分法）：
     * 1. SCREEN_ON_FOREGROUND  : 螢幕開啟且 Reader 在前景 (可見且互動中)
     * 2. SCREEN_ON_BACKGROUND  : 螢幕開啟且 Reader 在背景 (使用者在其他 App 或桌面)
     * 3. SCREEN_OFF_FOREGROUND : 螢幕關閉且 Reader 息屏前在前景 (放口袋/鎖屏聆聽模式)
     * 4. SCREEN_OFF_BACKGROUND : 螢幕關閉且 Reader 在背景 (在其他 App 時關閉螢幕)
     */
    fun getEnvironmentState(): ReaderEnvironmentState {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager

        val isInteractive = powerManager?.isInteractive ?: true
        val isKeyguardLocked = keyguardManager?.isKeyguardLocked ?: false
        val isScreenOffOrLocked = !isInteractive || isKeyguardLocked || isDreamingState

        return if (isScreenOffOrLocked) {
            if (wasForegroundBeforeScreenOff) {
                ReaderEnvironmentState.SCREEN_OFF_FOREGROUND
            } else {
                ReaderEnvironmentState.SCREEN_OFF_BACKGROUND
            }
        } else {
            if (isAppInForeground) {
                ReaderEnvironmentState.SCREEN_ON_FOREGROUND
            } else {
                ReaderEnvironmentState.SCREEN_ON_BACKGROUND
            }
        }
    }

    /**
     * 相容性方法：檢查是否允許切換朗讀進度（僅前景/息屏聆聽模式允許）
     */
    fun canHandleMediaButtons(): Boolean {
        if (isMediaSessionReleased) return false
        return getEnvironmentState().allowsProgressChange
    }

    fun setAppInForeground(inForeground: Boolean) {
        Log.d("NovelTts", "setAppInForeground: $inForeground (wasFgBeforeScreenOff=$wasForegroundBeforeScreenOff)")
        isAppInForeground = inForeground
        if (inForeground) {
            wasForegroundBeforeScreenOff = false
        } else {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive == true) {
                // 螢幕仍亮著時離開前景（切去其他 App 或回到桌面），非息屏
                wasForegroundBeforeScreenOff = false
            }
        }
        updateActiveState()
    }

    fun onScreenOffFromForeground() {
        Log.d("NovelTts", "onScreenOffFromForeground: screen turned off or dream while in foreground")
        isAppInForeground = false
        wasForegroundBeforeScreenOff = true
        updateActiveState()
    }

    fun updateActiveState() {
        updateMediaSessionActiveState()
        // 只有在前景（螢幕開啟前景或息屏聆聽模式）且有播放/暫停狀態時才需要 volume receiver
        if (playbackState != PlaybackState.IDLE && getEnvironmentState().allowsFastVolumeControl) {
            registerVolumeReceiverIfNeeded()
        } else {
            unregisterVolumeReceiver()
        }
    }

    private fun updateMediaSessionActiveState() {
        if (!::mediaSession.isInitialized || isMediaSessionReleased) return
        // 只要不是完全停止/閒置 (IDLE)，就保持 MediaSession 活躍，讓背景環境也能順利接收 Play/Pause
        val shouldBeActive = playbackState != PlaybackState.IDLE
        if (mediaSession.isActive != shouldBeActive) {
            mediaSession.isActive = shouldBeActive
            Log.d("NovelTts", "mediaSession.isActive set to: $shouldBeActive (env=${getEnvironmentState()})")
        }
    }

    private fun registerVolumeReceiverIfNeeded() {
        if (isVolumeReceiverRegistered) return
        try {
            val volumeFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(volumeChangeReceiver, volumeFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(volumeChangeReceiver, volumeFilter)
            }
            isVolumeReceiverRegistered = true
            Log.d("NovelTts", "volumeChangeReceiver registered")
        } catch (e: Exception) {
            Log.e("NovelTts", "Failed to register volumeChangeReceiver", e)
        }
    }

    private fun unregisterVolumeReceiver() {
        if (!isVolumeReceiverRegistered) return
        try {
            unregisterReceiver(volumeChangeReceiver)
            Log.d("NovelTts", "volumeChangeReceiver unregistered")
        } catch (e: Exception) {
            Log.e("NovelTts", "Failed to unregister volumeChangeReceiver", e)
        }
        isVolumeReceiverRegistered = false
        if (::fastVolumeController.isInitialized) fastVolumeController.reset()
        baselineMusicVolume = -1
    }

    private fun releaseMediaSession() {
        if (::mediaSession.isInitialized && !isMediaSessionReleased) {
            try {
                mediaSession.isActive = false
                mediaSession.release()
                Log.d("NovelTts", "mediaSession released")
            } catch (e: Exception) {
                Log.e("NovelTts", "Error releasing mediaSession", e)
            }
            isMediaSessionReleased = true
        }
    }

    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            Log.d("NovelTts", "screenStateReceiver: action=$action")
            when (action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isDreamingState = false
                    if (isAppInForeground) {
                        wasForegroundBeforeScreenOff = true
                        isAppInForeground = false
                    }
                    updateActiveState()
                }
                Intent.ACTION_SCREEN_ON -> {
                    updateActiveState()
                }
                Intent.ACTION_USER_PRESENT -> {
                    isDreamingState = false
                    if (!isAppInForeground) {
                        wasForegroundBeforeScreenOff = false
                    }
                    updateActiveState()
                }
                "android.intent.action.DREAMING_STARTED" -> {
                    isDreamingState = true
                    if (isAppInForeground) {
                        wasForegroundBeforeScreenOff = true
                        isAppInForeground = false
                    }
                    updateActiveState()
                }
                "android.intent.action.DREAMING_STOPPED" -> {
                    isDreamingState = false
                    updateActiveState()
                }
            }
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNoisyReceiverRegistered = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                if (playbackState == PlaybackState.PLAYING) {
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (playbackState == PlaybackState.PLAYING) {
                    pause()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 音訊焦點恢復
            }
        }
    }

    // 當耳機被拔出或藍牙耳機斷開時，自動暫停朗讀避免外放擴音
    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                if (playbackState == PlaybackState.PLAYING) {
                    pause()
                }
            }
        }
    }

    // 監聽藍牙耳機及系統音量變更動作
    private var lastChapterSwitchTime = 0L

    private val volumeChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != "android.media.VOLUME_CHANGED_ACTION") return
            if (isRestoringVolume) {
                Log.d("NovelTts", "volumeChangeReceiver: Ignoring VOLUME_CHANGED_ACTION during volume restoration")
                return
            }

            val now = System.currentTimeMillis()
            if (now - lastHardwareKeyDispatchTime < 250) {
                Log.d("NovelTts", "volumeChangeReceiver: Ignoring VOLUME_CHANGED_ACTION from recent hardware key dispatch")
                return
            }

            val env = getEnvironmentState()
            if (!env.allowsFastVolumeControl) {
                Log.d("NovelTts", "volumeChangeReceiver: Ignoring because env is $env (no progress changes in background)")
                return
            }

            val streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
            if (streamType == AudioManager.STREAM_MUSIC) {
                val currentVol = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                val prevVol = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                val direction = when {
                    currentVol > prevVol -> 1
                    currentVol < prevVol -> -1
                    currentVol == maxVol && prevVol == maxVol -> 1
                    currentVol == 0 && prevVol == 0 -> -1
                    else -> 0
                }

                Log.d("NovelTts", "VOLUME_CHANGED: curr=$currentVol, prev=$prevVol, dir=$direction, env=$env")

                if (direction != 0) {
                    if (fastVolumeController.clickCount == 0 && prevVol >= 0) {
                        baselineMusicVolume = prevVol
                    }
                    fastVolumeController.onVolumeEvent(direction)
                }
            }
        }
    }

    /**
     * 處理音量按鍵或變更事件
     * @param direction +1 為增加/前進，-1 為減少/後退
     * @param fromHardwareKey 是否為實體按鍵（來自 Activity 的 dispatchKeyEvent）
     */
    fun handleVolumeAction(direction: Int, fromHardwareKey: Boolean = false) {
        if (direction != 1 && direction != -1) return
        val env = getEnvironmentState()
        Log.d("NovelTts", "handleVolumeAction: direction=$direction, fromHardwareKey=$fromHardwareKey, env=$env")

        if (!env.allowsFastVolumeControl) {
            Log.d("NovelTts", "handleVolumeAction ignored because env is $env")
            return
        }

        if (fromHardwareKey) {
            lastHardwareKeyDispatchTime = System.currentTimeMillis()
            if (fastVolumeController.clickCount == 0) {
                baselineMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction == 1) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        } else {
            if (fastVolumeController.clickCount == 0) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val prevVol = currentVol - direction
                baselineMusicVolume = prevVol.coerceIn(0, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
            }
        }

        fastVolumeController.onVolumeEvent(direction)
    }

    private fun restoreVolumeBeforeBurst() {
        if (baselineMusicVolume >= 0) {
            val target = baselineMusicVolume
            isRestoringVolume = true
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                Log.d("NovelTts", "Restored volume to baseline: $target")
            } catch (e: Exception) {
                Log.e("NovelTts", "Failed to restore volume", e)
            } finally {
                serviceScope.launch {
                    delay(150)
                    isRestoringVolume = false
                }
            }
        }
    }

    private fun vibrateFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Vibrator::class.java)
                vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(60)
            }
        } catch (e: Exception) {
            Log.e("NovelTts", "Error in vibrateFeedback", e)
        }
    }

    private fun notifyProgressAction(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            listeners.forEach { it.onProgressActionNotification(message) }
        }
    }

    var chapters: List<Chapter> = emptyList()
        private set
    var currentChapterIndex: Int = 0
        private set
    var currentContent: ChapterContent? = null
        private set
    var currentSegments: List<TtsSegment> = emptyList()
        private set
    var currentSegmentIndex: Int = 0
        private set

    var playbackState: PlaybackState = PlaybackState.IDLE
        private set

    var settings: ReadingSettings = ReadingSettings()
        private set

    // 回呼介面供 UI 即時更新狀態
    interface PlaybackListener {
        fun onPlaybackStateChanged(state: PlaybackState)
        fun onChapterLoaded(chapter: Chapter, content: ChapterContent, segments: List<TtsSegment>, initialSegmentIndex: Int)
        fun onSegmentChanged(segmentIndex: Int, segment: TtsSegment?)
        fun onTtsReady(availableVoices: List<Voice>)
        fun onProgressActionNotification(message: String) {}  // 段落/章節切換的 Toast 提示（有默認實作，不強制覆寫）
    }

    private val listeners = mutableListOf<PlaybackListener>()

    fun addListener(listener: PlaybackListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            if (isTtsReady) {
                listener.onTtsReady(getAvailableChineseVoices())
            }
            val content = currentContent
            if (content != null && currentChapterIndex in chapters.indices) {
                listener.onChapterLoaded(chapters[currentChapterIndex], content, currentSegments, currentSegmentIndex)
            }
            listener.onPlaybackStateChanged(playbackState)
            if (currentSegmentIndex in currentSegments.indices) {
                listener.onSegmentChanged(currentSegmentIndex, currentSegments[currentSegmentIndex])
            }
        }
    }

    fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        repository = NovelRepository(applicationContext)
        progressManager = ReadingProgressManager(applicationContext)
        settings = progressManager.loadSettings()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        createNotificationChannel()
        setupMediaSession()
        setupFastVolumeController()
        tts = TextToSpeech(applicationContext, this)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction("android.intent.action.DREAMING_STARTED")
            addAction("android.intent.action.DREAMING_STOPPED")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenStateReceiver, screenFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenStateReceiver, screenFilter)
            }
            isScreenReceiverRegistered = true
        } catch (e: Exception) {
            Log.e("NovelTts", "Failed to register screenStateReceiver", e)
        }
    }

    private var silentAudioTrack: AudioTrack? = null

    @Synchronized
    private fun startSilentAudio() {
        if (silentAudioTrack != null && silentAudioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
            return
        }
        try {
            if (silentAudioTrack == null) {
                val sampleRate = 44100
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufSize = maxOf(minBufferSize, 4096)
                val buffer = ByteArray(bufSize) // 全部填 0，純靜音
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buffer, 0, buffer.size)
                track.setLoopPoints(0, buffer.size / 2, -1) // 無限循環靜音 PCM
                silentAudioTrack = track
            }
            silentAudioTrack?.play()
            Log.d("NovelTts", "startSilentAudio: Silent AudioTrack is PLAYING (UID ${android.os.Process.myUid()})")
        } catch (e: Exception) {
            Log.e("NovelTts", "startSilentAudio error", e)
        }
    }

    @Synchronized
    private fun pauseSilentAudio() {
        try {
            silentAudioTrack?.pause()
            Log.d("NovelTts", "pauseSilentAudio: Silent AudioTrack PAUSED")
        } catch (e: Exception) {
            Log.e("NovelTts", "pauseSilentAudio error", e)
        }
    }

    @Synchronized
    private fun stopSilentAudio() {
        try {
            silentAudioTrack?.pause()
            silentAudioTrack?.flush()
            silentAudioTrack?.stop()
            silentAudioTrack?.release()
            Log.d("NovelTts", "stopSilentAudio: Silent AudioTrack released")
        } catch (e: Exception) {
            Log.e("NovelTts", "stopSilentAudio error", e)
        } finally {
            silentAudioTrack = null
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "NovelReaderMediaSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                // =====================================================
                // Play / Pause / Stop：前景 + 背景都允許
                // =====================================================
                override fun onPlay() {
                    Log.d("NovelTts", "MediaSession: onPlay() called, env=${getEnvironmentState()}")
                    this@TtsReadingService.play()
                }

                override fun onPause() {
                    Log.d("NovelTts", "MediaSession: onPause() called, env=${getEnvironmentState()}")
                    this@TtsReadingService.pause()
                }

                override fun onStop() {
                    Log.d("NovelTts", "MediaSession: onStop() called, env=${getEnvironmentState()}")
                    this@TtsReadingService.stop()
                }

                // =====================================================
                // 進度跳轉類（下一章/上一章/FF/Rewind）：僅前景允許
                // =====================================================
                override fun onSkipToNext() {
                    val env = getEnvironmentState()
                    if (env.isBackground) {
                        Log.d("NovelTts", "MediaSession: onSkipToNext() blocked in $env")
                        return
                    }
                    Log.d("NovelTts", "MediaSession: onSkipToNext() called")
                    this@TtsReadingService.playNextChapter()
                }

                override fun onSkipToPrevious() {
                    val env = getEnvironmentState()
                    if (env.isBackground) {
                        Log.d("NovelTts", "MediaSession: onSkipToPrevious() blocked in $env")
                        return
                    }
                    Log.d("NovelTts", "MediaSession: onSkipToPrevious() called")
                    this@TtsReadingService.playPreviousChapter()
                }

                override fun onFastForward() {
                    val env = getEnvironmentState()
                    if (env.isBackground) {
                        Log.d("NovelTts", "MediaSession: onFastForward() blocked in $env")
                        return
                    }
                    Log.d("NovelTts", "MediaSession: onFastForward() called")
                    this@TtsReadingService.playNextChapter()
                }

                override fun onRewind() {
                    val env = getEnvironmentState()
                    if (env.isBackground) {
                        Log.d("NovelTts", "MediaSession: onRewind() blocked in $env")
                        return
                    }
                    Log.d("NovelTts", "MediaSession: onRewind() called")
                    this@TtsReadingService.playPreviousChapter()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val env = getEnvironmentState()
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    Log.d("NovelTts", "MediaSession: onMediaButtonEvent keyEvent=$keyEvent, env=$env")
                    if (keyEvent != null && keyEvent.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            // ---- 播放/暫停：前景+背景都允許 ----
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_PLAY")
                                play()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_PAUSE")
                                pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_PLAY_PAUSE / HEADSETHOOK")
                                if (playbackState == PlaybackState.PLAYING) pause() else play()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_STOP -> {
                                Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_STOP")
                                stop()
                                return true
                            }
                            // ---- 進度切換：僅前景允許 ----
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                                if (env.isBackground) {
                                    Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_NEXT blocked in $env")
                                    return true  // 消耗事件、不做任何事
                                }
                                Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_NEXT")
                                playNextChapter()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                            KeyEvent.KEYCODE_MEDIA_REWIND,
                            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                                if (env.isBackground) {
                                    Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_PREVIOUS blocked in $env")
                                    return true  // 消耗事件、不做任何事
                                }
                                Log.d("NovelTts", "MediaSession: KEYCODE_MEDIA_PREVIOUS")
                                playPreviousChapter()
                                return true
                            }
                            // ---- 音量鍵長按：在前景時由 FastVolumeController 統一處理（舊邏輯移除）----
                            // 音量鍵在背景時不觸發任何進度操作
                            KeyEvent.KEYCODE_VOLUME_UP -> {
                                return false  // 交給系統處理（正常調音量）
                            }
                            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                                return false  // 交給系統處理（正常調音量）
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            isActive = false
        }
        updateMediaSessionPlaybackState()
    }

    private fun setupFastVolumeController() {
        fastVolumeController = FastVolumeController(
            windowMs = 500L,
            onParagraphForward = {
                Log.d("NovelTts", "FastVolumeController: paragraph forward")
                restoreVolumeBeforeBurst()
                vibrateFeedback()
                notifyProgressAction("⏩ 前進一段落")
                nextSegment()
            },
            onParagraphBackward = {
                Log.d("NovelTts", "FastVolumeController: paragraph backward")
                restoreVolumeBeforeBurst()
                vibrateFeedback()
                notifyProgressAction("⏪ 後退一段落")
                previousSegment()
            },
            onChapterForward = {
                Log.d("NovelTts", "FastVolumeController: chapter forward")
                restoreVolumeBeforeBurst()
                vibrateFeedback()
                notifyProgressAction("⏭ 前進一章")
                playNextChapter()
            },
            onChapterBackward = {
                Log.d("NovelTts", "FastVolumeController: chapter backward")
                restoreVolumeBeforeBurst()
                vibrateFeedback()
                notifyProgressAction("⏮ 後退一章")
                playPreviousChapter()
            },
            onSingleVolumeClick = { direction ->
                // 單次按鍵：只調音量，不更改進度（已由 MainActivity.dispatchKeyEvent 調整過，此處僅記 log）
                Log.d("NovelTts", "FastVolumeController: single volume click dir=$direction, no progress change")
            }
        )
    }

    fun updateMediaSessionPlaybackState() {
        if (!::mediaSession.isInitialized || isMediaSessionReleased) return
        val state = when (playbackState) {
            PlaybackState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            PlaybackState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            PlaybackState.IDLE -> PlaybackStateCompat.STATE_STOPPED
        }
        val playbackStateCompat = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackStateCompat)

        val chapter = chapters.getOrNull(currentChapterIndex)
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, chapter?.title ?: "小說閱讀")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, repository.currentNovelTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "第 ${currentChapterIndex + 1} 章")
            .build()
        mediaSession.setMetadata(metadata)
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            registerReceiver(becomingNoisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            isNoisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(becomingNoisyReceiver)
            } catch (_: Exception) {}
            isNoisyReceiverRegistered = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 設定 TTS AudioAttributes
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts?.setAudioAttributes(audioAttributes)

            // 預設強制使用台灣繁體中文 (zh-TW)
            val langResult = tts?.setLanguage(TAIWAN_LOCALE)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.TRADITIONAL_CHINESE)
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    serviceScope.launch {
                        handleUtteranceDone()
                    }
                }

                override fun onError(utteranceId: String?) {
                    serviceScope.launch {
                        handleUtteranceDone()
                    }
                }
            })
            isTtsReady = true

            // 預設自動綁定台灣發音人
            selectDefaultTaiwanVoice()
            applyTtsParameters()
            notifyTtsReady()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("NovelTts", "onStartCommand: action=${intent?.action}, intent=$intent")
        if (intent != null) {
            val isMediaButton = intent.action == Intent.ACTION_MEDIA_BUTTON || intent.hasExtra(Intent.EXTRA_KEY_EVENT)
            if (isMediaButton) {
                // 媒體按鈕：只要 MediaSession 有效就轉發，讓 MediaSession Callback 決定是否允許（已區分前/背景）
                if (::mediaSession.isInitialized && !isMediaSessionReleased) {
                    Log.d("NovelTts", "onStartCommand: forwarding MediaButton to MediaSession")
                    MediaButtonReceiver.handleIntent(mediaSession, intent)
                } else {
                    Log.d("NovelTts", "onStartCommand: MediaSession not ready, ignoring media button")
                }
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            // 通知列章節切換按鈕：無論前/背景都允許（使用者主動點選通知列）
            ACTION_PREV_CHAPTER -> playPreviousChapter()
            ACTION_NEXT_CHAPTER -> playNextChapter()
            ACTION_STOP -> stop()
        }
        return START_NOT_STICKY
    }

    /**
     * 載入並設定章節清單，並可指定初始章節與段落
     */
    fun setChapterList(
        list: List<Chapter>,
        initialIndex: Int = 0,
        initialSegmentIndex: Int = 0,
        startPlay: Boolean = false
    ) {
        chapters = list
        loadChapter(initialIndex, startPlay, initialSegmentIndex)
    }

    /**
     * 載入特定章節
     */
    fun loadChapter(index: Int, autoPlay: Boolean = false, targetSegmentIndex: Int = 0) {
        if (index !in chapters.indices) return
        currentChapterIndex = index
        val chapter = chapters[index]

        serviceScope.launch {
            val content = repository.loadChapterContent(chapter)
            currentContent = content
            regenerateSegments()
            currentSegmentIndex = targetSegmentIndex.coerceIn(0, maxOf(0, currentSegments.size - 1))

            notifyChapterLoaded(chapter, content, currentSegments, currentSegmentIndex)
            saveCurrentProgress()
            updateMediaSessionPlaybackState()
            updateNotification()

            if (autoPlay) {
                play()
            } else if (playbackState == PlaybackState.PLAYING) {
                speakCurrentSegment()
            }
        }
    }

    /**
     * 重新根據 FilterConfig 計算要朗讀的 segments
     */
    fun regenerateSegments() {
        val content = currentContent ?: return
        currentSegments = NovelChapterParser.buildTtsSegments(content, settings.filterConfig)
        if (currentSegmentIndex >= currentSegments.size) {
            currentSegmentIndex = 0
        }
    }

    /**
     * 開始 / 繼續朗讀
     */
    fun play() {
        if (currentSegments.isEmpty()) {
            regenerateSegments()
        }
        if (currentSegments.isEmpty()) return

        requestAudioFocus()
        startSilentAudio()
        registerNoisyReceiver()
        playbackState = PlaybackState.PLAYING
        updateMediaSessionActiveState()
        registerVolumeReceiverIfNeeded()

        startForeground(NOTIFICATION_ID, buildNotification())
        notifyPlaybackState(playbackState)
        updateMediaSessionPlaybackState()
        speakCurrentSegment()
    }

    /**
     * 暫停朗讀 (儲存進度)
     */
    fun pause() {
        playbackState = PlaybackState.PAUSED
        tts?.stop()
        pauseSilentAudio()
        updateMediaSessionActiveState()
        // 暫停時仍保持 volumeChangeReceiver 有效（讓使用者在鎖屏暫停後還能用耳機恢復播放）
        saveCurrentProgress()
        notifyPlaybackState(playbackState)
        updateMediaSessionPlaybackState()
        updateNotification()
        unregisterNoisyReceiver()
    }

    /**
     * 停止朗讀
     */
    fun stop() {
        playbackState = PlaybackState.IDLE
        tts?.stop()
        stopSilentAudio()
        unregisterVolumeReceiver()
        updateMediaSessionActiveState()
        fastVolumeController.reset()
        baselineMusicVolume = -1
        saveCurrentProgress()
        currentSegmentIndex = 0
        notifyPlaybackState(playbackState)
        updateMediaSessionPlaybackState()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * 跳至指定 UI 位置 (點擊段落直接開始朗讀)
     */
    fun seekToUiPosition(uiPos: Int) {
        val targetIdx = currentSegments.indexOfFirst { it.uiPosition == uiPos }
        if (targetIdx != -1) {
            currentSegmentIndex = targetIdx
            if (playbackState != PlaybackState.PLAYING) {
                requestAudioFocus()
                startSilentAudio()
                registerNoisyReceiver()
                playbackState = PlaybackState.PLAYING
                updateActiveState()
                startForeground(NOTIFICATION_ID, buildNotification())
                notifyPlaybackState(playbackState)
                updateMediaSessionPlaybackState()
            }
            speakCurrentSegment()
        }
    }

    /**
     * 上一段
     */
    fun previousSegment() {
        if (currentSegmentIndex > 0) {
            currentSegmentIndex--
            speakCurrentSegment()
        } else {
            playPreviousChapter()
        }
    }

    /**
     * 下一段
     */
    fun nextSegment() {
        if (currentSegmentIndex < currentSegments.size - 1) {
            currentSegmentIndex++
            speakCurrentSegment()
        } else {
            playNextChapter()
        }
    }

    /**
     * 上一章
     */
    fun playPreviousChapter() {
        val now = System.currentTimeMillis()
        if (now - lastChapterSwitchTime < 1000) return
        lastChapterSwitchTime = now
        if (currentChapterIndex > 0) {
            loadChapter(currentChapterIndex - 1, autoPlay = true)
        }
    }

    /**
     * 下一章 (要求必須自動換下一章)
     */
    fun playNextChapter() {
        val now = System.currentTimeMillis()
        if (now - lastChapterSwitchTime < 1000) return
        lastChapterSwitchTime = now
        if (currentChapterIndex < chapters.size - 1) {
            loadChapter(currentChapterIndex + 1, autoPlay = true)
        } else {
            stop()
        }
    }

    /**
     * 更新語速、語調、性別及過濾設定，並持久化至 SharedPreferences
     */
    fun updateSettings(newSettings: ReadingSettings) {
        this.settings = newSettings
        progressManager.saveSettings(newSettings)

        applyTtsParameters()
        val curSeg = currentSegments.getOrNull(currentSegmentIndex)
        regenerateSegments()
        if (curSeg != null) {
            val newIdx = currentSegments.indexOfFirst { it.id == curSeg.id || it.uiPosition == curSeg.uiPosition }
            currentSegmentIndex = if (newIdx != -1) newIdx else 0
        }
        val chapter = chapters.getOrNull(currentChapterIndex)
        val content = currentContent
        if (chapter != null && content != null) {
            notifyChapterLoaded(chapter, content, currentSegments, currentSegmentIndex)
        }
    }

    private fun applyTtsParameters() {
        val t = tts ?: return
        if (!isTtsReady) return

        t.setSpeechRate(settings.speechRate)

        var effectivePitch = settings.pitch
        when (settings.gender) {
            VoiceGender.MALE -> {
                effectivePitch = settings.pitch * 0.88f
                selectVoiceByGender(isFemale = false)
            }
            VoiceGender.FEMALE -> {
                effectivePitch = settings.pitch * 1.12f
                selectVoiceByGender(isFemale = true)
            }
            VoiceGender.DEFAULT -> {
                if (settings.selectedVoiceName != null) {
                    selectVoiceByName(settings.selectedVoiceName!!)
                } else {
                    selectDefaultTaiwanVoice()
                }
            }
        }
        t.setPitch(effectivePitch)
    }

    /**
     * 優先選取台灣 (zh-TW / cmn-tw) 口音的男聲或女聲
     */
    private fun selectVoiceByGender(isFemale: Boolean) {
        val voices = getAvailableChineseVoices()

        val targetVoice = if (isFemale) {
            // 1. 優先找台灣女聲 (cmn-tw-x-ctd, female, etc.)
            voices.find { v ->
                val n = v.name.lowercase()
                val isTw = isTaiwanVoice(v)
                val isFem = n.contains("female") || n.contains("woman") || n.contains("ctd") || n.contains("cta")
                isTw && isFem
            } ?: voices.find { v -> isTaiwanVoice(v) } // 2. 任何台灣語音
            ?: voices.find { v ->
                val n = v.name.lowercase()
                n.contains("female") || n.contains("woman") || n.contains("ctd")
            } ?: voices.firstOrNull()
        } else {
            // 1. 優先找台灣男聲 (cmn-tw-x-ctc, male, etc.)
            voices.find { v ->
                val n = v.name.lowercase()
                val isTw = isTaiwanVoice(v)
                val isMale = n.contains("male") || n.contains("man") || n.contains("ctc") || n.contains("ctb")
                isTw && isMale
            } ?: voices.find { v -> isTaiwanVoice(v) } // 2. 任何台灣語音
            ?: voices.find { v ->
                val n = v.name.lowercase()
                n.contains("male") || n.contains("man") || n.contains("ctc")
            } ?: voices.firstOrNull()
        }

        if (targetVoice != null) {
            tts?.voice = targetVoice
        }
    }

    /**
     * 預設選用台灣標準語音 (以台灣女聲 cmn-tw-x-ctd 或繁體中文優先)
     */
    fun selectDefaultTaiwanVoice() {
        val voices = getAvailableChineseVoices()
        val twVoice = voices.find { v ->
            val n = v.name.lowercase()
            isTaiwanVoice(v) && (n.contains("ctd") || n.contains("female") || n.contains("cta"))
        } ?: voices.find { v -> isTaiwanVoice(v) }

        if (twVoice != null) {
            tts?.voice = twVoice
        }
    }

    private fun isTaiwanVoice(v: Voice): Boolean {
        val n = v.name.lowercase()
        return v.locale == TAIWAN_LOCALE ||
                v.locale.country.equals("TW", true) ||
                v.locale.toLanguageTag().contains("TW", true) ||
                n.contains("tw") || n.contains("taiwan")
    }

    fun selectVoiceByName(name: String) {
        val voices = getAvailableChineseVoices()
        val v = voices.find { it.name == name }
        if (v != null) {
            tts?.voice = v
            settings.selectedVoiceName = name
            progressManager.saveSettings(settings)
        }
    }

    /**
     * 取得所有中文語音，並將 🇹🇼 台灣語音排在最前面
     */
    fun getAvailableChineseVoices(): List<Voice> {
        val t = tts ?: return emptyList()
        return try {
            val all = t.voices?.filter { v ->
                val lang = v.locale.language.lowercase()
                lang.startsWith("zh") || lang.startsWith("cmn") || v.locale == Locale.CHINESE || v.locale == TAIWAN_LOCALE
            } ?: emptyList()

            // 台灣口音排在最前面，其餘依序排列
            all.sortedWith(compareByDescending<Voice> { isTaiwanVoice(it) }.thenBy { it.name })
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun speakCurrentSegment() {
        if (!isTtsReady || currentSegments.isEmpty()) return
        if (currentSegmentIndex !in currentSegments.indices) {
            if (settings.autoNextChapter && currentChapterIndex < chapters.size - 1) {
                playNextChapter()
            } else {
                stop()
            }
            return
        }

        val segment = currentSegments[currentSegmentIndex]
        saveCurrentProgress()
        notifySegmentChanged(currentSegmentIndex, segment)
        updateNotification()

        val utteranceId = "seg_${currentChapterIndex}_$currentSegmentIndex"
        tts?.speak(segment.speechText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    private fun handleUtteranceDone() {
        if (playbackState != PlaybackState.PLAYING) return

        if (currentSegmentIndex < currentSegments.size - 1) {
            currentSegmentIndex++
            speakCurrentSegment()
        } else {
            if (settings.autoNextChapter && currentChapterIndex < chapters.size - 1) {
                playNextChapter()
            } else {
                stop()
            }
        }
    }

    fun saveCurrentProgress() {
        val chapter = chapters.getOrNull(currentChapterIndex) ?: return
        val segment = currentSegments.getOrNull(currentSegmentIndex)
        progressManager.saveProgress(
            novelId = repository.currentNovelId,
            novelTitle = repository.currentNovelTitle,
            chapterIndex = currentChapterIndex,
            chapterNumber = chapter.chapterNumber,
            chapterTitle = chapter.title,
            segmentIndex = currentSegmentIndex,
            uiPosition = segment?.uiPosition ?: 0
        )
    }

    // --- 通知列相關 ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "小說有聲朗讀",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "提供背景朗讀與通知列播放控制"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val chapterTitle = chapters.getOrNull(currentChapterIndex)?.title ?: "小說閱讀"
        val segmentText = currentSegments.getOrNull(currentSegmentIndex)?.displayText ?: "朗讀中..."

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this, 1, Intent(this, TtsReadingService::class.java).apply { action = ACTION_PREV_CHAPTER },
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseActionIntent = if (playbackState == PlaybackState.PLAYING) {
            PendingIntent.getService(
                this, 2, Intent(this, TtsReadingService::class.java).apply { action = ACTION_PAUSE },
                PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2, Intent(this, TtsReadingService::class.java).apply { action = ACTION_PLAY },
                PendingIntent.FLAG_IMMUTABLE
            )
        }
        val nextIntent = PendingIntent.getService(
            this, 3, Intent(this, TtsReadingService::class.java).apply { action = ACTION_NEXT_CHAPTER },
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (playbackState == PlaybackState.PLAYING) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseTitle = if (playbackState == PlaybackState.PLAYING) "暫停" else "朗讀"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(chapterTitle)
            .setContentText(segmentText)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_previous, "上一章", prevIntent)
            .addAction(playPauseIcon, playPauseTitle, playPauseActionIntent)
            .addAction(android.R.drawable.ic_media_next, "下一章", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(playbackState == PlaybackState.PLAYING)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (playbackState != PlaybackState.IDLE) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun notifyPlaybackState(state: PlaybackState) {
        listeners.forEach { it.onPlaybackStateChanged(state) }
    }

    private fun notifyChapterLoaded(chapter: Chapter, content: ChapterContent, segments: List<TtsSegment>, initialSegmentIndex: Int) {
        listeners.forEach { it.onChapterLoaded(chapter, content, segments, initialSegmentIndex) }
    }

    private fun notifySegmentChanged(segmentIndex: Int, segment: TtsSegment?) {
        listeners.forEach { it.onSegmentChanged(segmentIndex, segment) }
    }

    private fun notifyTtsReady() {
        val voices = getAvailableChineseVoices()
        listeners.forEach { it.onTtsReady(voices) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("NovelTts", "onTaskRemoved: app swiped away/closed, stopping and releasing")
        stopAndRelease()
    }

    /**
     * 當 App 關閉（MainActivity 銷毀或工作清單滑掉）時徹底清理資源並結束 Service
     */
    fun stopAndRelease() {
        Log.d("NovelTts", "stopAndRelease: cleaning up media resources and stopping service")
        isAppInForeground = false
        wasForegroundBeforeScreenOff = false
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {}
            isScreenReceiverRegistered = false
        }
        updateActiveState()
        stop()
        releaseMediaSession()
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentProgress()
        serviceScope.cancel()
        tts?.stop()
        tts?.shutdown()
        stopSilentAudio()
        abandonAudioFocus()
        unregisterNoisyReceiver()
        unregisterVolumeReceiver()
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenStateReceiver)
            } catch (_: Exception) {}
            isScreenReceiverRegistered = false
        }
        releaseMediaSession()
    }
}
