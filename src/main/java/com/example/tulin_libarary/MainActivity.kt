package com.example.tulin_libarary

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

import com.example.tulin_libarary.service.BookGenerationService
import com.example.tulin_libarary.ui.screens.*
import com.example.tulin_libarary.ui.theme.Tulin_libararyTheme
import com.example.tulin_libarary.viewmodel.LibraryViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            Tulin_libararyTheme {
                // 设置原生窗口背景色与主题一致，防止转场时白屏
                val bgColor = MaterialTheme.colorScheme.background.toArgb()
                LaunchedEffect(bgColor) {
                    window.setBackgroundDrawable(ColorDrawable(bgColor))
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // 处理来自通知的意图（点击通知打开书籍）
                    var pendingBookId by remember { mutableStateOf<Long?>(null) }
                    LaunchedEffect(intent) {
                        handleNotificationIntent(intent)?.let { bookId ->
                            pendingBookId = bookId
                        }
                    }

                    LibraryApp(
                        initialBookId = pendingBookId,
                        onBookOpened = { pendingBookId = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    /**
     * 解析通知意图：如果带有 EXTRA_OPEN_READER 与 EXTRA_BOOK_ID，则返回书籍ID
     */
    private fun handleNotificationIntent(intent: Intent?): Long? {
        intent ?: return null
        val openReader = intent.getBooleanExtra(BookGenerationService.EXTRA_OPEN_READER, false)
        if (!openReader) return null
        val bookId = intent.getLongExtra(BookGenerationService.EXTRA_BOOK_ID, -1L)
        return if (bookId > 0) bookId else null
    }
}

/**
 * 安全返回：防止连续点击返回导致导航栈被清空白屏
 */
fun safePopBackStack(navController: androidx.navigation.NavController) {
    if (navController.previousBackStackEntry != null) {
        navController.popBackStack()
    }
}

@Composable
fun LibraryApp(
    initialBookId: Long? = null,
    onBookOpened: () -> Unit = {}
) {
    val navController = rememberNavController()
    val viewModel: LibraryViewModel = viewModel()

    // 收到通知点击事件后，跳转到对应书籍的阅读页
    LaunchedEffect(initialBookId) {
        val bookId = initialBookId
        if (bookId != null && bookId > 0) {
            navController.navigate("reader/$bookId") {
                popUpTo("library")
                launchSingleTop = true
            }
            onBookOpened()
        }
    }

    NavHost(
        navController = navController,
        startDestination = "library"
    ) {
        composable("library") {
            LibraryScreen(
                viewModel = viewModel,
                onBookClick = { bookId ->
                    navController.navigate("reader/$bookId") {
                        launchSingleTop = true
                    }
                },
                onGenerateClick = {
                    navController.navigate("generate") {
                        launchSingleTop = true
                    }
                },
                onSettingsClick = {
                    navController.navigate("settings") {
                        launchSingleTop = true
                    }
                },
                onViewGeneration = {
                    navController.navigate("generate") {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("generate") {
            GenerateScreen(
                viewModel = viewModel,
                onBack = { safePopBackStack(navController) },
                onBookGenerated = { bookId ->
                    navController.navigate("reader/$bookId") {
                        popUpTo("library")
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("reader/{bookId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId")?.toLongOrNull() ?: return@composable
            ReaderScreen(
                bookId = bookId,
                viewModel = viewModel,
                onBack = { safePopBackStack(navController) }
            )
        }

        composable("settings") {
            SettingsScreen(
                onBack = { safePopBackStack(navController) }
            )
        }
    }
}
