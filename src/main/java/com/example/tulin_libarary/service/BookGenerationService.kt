package com.example.tulin_libarary.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.tulin_libarary.MainActivity
import com.example.tulin_libarary.data.ApiConfig
import com.example.tulin_libarary.data.Book
import com.example.tulin_libarary.data.Chapter
import com.example.tulin_libarary.data.DefaultPrompts
import com.example.tulin_libarary.data.GenerationState
import com.example.tulin_libarary.data.LibraryRepository
import com.example.tulin_libarary.data.SiliconFlowApiService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random
import android.util.Log

class BookGenerationService : Service() {

    private val TAG = "BookGenerationService"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var generationJob: Job? = null

    private lateinit var repository: LibraryRepository
    private val apiService = SiliconFlowApiService()

    private var lastNotifyTime = 0L
    private var currentBookId: Long = -1L
    private var currentTitle: String = ""
    @Volatile
    private var isUserCancelled = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate: Service created")
        repository = LibraryRepository(applicationContext)
        createNotificationChannels()
        initLocks()
        startForegroundImmediately()
    }

    private fun startForegroundImmediately() {
        try {
            val notification = buildProgressNotification("初始化中...", 0f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID_PROGRESS,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID_PROGRESS, notification)
            }
            Log.d(TAG, "startForegroundImmediately: Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundImmediately failed", e)
        }
    }

    private fun initLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BookGeneration::CPU"
        ).apply {
            setReferenceCounted(false)
        }

        val wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "BookGeneration::WiFi"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld == false) {
                // 单次持有最长30分钟，足够覆盖一次网络请求；若任务更长会再次acquire
                wakeLock?.acquire(30 * 60 * 1000L)
                Log.d(TAG, "acquireLocks: WakeLock acquired")
            }
            @Suppress("DEPRECATION")
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
                Log.d(TAG, "acquireLocks: WifiLock acquired")
            }
        } catch (e: Exception) {
            Log.e(TAG, "acquireLocks failed", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "releaseLocks: WakeLock released")
            }
            @Suppress("DEPRECATION")
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "releaseLocks: WifiLock released")
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseLocks failed", e)
        }
    }

    /**
     * 检测设备是否处于 Doze（省电休眠）模式。
     * 在该模式下系统会挂起网络访问，需要等待恢复或请求用户关闭电池优化。
     */
    private fun isDeviceIdle(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            powerManager.isDeviceIdleMode
        } else {
            false
        }
    }

    /**
     * 如果设备处于 Doze 模式，则等待其退出，最多等待指定时长。
     */
    private suspend fun waitForDeviceNotIdle(timeoutMs: Long = 10 * 60 * 1000L) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val deadline = System.currentTimeMillis() + timeoutMs
        while (powerManager.isDeviceIdleMode && System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            delay(3000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")
        when (intent?.action) {
            ACTION_START -> {
                if (generationJob?.isActive == true) {
                    Log.w(TAG, "onStartCommand: Already generating, ignoring start request")
                    return START_REDELIVER_INTENT
                }
                val prompt = intent.getStringExtra(EXTRA_PROMPT).orEmpty()
                if (prompt.isNotBlank()) {
                    Log.d(TAG, "onStartCommand: Starting generation with prompt: $prompt")
                    startGenerationFromPrompt(prompt)
                } else {
                    Log.d(TAG, "onStartCommand: Empty prompt, checking for resumable books")
                    checkAndResumeGeneration()
                }
            }
            ACTION_CANCEL -> {
                Log.d(TAG, "onStartCommand: Cancelling generation")
                stopGeneration()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun checkAndResumeGeneration() {
        if (generationJob?.isActive == true) {
            Log.d(TAG, "checkAndResumeGeneration: Generation already running, skip")
            return
        }
        scope.launch {
            try {
                Log.d(TAG, "checkAndResumeGeneration: Checking for generating books")
                val generatingBooks = withContext(Dispatchers.IO) {
                    repository.getBooksByStatus("generating")
                }
                if (generatingBooks.isNotEmpty()) {
                    val book = generatingBooks.first()
                    Log.d(TAG, "checkAndResumeGeneration: Found generating book: ${book.title}")
                    val config = getApiConfig()
                    if (config.apiKey.isNotBlank()) {
                        updateState(GenerationState.Generating("正在恢复创作...", 0.1f))
                        startGenerationFromBook(book, config)
                    } else {
                        Log.e(TAG, "checkAndResumeGeneration: API key is blank")
                    }
                } else {
                    Log.d(TAG, "checkAndResumeGeneration: No generating books found")
                    stopSelfIfIdle()
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkAndResumeGeneration failed", e)
                if (e !is CancellationException) {
                    stopSelfIfIdle()
                }
            }
        }
    }

    private fun startGenerationFromPrompt(prompt: String) {
        isUserCancelled = false
        acquireLocks()

        generationJob?.cancel()
        generationJob = scope.launch {
            try {
                Log.d(TAG, "startGenerationFromPrompt: Checking for existing generating books")
                val existingGeneratingBooks = withContext(Dispatchers.IO) {
                    repository.getBooksByStatus("generating")
                }

                if (existingGeneratingBooks.isNotEmpty()) {
                    val existingBook = existingGeneratingBooks.first()
                    Log.d(TAG, "startGenerationFromPrompt: Resuming existing book: ${existingBook.title}")
                    startGenerationFromBook(existingBook, getApiConfig())
                    return@launch
                }

                Log.d(TAG, "startGenerationFromPrompt: Starting new generation")
                val config = getApiConfig()
                if (config.apiKey.isBlank()) {
                    Log.e(TAG, "startGenerationFromPrompt: API key is blank")
                    updateState(GenerationState.Error("请先在设置中配置API Key"))
                    sendResultNotification(false, "创作失败", "请先在设置中配置API Key", -1L)
                    releaseLocks()
                    currentBookId = -1L
                    currentTitle = ""
                    stopSelf()
                    return@launch
                }

                updateState(GenerationState.Generating("正在构思故事框架...", 0.05f))
                Log.d(TAG, "startGenerationFromPrompt: Generating outline")

                val outline = generateWithRetry(
                    config = config,
                    systemPrompt = config.outlineSystemPrompt,
                    userPrompt = prompt,
                    temperature = 0.7,
                    maxTokens = 4096,
                    chapterNum = 1,
                    totalChapters = 1,
                    label = "构思",
                    baseProgress = 0.05f
                )
                if (outline == null) {
                    Log.e(TAG, "startGenerationFromPrompt: Outline generation failed")
                    updateState(GenerationState.Error("故事框架构思失败，已重试3次"))
                    sendResultNotification(false, "创作失败", "故事框架构思失败", -1L)
                    releaseLocks()
                    currentBookId = -1L
                    currentTitle = ""
                    stopSelf()
                    return@launch
                }
                Log.d(TAG, "startGenerationFromPrompt: Outline generated: ${outline.take(100)}...")

                val title = Regex("《([^》]+)》").find(outline)?.groupValues?.get(1) ?: "未命名书籍"
                val totalChapters = extractChapterCount(outline, config.maxChapters)
                Log.d(TAG, "startGenerationFromPrompt: Title=$title, Chapters=$totalChapters")

                val coverColors = listOf(0xFFE57373, 0xFF64B5F6, 0xFF81C784, 0xFFFFD54F, 0xFFBA68C8, 0xFF4DB6AC)
                val book = Book(
                    title = title,
                    outline = outline,
                    coverColor = coverColors.random(),
                    totalChapters = totalChapters,
                    status = "generating"
                )
                val bookId = withContext(Dispatchers.IO) { repository.insertBook(book) }
                currentBookId = bookId
                currentTitle = title
                Log.d(TAG, "startGenerationFromPrompt: Book created with id=$bookId")

                val bookWithId = book.copy(id = bookId)
                startGenerationFromBook(bookWithId, config)

            } catch (e: CancellationException) {
                Log.d(TAG, "startGenerationFromPrompt: Cancelled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startGenerationFromPrompt failed", e)
                if (currentBookId > 0) {
                    markBookFailed(currentBookId)
                }
                updateState(GenerationState.Error(e.message ?: "生成失败"))
                sendResultNotification(false, "创作失败", e.message ?: "未知错误", currentBookId)
                releaseLocks()
                currentBookId = -1L
                currentTitle = ""
                stopSelf()
            }
        }
    }

    private fun startGenerationFromBook(book: Book, config: ApiConfig) {
        isUserCancelled = false
        currentBookId = book.id
        currentTitle = book.title
        acquireLocks()

        generationJob = scope.launch {
            try {
                generateChapters(book, config)
                if (isUserCancelled) {
                    Log.d(TAG, "startGenerationFromBook: Generation cancelled for book ${book.id}")
                    return@launch
                }
                generateCoverImage(book, config)

                val updatedBook = withContext(Dispatchers.IO) {
                    repository.getBook(book.id) ?: book
                }
                withContext(Dispatchers.IO) {
                    repository.updateBook(updatedBook.copy(status = "completed"))
                }
                updateState(GenerationState.Success(book.id))
                sendResultNotification(true, "创作完成", "《${book.title}》已为你创作完成，点击开始阅读", book.id)
                Log.d(TAG, "startGenerationFromBook: Generation completed for book ${book.id}")

            } catch (e: CancellationException) {
                Log.d(TAG, "startGenerationFromBook: Cancelled for book ${book.id}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startGenerationFromBook failed", e)
                markBookFailed(book.id)
                updateState(GenerationState.Error(e.message ?: "生成失败"))
                sendResultNotification(false, "创作失败", e.message ?: "未知错误", book.id)
            } finally {
                releaseLocks()
                currentBookId = -1L
                currentTitle = ""
                generationJob = null
                // 如果用户未取消，检查是否还有其他需要生成的书；否则停止服务
                if (isUserCancelled) {
                    stopSelf()
                } else {
                    val nextBook = withContext(Dispatchers.IO) {
                        repository.getBooksByStatus("generating").firstOrNull()
                    }
                    if (nextBook != null) {
                        startGenerationFromBook(nextBook, getApiConfig())
                    } else {
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun markBookFailed(bookId: Long) {
        try {
            withContext(Dispatchers.IO) {
                repository.getBook(bookId)?.let {
                    repository.updateBook(it.copy(status = "failed"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "markBookFailed failed", e)
        }
    }

    private suspend fun generateChapters(book: Book, config: ApiConfig) {
        val completedChapters = withContext(Dispatchers.IO) {
            repository.getCompletedChapterCount(book.id)
        }
        var previousChapterSummary = withContext(Dispatchers.IO) {
            repository.getLastChapterSummary(book.id)
        }

        Log.d(TAG, "generateChapters: Book ${book.id}, completed=$completedChapters, total=${book.totalChapters}")

        if (completedChapters > 0) {
            updateState(
                GenerationState.Generating(
                    "已恢复创作，继续第${completedChapters + 1}/${book.totalChapters}章...",
                    0.15f + completedChapters.toFloat() * (0.77f / book.totalChapters.toFloat())
                )
            )
        }

        for (chapterNum in (completedChapters + 1)..book.totalChapters) {
            currentCoroutineContext().ensureActive()
            acquireLocks()
            if (isUserCancelled) {
                Log.d(TAG, "generateChapters: User cancelled at chapter $chapterNum")
                return
            }

            val baseProgress = 0.15f
            val progressPerChapter = 0.77f / book.totalChapters.toFloat()
            val currentProgress = baseProgress + (chapterNum - 1).toFloat() * progressPerChapter

            updateState(
                GenerationState.Generating(
                    "正在创作第${chapterNum}/${book.totalChapters}章...",
                    currentProgress
                )
            )
            Log.d(TAG, "generateChapters: Starting chapter $chapterNum")

            val currentChapterOutline = extractChapterOutline(book.outline, chapterNum)

            val chapterOutlinePrompt = if (chapterNum == 1) {
                "书籍标题：《${book.title}》\n\n当前章节大纲：\n$currentChapterOutline\n\n请详细展开第${chapterNum}章的写作大纲，包含关键情节节点。"
            } else {
                "书籍标题：《${book.title}》\n\n$previousChapterSummary\n\n当前章节大纲：\n$currentChapterOutline\n\n请根据上一章结尾和当前大纲，详细展开第${chapterNum}章的写作大纲。要求：\n1. 明确本章的承接点——从上一章结尾的哪个情节或情绪状态自然延续\n2. 标注本章的第一个关键转折，确保从承接点到转折的过渡流畅\n3. 列出本章与上一章的人物、伏笔关联点"
            }

            val chapterOutline = generateWithRetry(
                config = config,
                systemPrompt = config.outlineSystemPrompt,
                userPrompt = chapterOutlinePrompt,
                temperature = 0.7,
                maxTokens = 4096,
                chapterNum = chapterNum,
                totalChapters = book.totalChapters,
                label = "大纲"
            )
            if (chapterOutline == null) {
                Log.e(TAG, "generateChapters: Chapter $chapterNum outline generation failed")
                markBookFailed(book.id)
                updateState(GenerationState.Error("第${chapterNum}章大纲生成失败，已重试3次"))
                sendResultNotification(false, "创作失败", "《${book.title}》第${chapterNum}章大纲生成失败", book.id)
                throw GenerationException("第${chapterNum}章大纲生成失败")
            }

            currentCoroutineContext().ensureActive()
            if (isUserCancelled) {
                Log.d(TAG, "generateChapters: User cancelled after outline $chapterNum")
                return
            }

            updateState(
                GenerationState.Generating(
                    "正在撰写第${chapterNum}/${book.totalChapters}章内容...",
                    currentProgress + progressPerChapter * 0.5f
                )
            )

            val contentPrompt = if (chapterNum == 1) {
                "书籍标题：《${book.title}》\n\n章节大纲：\n$chapterOutline\n\n请根据大纲撰写第${chapterNum}章的完整内容，要求细节丰富，语言优美，情节引人入胜，1000字左右。"
            } else {
                "书籍标题：《${book.title}》\n\n$previousChapterSummary\n\n当前章节大纲：\n$chapterOutline\n\n请根据上一章结尾和当前大纲，撰写第${chapterNum}章的完整内容。要求：\n1. 开篇用1-2句自然承接上一章结尾——可以是一个动作的延续、场景的切换、或情绪的过渡，避免生硬的\"上回说到\"或时间跳跃\n2. 保持人物性格、语气、设定的前后一致性\n3. 如有伏笔或未解决线索，在本章中自然提及\n4. 细节丰富，语言优美，1000字左右"
            }

            val chapterContent = generateWithRetry(
                config = config,
                systemPrompt = config.chapterSystemPrompt,
                userPrompt = contentPrompt,
                temperature = 0.7,
                maxTokens = 8192,
                chapterNum = chapterNum,
                totalChapters = book.totalChapters,
                label = "内容"
            )
            if (chapterContent == null) {
                Log.e(TAG, "generateChapters: Chapter $chapterNum content generation failed")
                markBookFailed(book.id)
                updateState(GenerationState.Error("第${chapterNum}章内容生成失败，已重试3次"))
                sendResultNotification(false, "创作失败", "《${book.title}》第${chapterNum}章内容生成失败", book.id)
                throw GenerationException("第${chapterNum}章内容生成失败")
            }

            currentCoroutineContext().ensureActive()

            val chapterTitle = Regex("(?:第[一二三四五六七八九十\\d]+章[：:]?\\s*)(.+)")
                .find(chapterContent)?.groupValues?.get(1)
                ?: "第${chapterNum}章"

            val chapter = Chapter(
                bookId = book.id,
                chapterNumber = chapterNum,
                title = chapterTitle,
                content = chapterContent,
                outline = chapterOutline
            )
            withContext(Dispatchers.IO) { repository.insertChapter(chapter) }
            Log.d(TAG, "generateChapters: Chapter $chapterNum completed")

            previousChapterSummary = extractChapterSummary(chapterContent)
        }
    }

    private class GenerationException(message: String) : Exception(message)

    private suspend fun generateCoverImage(book: Book, config: ApiConfig) {
        if (book.coverImagePath.isNotBlank()) {
            Log.d(TAG, "generateCoverImage: Cover already exists, skipping")
            updateState(GenerationState.Generating("书籍创作完成，封面已存在", 1.0f))
            return
        }

        if (config.imagesModel.isBlank() || config.imagesBaseUrl.isBlank() || config.imagePrompt.isBlank()) {
            Log.d(TAG, "generateCoverImage: Image config incomplete, skipping")
            updateState(GenerationState.Generating("书籍创作完成，跳过封面生成", 1.0f))
            return
        }

        // 如果设备已休眠，等待恢复后再生成封面，避免网络请求被 Doze 挂起
        if (isDeviceIdle()) {
            updateState(GenerationState.Generating("设备休眠中，等待网络恢复后生成封面...", 0.92f))
            waitForDeviceNotIdle()
        }

        try {
            updateState(GenerationState.Generating("正在构思封面描述...", 0.92f))
            val imagePromptText = withContext(Dispatchers.IO) {
                apiService.generateImagePrompt(
                    apiKey = config.apiKey,
                    baseUrl = config.baseUrl,
                    model = config.model,
                    imagePrompt = config.imagePrompt,
                    bookOutline = book.outline
                )
            }.trim()
            Log.d(TAG, "generateCoverImage: Image prompt generated")

            updateState(GenerationState.Generating("正在生成封面图片...", 0.95f))
            val imageApiKey = config.imagesApiKey.ifBlank { config.apiKey }
            val imageUrl = withContext(Dispatchers.IO) {
                apiService.generateImage(
                    apiKey = imageApiKey,
                    imagesBaseUrl = config.imagesBaseUrl,
                    imagesModel = config.imagesModel,
                    prompt = imagePromptText,
                    seed = Random.nextInt(100000)
                )
            }
            Log.d(TAG, "generateCoverImage: Image URL received")

            updateState(GenerationState.Generating("正在下载封面图片...", 0.97f))
            val imageResponse = withContext(Dispatchers.IO) {
                java.net.URL(imageUrl).openStream().readBytes()
            }

            updateState(GenerationState.Generating("正在保存封面...", 0.99f))
            val imagesDir = File(filesDir, "covers")
            imagesDir.mkdirs()
            val coverImagePath = File(imagesDir, "${book.id}_cover.png").absolutePath

            withContext(Dispatchers.IO) {
                File(coverImagePath).writeBytes(imageResponse)
            }

            withContext(Dispatchers.IO) {
                repository.updateBook(book.copy(coverImagePath = coverImagePath))
            }
            Log.d(TAG, "generateCoverImage: Cover saved to $coverImagePath")

        } catch (e: Exception) {
            Log.e(TAG, "generateCoverImage failed", e)
        }
    }

    private fun stopGeneration() {
        isUserCancelled = true
        generationJob?.cancel()
        scope.launch(Dispatchers.IO) {
            if (currentBookId > 0) {
                try {
                    val book = repository.getBook(currentBookId)
                    book?.let {
                        repository.updateBook(it.copy(status = "cancelled"))
                        Log.d(TAG, "stopGeneration: Book ${currentBookId} marked as cancelled")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "stopGeneration: Failed to mark book cancelled", e)
                }
            }
        }
        updateState(GenerationState.Idle)
        releaseLocks()
        currentBookId = -1L
        currentTitle = ""
        // 取消后不再继续其他生成任务
        generationJob = null
        stopSelf()
        Log.d(TAG, "stopGeneration: Generation stopped")
    }

    private fun stopSelfIfIdle() {
        scope.launch(Dispatchers.IO) {
            try {
                val hasGenerating = repository.getBooksByStatus("generating").isNotEmpty()
                if (!hasGenerating && generationJob?.isActive != true) {
                    Log.d(TAG, "stopSelfIfIdle: No more work, stopping service")
                    stopSelf()
                }
            } catch (e: Exception) {
                Log.e(TAG, "stopSelfIfIdle failed", e)
            }
        }
    }

    private fun updateState(state: GenerationState) {
        _state.value = state
        val now = System.currentTimeMillis()
        if (state is GenerationState.Generating) {
            // 限制通知刷新频率，避免系统负担，同时保证进度可见
            if (now - lastNotifyTime > 1000 || state.progress >= 0.99f) {
                lastNotifyTime = now
                notifyProgress(state.step, state.progress)
            }
        }
    }

    private fun notifyProgress(step: String, progress: Float) {
        try {
            NotificationManagerCompat.from(this).notify(
                NOTIFICATION_ID_PROGRESS,
                buildProgressNotification(step, progress)
            )
        } catch (_: SecurityException) {
            Log.w(TAG, "notifyProgress: Notification permission denied")
        }
    }

    private fun buildProgressNotification(step: String, progress: Float): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BookGenerationService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI正在后台创作")
            .setContentText(step)
            .setProgress(100, (progress * 100).toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelIntent)
            .build()
    }

    private fun sendResultNotification(success: Boolean, title: String, content: String, bookId: Long) {
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID_PROGRESS)

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_BOOK_ID, bookId)
            putExtra(EXTRA_OPEN_READER, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, bookId.toInt(),
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID_RESULT)
            .setSmallIcon(if (success) android.R.drawable.ic_menu_send else android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        if (success) {
            builder.setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
        }

        try {
            NotificationManagerCompat.from(this).notify(bookId.toInt(), builder.build())
            Log.d(TAG, "sendResultNotification: Sent notification for book $bookId")
        } catch (_: SecurityException) {
            Log.w(TAG, "sendResultNotification: Notification permission denied")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val progressChannel = NotificationChannel(
            CHANNEL_ID_PROGRESS,
            "创作进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示AI书籍创作的实时进度"
            setShowBadge(false)
        }

        val resultChannel = NotificationChannel(
            CHANNEL_ID_RESULT,
            "创作结果",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "AI书籍创作完成或失败的通知"
        }

        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(resultChannel)
    }

    private fun getApiConfig(): ApiConfig {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        return ApiConfig(
            apiKey = prefs.getString("api_key", "") ?: "",
            baseUrl = prefs.getString("base_url", "https://api.siliconflow.cn/v1") ?: "",
            model = prefs.getString("model", "deepseek-ai/DeepSeek-V4-Flash") ?: "",
            imagesModel = prefs.getString("images_model", "Kwai-Kolors/Kolors") ?: "",
            imagesBaseUrl = prefs.getString("images_base_url", "https://api.siliconflow.cn/v1") ?: "",
            imagePrompt = prefs.getString("image_prompt", "你是一个书籍封面制作大师，能够绘制任何书籍的最佳封面，请将用户的输入升华具有大师水准的准确且标准且丰富的的英文绘图提示词，以便绘图模型能够完美绘制。") ?: "",
            imagesApiKey = prefs.getString("images_api_key", "") ?: "",
            outlineSystemPrompt = prefs.getString("outline_system_prompt", DefaultPrompts.OUTLINE_SYSTEM_PROMPT)
                ?.takeIf { it.isNotBlank() }
                ?: DefaultPrompts.OUTLINE_SYSTEM_PROMPT,
            chapterSystemPrompt = prefs.getString("chapter_system_prompt", DefaultPrompts.CHAPTER_SYSTEM_PROMPT)
                ?.takeIf { it.isNotBlank() }
                ?: DefaultPrompts.CHAPTER_SYSTEM_PROMPT,
            maxChapters = prefs.getInt("max_chapters", 100).coerceIn(1, 500)
        )
    }

    private suspend fun generateWithRetry(
        config: ApiConfig,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double,
        maxTokens: Int,
        chapterNum: Int,
        totalChapters: Int,
        label: String,
        maxRetries: Int = 3,
        baseProgress: Float = 0.15f
    ): String? {
        val progressPerChapter = 0.77f / totalChapters.toFloat()
        val currentProgress = baseProgress + (chapterNum - 1).toFloat() * progressPerChapter

        var attempt = 0
        while (attempt < maxRetries) {
            attempt++
            currentCoroutineContext().ensureActive()
            if (isUserCancelled) {
                Log.d(TAG, "generateWithRetry: Cancelled at attempt $attempt")
                return null
            }

            // 如果设备处于 Doze 模式，等待恢复后再发请求，避免长时间挂起
            if (isDeviceIdle()) {
                updateState(
                    GenerationState.Generating(
                        "设备处于省电休眠，等待网络恢复后继续${label}...",
                        currentProgress + progressPerChapter * (if (label == "大纲") 0.2f else 0.5f)
                    )
                )
                waitForDeviceNotIdle()
                if (isUserCancelled) return null
            }

            try {
                Log.d(TAG, "generateWithRetry: Attempt $attempt/$maxRetries for chapter $chapterNum $label")
                return withContext(Dispatchers.IO) {
                    apiService.chatCompletion(
                        apiKey = config.apiKey,
                        baseUrl = config.baseUrl,
                        model = config.model,
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        temperature = temperature,
                        maxTokens = maxTokens
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "generateWithRetry: Socket timeout for chapter $chapterNum $label", e)
                // Doze 会导致请求长时间无响应，检测到后不计入重试次数，等待恢复后重试
                if (isDeviceIdle()) {
                    updateState(
                        GenerationState.Generating(
                            "网络因休眠暂停，等待恢复后继续${label}...",
                            currentProgress + progressPerChapter * (if (label == "大纲") 0.2f else 0.5f)
                        )
                    )
                    waitForDeviceNotIdle()
                    attempt--
                    continue
                }
                if (attempt < maxRetries) {
                    updateState(
                        GenerationState.Generating(
                            "第${chapterNum}章${label}第${attempt}次尝试失败，正在重试...",
                            currentProgress + progressPerChapter * (if (label == "大纲") 0.2f else 0.7f)
                        )
                    )
                    delay(2000L * attempt)
                }
            } catch (e: Exception) {
                Log.e(TAG, "generateWithRetry: Attempt $attempt failed for chapter $chapterNum $label", e)
                if (attempt < maxRetries) {
                    updateState(
                        GenerationState.Generating(
                            "第${chapterNum}章${label}第${attempt}次尝试失败，正在重试...",
                            currentProgress + progressPerChapter * (if (label == "大纲") 0.2f else 0.7f)
                        )
                    )
                    delay(2000L * attempt)
                }
            }
        }
        return null
    }

    private fun extractChapterCount(outline: String, maxChapters: Int): Int {
        val explicitMatch = Regex("(?:共|一共|总计|总[计共])\\s*(\\d+)\\s*[章节]").find(outline)
            ?: Regex("(?:共|一共|总计|总[计共])\\s*([一二三四五六七八九十]+)\\s*[章节]").find(outline)
        if (explicitMatch != null) {
            val numStr = explicitMatch.groupValues[1]
            val count = numStr.toIntOrNull() ?: chineseToInt(numStr) ?: 1
            return count.coerceIn(1, maxChapters)
        }
        val simpleMatch = Regex("(?<!第)(\\d+)\\s*章(?!节)").find(outline)
        if (simpleMatch != null) {
            val count = simpleMatch.groupValues[1].toIntOrNull() ?: 1
            return count.coerceIn(1, maxChapters)
        }
        val englishMatch = Regex("(\\d+)\\s*chapters?", RegexOption.IGNORE_CASE).find(outline)
        if (englishMatch != null) {
            val count = englishMatch.groupValues[1].toIntOrNull() ?: 1
            return count.coerceIn(1, maxChapters)
        }
        return 1
    }

    private fun extractChapterOutline(fullOutline: String, chapterNum: Int): String {
        val chapterPattern = Regex("(第[一二三四五六七八九十\\d]+章[：:]?.+?)(?=第[一二三四五六七八九十\\d]+章|$)", RegexOption.DOT_MATCHES_ALL)
        val matches = chapterPattern.findAll(fullOutline).toList()
        if (chapterNum > 0 && chapterNum <= matches.size) {
            return matches[chapterNum - 1].groupValues[1].trim()
        }
        return "第${chapterNum}章内容大纲"
    }

    private fun extractChapterSummary(content: String): String {
        val cleanContent = content.replace(Regex("\\s+"), " ").trim()
        val titleLine = cleanContent.lines().firstOrNull { it.contains("第") && it.contains("章") } ?: ""
        val titleContext = if (titleLine.length in 2..50) "\n【上章标题】${titleLine.trim()}" else ""

        return if (cleanContent.length <= 200) {
            titleContext + cleanContent
        } else {
            titleContext + "\n【上章结尾】..." + cleanContent.takeLast(200)
        }
    }

    private fun chineseToInt(str: String): Int? {
        val map = mapOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
        )
        return map[str]
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Service destroyed")
        isUserCancelled = true
        generationJob?.cancel()
        releaseLocks()
        scope.cancel()
    }

    companion object {
        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_OPEN_READER = "open_reader"
        const val ACTION_START = "com.example.tulin_libarary.action.START"
        const val ACTION_CANCEL = "com.example.tulin_libarary.action.CANCEL"
        const val CHANNEL_ID_PROGRESS = "generation_progress"
        const val CHANNEL_ID_RESULT = "generation_result"
        const val NOTIFICATION_ID_PROGRESS = 1001

        private val _state = MutableStateFlow<GenerationState>(GenerationState.Idle)
        val state: StateFlow<GenerationState> = _state.asStateFlow()

        fun resetState() {
            val current = _state.value
            if (current is GenerationState.Success || current is GenerationState.Error) {
                _state.value = GenerationState.Idle
            }
        }
    }
}
