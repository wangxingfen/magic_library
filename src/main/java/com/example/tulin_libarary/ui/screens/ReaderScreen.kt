package com.example.tulin_libarary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.tulin_libarary.data.Book
import com.example.tulin_libarary.data.Chapter
import com.example.tulin_libarary.viewmodel.LibraryViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ReadingTheme(val displayName: String, val bgColor: Color, val textColor: Color, val titleColor: Color) {
    LIGHT("浅色", Color(0xFFFFFFFF), Color(0xFF212121), Color(0xFF1976D2)),
    DARK("夜间", Color(0xFF121212), Color(0xFFE0E0E0), Color(0xFF90CAF9)),
    SEPIA("护眼", Color(0xFFF5F5DC), Color(0xFF5D4037), Color(0xFF8D6E63)),
    GREEN("绿豆沙", Color(0xFFC7EDCC), Color(0xFF2E3B2F), Color(0xFF4E6B4F)),
    BLUE("星空", Color(0xFF1A237E), Color(0xFFE3F2FD), Color(0xFF90CAF9)),
    PURPLE("紫罗兰", Color(0xFF4A148C), Color(0xFFFCE4EC), Color(0xFFCE93D8))
}

data class FontOption(
    val key: String,
    val displayName: String,
    val fontFamily: FontFamily
)

/** 加载可用字体：内置 + 系统字体库 */
fun loadAvailableFonts(): List<FontOption> {
    val fonts = mutableListOf(
        FontOption("system", "系统默认", FontFamily.Default),
        FontOption("serif", "衬线", FontFamily.Serif),
        FontOption("sans_serif", "无衬线", FontFamily.SansSerif),
        FontOption("sans_light", "细体", FontFamily(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL))),
        FontOption("sans_medium", "中黑", FontFamily(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL))),
        FontOption("sans_condensed", "窄体", FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))),
        FontOption("monospace", "等宽", FontFamily.Monospace),
        FontOption("serif_mono", "衬线等宽", FontFamily(android.graphics.Typeface.create("serif-monospace", android.graphics.Typeface.NORMAL))),
        FontOption("cursive", "手写", FontFamily.Cursive)
    )
    // 扫描系统字体目录
    runCatching {
        val fontDir = java.io.File("/system/fonts/")
        if (fontDir.isDirectory) {
            fontDir.listFiles { f -> f.extension.lowercase() in setOf("ttf", "otf") }
                ?.sortedBy { it.nameWithoutExtension }
                ?.forEach { file ->
                    val tf = android.graphics.Typeface.createFromFile(file)
                    val name = file.nameWithoutExtension
                    fonts.add(FontOption("file:${file.absolutePath}", name, FontFamily(tf)))
                }
        }
    }
    return fonts
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: Long,
    viewModel: LibraryViewModel,
    onBack: () -> Unit
) {
    val chapters by viewModel.getChapters(bookId).collectAsState()
    val books by viewModel.allBooks.collectAsState()
    val book = books.find { it.id == bookId }
    val context = LocalContext.current
    val readingPrefs = remember { context.getSharedPreferences("reading_settings", android.content.Context.MODE_PRIVATE) }

    var selectedChapter by remember { mutableStateOf<Chapter?>(null) }
    var showChapterList by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showToolbar by remember { mutableStateOf(true) }
    var showFontPicker by remember { mutableStateOf(false) }
    
    // 字体列表（内置 + 系统字体库）
    val availableFonts = remember { loadAvailableFonts() }
    
    var fontSize by remember { mutableFloatStateOf(readingPrefs.getFloat("font_size", 18f)) }
    var lineHeight by remember { mutableFloatStateOf(readingPrefs.getFloat("line_height", 1.8f)) }
    var currentTheme by remember {
        val name = readingPrefs.getString("theme", ReadingTheme.LIGHT.name)
        mutableStateOf(ReadingTheme.entries.find { it.name == name } ?: ReadingTheme.LIGHT)
    }
    var currentFont by remember {
        val key = readingPrefs.getString("font", "serif")
        mutableStateOf(availableFonts.find { it.key == key } ?: availableFonts[1])
    }
    var textAlign by remember {
        val name = readingPrefs.getString("align", "JUSTIFY")
        mutableStateOf(when (name) { "LEFT" -> TextAlign.Left; "CENTER" -> TextAlign.Center; else -> TextAlign.Justify })
    }
    var brightness by remember {
        mutableFloatStateOf(readingPrefs.getFloat("brightness", -1f)) // -1 = 跟随系统
    }

    // 持久化阅读设置
    fun saveReadingSettings() {
        readingPrefs.edit()
            .putFloat("font_size", fontSize)
            .putFloat("line_height", lineHeight)
            .putString("theme", currentTheme.name)
            .putString("font", currentFont.key)
            .putString("align", when (textAlign) { TextAlign.Left -> "LEFT"; TextAlign.Center -> "CENTER"; else -> "JUSTIFY" })
            .putFloat("brightness", brightness)
            .apply()
    }

    val scope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()
    
    var hasRestoredProgress by remember { mutableStateOf(false) }
    var hasAppliedScrollPosition by remember { mutableStateOf(false) }

    LaunchedEffect(bookId, chapters) {
        if (!hasRestoredProgress && chapters.isNotEmpty()) {
            val progress = viewModel.getProgress(bookId)
            if (progress != null) {
                val lastChapter = chapters.find { it.id == progress.lastChapterId }
                if (lastChapter != null) {
                    selectedChapter = lastChapter
                    hasRestoredProgress = true
                }
            }
            if (progress == null && selectedChapter == null) {
                selectedChapter = chapters.first()
            }
        }
    }

    LaunchedEffect(selectedChapter?.id, hasAppliedScrollPosition) {
        if (hasRestoredProgress && !hasAppliedScrollPosition && selectedChapter != null) {
            val progress = viewModel.getProgress(bookId)
            if (progress != null) {
                val chapterIndex = chapters.indexOfFirst { it.id == progress.lastChapterId }
                if (chapterIndex >= 0) {
                    delay(100)
                    lazyListState.scrollToItem(chapterIndex, progress.scrollPosition)
                    hasAppliedScrollPosition = true
                }
            }
        }
    }

    // 跟踪当前可见章节
    val currentVisibleChapterIndex by remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            if (layoutInfo.visibleItemsInfo.isNotEmpty()) {
                layoutInfo.visibleItemsInfo.first().index.coerceIn(0, chapters.lastIndex)
            } else {
                chapters.indexOf(selectedChapter).coerceAtLeast(0)
            }
        }
    }

    LaunchedEffect(currentVisibleChapterIndex) {
        if (chapters.isNotEmpty() && currentVisibleChapterIndex < chapters.size) {
            val chapter = chapters[currentVisibleChapterIndex]
            if (chapter.id != selectedChapter?.id) {
                selectedChapter = chapter
            }
            delay(500)
            val offset = lazyListState.firstVisibleItemScrollOffset
            viewModel.saveProgress(bookId, chapter.id, offset)
        }
    }

    BackHandler {
        if (showSettingsPanel) {
            showSettingsPanel = false
        } else if (showChapterList) {
            showChapterList = false
        } else {
            onBack()
        }
    }

    val toggleToolbar = {
        if (!showSettingsPanel && !showChapterList) {
            showToolbar = !showToolbar
        }
    }

    // 沉浸式阅读：纯净模式隐藏系统状态栏和导航栏
    val view = LocalView.current
    DisposableEffect(showToolbar, showSettingsPanel, showChapterList) {
        val window = (view.context as android.app.Activity).window
        val controller = WindowInsetsControllerCompat(window, view)
        val shouldHideSystemBars = !showToolbar && !showSettingsPanel && !showChapterList
        if (shouldHideSystemBars) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 应用亮度到窗口
    LaunchedEffect(brightness) {
        val window = (view.context as android.app.Activity).window
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
    }
    // 退出阅读时恢复跟随系统亮度
    DisposableEffect(Unit) {
        onDispose {
            val window = (view.context as android.app.Activity).window
            val lp = window.attributes
            lp.screenBrightness = -1f
            window.attributes = lp
        }
    }

    val scrollProgress by remember {
        derivedStateOf {
            if (chapters.isEmpty()) return@derivedStateOf 0f
            val layoutInfo = lazyListState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf 0.001f

            val firstVisible = visibleItems.first()
            val currentIndex = firstVisible.index.coerceIn(0, chapters.lastIndex)

            val charCounts = chapters.map { it.content.length + it.title.length }
            val totalChars = charCounts.sum().coerceAtLeast(1)
            val prevChars = charCounts.take(currentIndex).sum()
            val currentChapterChars = charCounts[currentIndex].coerceAtLeast(1)

            val itemHeight = firstVisible.size.toFloat().coerceAtLeast(1f)
            val scrollOffsetInItem = lazyListState.firstVisibleItemScrollOffset.toFloat()
            val intraChapterProgress = (scrollOffsetInItem / itemHeight).coerceIn(0f, 1f)

            val rawProgress = (prevChars + currentChapterChars * intraChapterProgress) / totalChars
            ((rawProgress * 1000).toInt() / 1000f).coerceIn(0.001f, 1f)
        }
    }

    Scaffold(
        containerColor = currentTheme.bgColor
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(currentTheme.bgColor)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (chapters.isNotEmpty()) {
                    ReadingContent(
                        chapters = chapters,
                        fontSize = fontSize,
                        lineHeight = lineHeight,
                        theme = currentTheme,
                        fontFamily = currentFont.fontFamily,
                        textAlign = textAlign,
                        lazyListState = lazyListState,
                        onTap = toggleToolbar
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = currentTheme.textColor)
                    }
                }
            }

            AnimatedVisibility(
                visible = showToolbar,
                enter = slideInVertically(initialOffsetY = { -it }),
                exit = slideOutVertically(targetOffsetY = { -it }),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = currentTheme.bgColor.copy(alpha = 0.95f),
                            titleContentColor = currentTheme.textColor,
                            navigationIconContentColor = currentTheme.textColor,
                            actionIconContentColor = currentTheme.textColor
                        ),
                        title = {},
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        },
                        actions = {
                            IconButton(onClick = { showSettingsPanel = !showSettingsPanel }) {
                                Icon(Icons.Default.Settings, contentDescription = "阅读设置")
                            }
                            IconButton(onClick = { showChapterList = !showChapterList }) {
                                Icon(Icons.Default.List, contentDescription = "目录")
                            }
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = showToolbar && selectedChapter != null,
                enter = slideInVertically(initialOffsetY = { it }),
                exit = slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(currentTheme.bgColor.copy(alpha = 0.95f))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (chapters.isNotEmpty()) {
                        LinearProgressIndicator(
                            progress = { scrollProgress },
                            modifier = Modifier.fillMaxWidth().height(3.dp).padding(bottom = 8.dp),
                            color = currentTheme.titleColor,
                            trackColor = currentTheme.textColor.copy(alpha = 0.2f)
                        )
                        Text(
                            String.format("%.1f%%", scrollProgress * 100),
                            fontSize = 12.sp,
                            color = currentTheme.textColor.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                if (showChapterList) {
                    ChapterListPanel(
                        chapters = chapters,
                        selectedChapter = selectedChapter,
                        theme = currentTheme,
                        onChapterSelected = { chapter ->
                            selectedChapter = chapter
                            hasAppliedScrollPosition = false
                            val chapterIndex = chapters.indexOf(chapter)
                            if (chapterIndex >= 0) {
                                scope.launch { lazyListState.scrollToItem(chapterIndex) }
                            }
                            showChapterList = false
                        }
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black.copy(alpha = 0.3f))
                            .clickable { showChapterList = false }
                    )
                }
            }

            if (showSettingsPanel) {
                ReadingSettingsPanel(
                    fontSize = fontSize,
                    onFontSizeChange = { newFontSize -> fontSize = newFontSize; saveReadingSettings() },
                    lineHeight = lineHeight,
                    onLineHeightChange = { newLineHeight -> lineHeight = newLineHeight; saveReadingSettings() },
                    currentTheme = currentTheme,
                    onThemeChange = { newTheme -> currentTheme = newTheme; saveReadingSettings() },
                    currentFont = currentFont,
                    availableFonts = availableFonts,
                    onFontChange = { newFont -> currentFont = newFont; saveReadingSettings() },
                    onFontPickerClick = { showFontPicker = true },
                    textAlign = textAlign,
                    onTextAlignChange = { newTextAlign -> textAlign = newTextAlign; saveReadingSettings() },
                    brightness = brightness,
                    onBrightnessChange = { newBrightness -> brightness = newBrightness; saveReadingSettings() },
                    onDismiss = { showSettingsPanel = false }
                )
            }

            if (showFontPicker) {
                FontPickerDialog(
                    fonts = availableFonts,
                    currentFont = currentFont,
                    theme = currentTheme,
                    onFontSelected = { font ->
                        currentFont = font
                        saveReadingSettings()
                        showFontPicker = false
                    },
                    onDismiss = { showFontPicker = false }
                )
            }
        }
    }
}

sealed class ContentBlock {
    data class Heading(val level: Int, val text: String) : ContentBlock()
    data class Paragraph(val text: String) : ContentBlock()
    data class EmptyLine(val text: String) : ContentBlock()
}

fun parseContent(content: String): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    val lines = content.split("\n")
    
    for (line in lines) {
        val trimmed = line.trim()
        
        if (trimmed.isEmpty()) {
            blocks.add(ContentBlock.EmptyLine(line))
            continue
        }
        
        val headingMatch = Regex("^(#{1,6})\\s+(.+)").find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trim()
            blocks.add(ContentBlock.Heading(level, text))
            continue
        }
        
        blocks.add(ContentBlock.Paragraph(line))
    }
    
    return blocks
}

@Composable
private fun ReadingContent(
    chapters: List<Chapter>,
    fontSize: Float,
    lineHeight: Float,
    theme: ReadingTheme,
    fontFamily: FontFamily,
    textAlign: TextAlign,
    lazyListState: LazyListState,
    onTap: () -> Unit
) {
    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .background(theme.bgColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onTap() })
            }
    ) {
        items(chapters) { chapter ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp)
            ) {
                Text(
                    text = chapter.title,
                    fontSize = (fontSize + 4).sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily,
                    color = theme.titleColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                )
                
                HorizontalDivider(
                    color = theme.textColor.copy(alpha = 0.2f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                val contentBlocks = parseContent(chapter.content)
                contentBlocks.forEach { block ->
                    when (block) {
                        is ContentBlock.Heading -> {
                            val headingFontSize = when (block.level) {
                                1 -> fontSize + 8
                                2 -> fontSize + 6
                                3 -> fontSize + 4
                                4 -> fontSize + 2
                                5 -> fontSize + 1
                                else -> fontSize
                            }
                            Text(
                                text = block.text,
                                fontSize = headingFontSize.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = fontFamily,
                                textAlign = textAlign,
                                color = theme.titleColor,
                                softWrap = true,
                                modifier = Modifier
                                    .padding(top = 16.dp, bottom = 8.dp)
                            )
                        }
                        is ContentBlock.Paragraph -> {
                            Text(
                                text = block.text,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * lineHeight).sp,
                                fontFamily = fontFamily,
                                textAlign = textAlign,
                                color = theme.textColor,
                                softWrap = true,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        is ContentBlock.EmptyLine -> {
                            Spacer(modifier = Modifier.height((fontSize * 0.5f).dp))
                        }
                    }
                }
            }
        }
        // 底部留白，确保最后内容可完整阅读
        item {
            Spacer(modifier = Modifier.height(64.dp))
        }
    }
}

@Composable
private fun ChapterListPanel(
    chapters: List<Chapter>,
    selectedChapter: Chapter?,
    theme: ReadingTheme,
    onChapterSelected: (Chapter) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(theme.bgColor)
            .padding(8.dp)
    ) {
        Text(
            "目录",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = theme.textColor,
            modifier = Modifier.padding(12.dp)
        )
        HorizontalDivider(color = theme.textColor.copy(alpha = 0.2f))
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            chapters.forEach { chapter ->
                val isSelected = chapter.id == selectedChapter?.id
                ListItem(
                    colors = ListItemDefaults.colors(
                        containerColor = if (isSelected) theme.titleColor.copy(alpha = 0.15f) else Color.Transparent,
                        headlineColor = if (isSelected) theme.titleColor else theme.textColor
                    ),
                    headlineContent = {
                        Text(
                            chapter.title,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingContent = {
                        Text(
                            "${chapter.chapterNumber}",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.textColor.copy(alpha = 0.6f)
                        )
                    },
                    modifier = Modifier.clickable { onChapterSelected(chapter) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReadingSettingsPanel(
    fontSize: Float,
    onFontSizeChange: (Float) -> Unit,
    lineHeight: Float,
    onLineHeightChange: (Float) -> Unit,
    currentTheme: ReadingTheme,
    onThemeChange: (ReadingTheme) -> Unit,
    currentFont: FontOption,
    availableFonts: List<FontOption>,
    onFontChange: (FontOption) -> Unit,
    onFontPickerClick: () -> Unit,
    textAlign: TextAlign,
    onTextAlignChange: (TextAlign) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val scrimColor = Color.Black.copy(alpha = 0.4f)
    val panelColor = currentTheme.bgColor
    val textColor = currentTheme.textColor
    val accentColor = currentTheme.titleColor
    val mutedColor = textColor.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scrimColor)
            .clickable { onDismiss() }
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(panelColor)
                .clickable(enabled = false) { }
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            // —— 字号 ——
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字号", color = textColor, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                Text("小", color = mutedColor, fontSize = 12.sp)
                Slider(
                    value = fontSize,
                    onValueChange = { onFontSizeChange(it) },
                    valueRange = 12f..36f,
                    steps = 11,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = mutedColor.copy(alpha = 0.3f)
                    )
                )
                Text("大", color = mutedColor, fontSize = 14.sp)
                Text(
                    "${fontSize.toInt()}",
                    color = accentColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp).width(28.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(18.dp))

            // —— 行距 ——
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("行距", color = textColor, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                Text("紧", color = mutedColor, fontSize = 12.sp)
                Slider(
                    value = lineHeight,
                    onValueChange = { onLineHeightChange(it) },
                    valueRange = 1.2f..2.5f,
                    steps = 12,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = mutedColor.copy(alpha = 0.3f)
                    )
                )
                Text("疏", color = mutedColor, fontSize = 14.sp)
                Text(
                    String.format("%.1f", lineHeight),
                    color = accentColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp).width(28.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(Modifier.height(20.dp))

            // —— 背景主题 ——
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("背景", color = textColor, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                ReadingTheme.entries.forEach { theme ->
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .size(34.dp)
                            .background(theme.bgColor, RoundedCornerShape(50))
                            .border(
                                width = if (currentTheme == theme) 2.5.dp else 0.dp,
                                color = if (currentTheme == theme) accentColor else Color.Transparent,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { onThemeChange(theme) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentTheme == theme) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = theme.textColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // —— 字体 ——
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onFontPickerClick() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("字体", color = textColor, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                Text(
                    currentFont.displayName,
                    fontSize = 15.sp,
                    color = textColor,
                    fontFamily = currentFont.fontFamily,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "${availableFonts.size} 种",
                    fontSize = 12.sp,
                    color = mutedColor,
                )
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = "选择字体",
                    tint = mutedColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.height(18.dp))

            // —— 对齐 ——
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("对齐", color = textColor, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                listOf(
                    Triple(Icons.Default.FormatAlignLeft, "左对齐", TextAlign.Left),
                    Triple(Icons.Default.FormatAlignCenter, "居中", TextAlign.Center),
                    Triple(Icons.Default.FormatAlignJustify, "两端", TextAlign.Justify)
                ).forEach { (icon, label, align) ->
                    val selected = textAlign == align
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .background(
                                if (selected) accentColor else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                width = 1.dp,
                                color = if (selected) accentColor else mutedColor.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .clickable { onTextAlignChange(align) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (selected) panelColor else textColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            label,
                            fontSize = 13.sp,
                            color = if (selected) panelColor else textColor
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // —— 亮度 ——
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("亮度", color = textColor, fontSize = 14.sp, modifier = Modifier.width(48.dp))
                Icon(
                    Icons.Default.BrightnessLow,
                    contentDescription = null,
                    tint = mutedColor,
                    modifier = Modifier.size(18.dp)
                )
                Slider(
                    value = if (brightness < 0) 0.5f else brightness,
                    onValueChange = { onBrightnessChange(it) },
                    valueRange = 0.05f..1f,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = accentColor,
                        activeTrackColor = accentColor,
                        inactiveTrackColor = mutedColor.copy(alpha = 0.3f)
                    )
                )
                Icon(
                    Icons.Default.BrightnessHigh,
                    contentDescription = null,
                    tint = mutedColor,
                    modifier = Modifier.size(20.dp)
                )
                if (brightness < 0) {
                    Text(
                        "自动",
                        fontSize = 12.sp,
                        color = mutedColor,
                        modifier = Modifier.padding(start = 8.dp).width(32.dp),
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "${(brightness * 100).toInt()}",
                        fontSize = 13.sp,
                        color = accentColor,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 8.dp).width(32.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FontPickerDialog(
    fonts: List<FontOption>,
    currentFont: FontOption,
    theme: ReadingTheme,
    onFontSelected: (FontOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    val bgColor = theme.bgColor
    val textColor = theme.textColor
    val accentColor = theme.titleColor
    val mutedColor = textColor.copy(alpha = 0.5f)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bgColor,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .background(mutedColor.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                "选择字体",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                "共 ${fonts.size} 种字体可用",
                fontSize = 12.sp,
                color = mutedColor,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            LazyColumn(
                modifier = Modifier.weight(1f, fill = false)
            ) {
                items(fonts) { font ->
                    val selected = currentFont.key == font.key
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onFontSelected(font) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 预览：用该字体渲染示例文字
                        Text(
                            "永和九年岁在癸丑",
                            fontSize = 16.sp,
                            fontFamily = font.fontFamily,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            font.displayName,
                            fontSize = 13.sp,
                            color = mutedColor,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "已选",
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        } else {
                            Spacer(Modifier.width(20.dp))
                        }
                    }
                    HorizontalDivider(
                        color = mutedColor.copy(alpha = 0.15f),
                        thickness = 0.5.dp
                    )
                }
            }
        }
    }
}