package com.example.tulin_libarary.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tulin_libarary.data.Book
import com.example.tulin_libarary.data.GenerationState
import com.example.tulin_libarary.viewmodel.LibraryViewModel
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookClick: (Long) -> Unit,
    onGenerateClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onViewGeneration: () -> Unit = {}
) {
    val books by viewModel.allBooks.collectAsState()
    val generationState by viewModel.generationState.collectAsState()
    var showDeleteDialog by remember { mutableStateOf<Book?>(null) }

    val context = LocalContext.current
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val hasShownOnboarding by viewModel.hasShownOnboarding.collectAsState()
    val shouldShowOnboarding = !prefs.getBoolean("onboarding_shown", false) && !hasShownOnboarding
    var showOnboarding by remember { mutableStateOf(shouldShowOnboarding) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "银河百科全书",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onGenerateClick,
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = "AI生成书籍")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 后台生成进度横幅
            if (generationState is GenerationState.Generating) {
                val state = generationState as GenerationState.Generating
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    onClick = onViewGeneration
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "AI正在后台创作中...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                state.step,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { viewModel.cancelGeneration() }
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "取消创作",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }

            if (books.isEmpty() && generationState !is GenerationState.Generating) {
                // 空状态
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(80.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "图书馆还是空的",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "点击右下角按钮，让AI为你创作一本书吧！",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(books, key = { it.id }) { book ->
                        BookCard(
                            book = book,
                            onClick = {
                                // 生成中的书籍点击后跳转到创作页查看进度
                                if (book.status == "generating") {
                                    onViewGeneration()
                                } else {
                                    onBookClick(book.id)
                                }
                            },
                            onLongClick = { showDeleteDialog = book }
                        )
                    }
                }
            }
        }
    }

    // 删除确认对话框
    showDeleteDialog?.let { book ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("删除书籍") },
            text = { Text("确定要删除《${book.title}》吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(book)
                        showDeleteDialog = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("取消")
                }
            }
        )
    }

    // 新手指引对话框
    if (showOnboarding) {
        AlertDialog(
            onDismissRequest = { showOnboarding = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.AutoStories,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("欢迎来到银河百科全书") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这里是 AI 驱动的星际图书馆，你可以：")
                    Text("• 点击右下角 ✨ 按钮，让 AI 为你创作一本书")
                    Text("• 长按书架上的书籍进行管理")
                    Text("• 创作完成后会收到通知，点击即可阅读")
                    Text("• 在右上角设置中调整模型、提示词与章节上限")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = {
                            val intent = Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://lcniv5vgx901.feishu.cn/wiki/BoVtwV3aQiPEQHkbiVQcvyr2nIg")
                            )
                            context.startActivity(intent)
                        },
                        modifier = Modifier.padding(horizontal = 0.dp)
                    ) {
                        Text("查看使用文档")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        prefs.edit().putBoolean("onboarding_shown", true).apply()
                        viewModel.markOnboardingShown()
                        showOnboarding = false
                    }
                ) {
                    Text("知道了，不再提示")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.markOnboardingShown()
                        showOnboarding = false
                    }
                ) {
                    Text("稍后再说")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookCard(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val baseColor = Color(book.coverColor)
    val coverBitmap = remember(book.coverImagePath) {
        if (book.coverImagePath.isNotBlank() && File(book.coverImagePath).exists()) {
            val options = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                inDensity = android.util.DisplayMetrics.DENSITY_DEFAULT
                inScaled = false
            }
            BitmapFactory.decodeFile(book.coverImagePath, options)
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .shadow(
                elevation = 10.dp,
                shape = RoundedCornerShape(2.dp, 10.dp, 10.dp, 2.dp),
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.12f)
            )
            .clip(RoundedCornerShape(2.dp, 10.dp, 10.dp, 2.dp))
            .combinedClickable(
                onClick = { onClick() },
                onLongClick = { onLongClick() }
            )
            .height(260.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (coverBitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = coverBitmap.asImageBitmap(),
                        contentDescription = book.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.High
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(baseColor, baseColor.copy(alpha = 0.7f))
                                )
                            )
                    ) {
                        Icon(
                            Icons.Default.AutoStories,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier
                                .size(64.dp)
                                .align(Alignment.Center)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Black.copy(alpha = 0.7f),
                                    Color.Black.copy(alpha = 0.4f)
                                )
                            )
                        )
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "共${book.totalChapters}章",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                            letterSpacing = 0.3.sp
                        )
                        if (book.status == "generating") {
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.3f)
                            )
                        }
                    }
                }
            }
        }
    }
}
