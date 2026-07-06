package com.example.tulin_libarary.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.tulin_libarary.data.DefaultPrompts
import com.example.tulin_libarary.data.ModelInfo
import com.example.tulin_libarary.data.SiliconFlowApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()
    val apiService = remember { SiliconFlowApiService() }

    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var baseUrl by remember { mutableStateOf(prefs.getString("base_url", "https://api.siliconflow.cn/v1") ?: "") }
    var model by remember { mutableStateOf(prefs.getString("model", "") ?: "") }
    var imagesModel by remember { mutableStateOf(prefs.getString("images_model", "Kwai-Kolors/Kolors") ?: "") }
    var imagesBaseUrl by remember { mutableStateOf(prefs.getString("images_base_url", "https://api.siliconflow.cn/v1") ?: "") }
    var imagesApiKey by remember { mutableStateOf(prefs.getString("images_api_key", "") ?: "") }
    var imagePrompt by remember { mutableStateOf(prefs.getString("image_prompt", "你是一个书籍封面制作大师，能够绘制任何书籍的最佳封面，请将用户的输入升华具有大师水准的准确且标准且丰富的的英文绘图提示词，以便绘图模型能够完美绘制。") ?: "") }
    var outlineSystemPrompt by remember {
        mutableStateOf(
            prefs.getString("outline_system_prompt", DefaultPrompts.OUTLINE_SYSTEM_PROMPT)
                ?.takeIf { it.isNotBlank() }
                ?: DefaultPrompts.OUTLINE_SYSTEM_PROMPT
        )
    }
    var chapterSystemPrompt by remember {
        mutableStateOf(
            prefs.getString("chapter_system_prompt", DefaultPrompts.CHAPTER_SYSTEM_PROMPT)
                ?.takeIf { it.isNotBlank() }
                ?: DefaultPrompts.CHAPTER_SYSTEM_PROMPT
        )
    }
    var maxChapters by remember {
        mutableIntStateOf(
            prefs.getInt("max_chapters", 100).coerceIn(1, 500)
        )
    }

    // 模型列表状态
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var isLoadingModels by remember { mutableStateOf(false) }
    var modelLoadError by remember { mutableStateOf<String?>(null) }
    var showModelDropdown by remember { mutableStateOf(false) }
    
    var showSaveDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    // 一键恢复默认设置
    fun resetAllToDefault() {
        apiKey = ""
        baseUrl = "https://api.siliconflow.cn/v1"
        model = ""
        imagesModel = "Kwai-Kolors/Kolors"
        imagesBaseUrl = "https://api.siliconflow.cn/v1"
        imagesApiKey = ""
        imagePrompt = "你是一个书籍封面制作大师，能够绘制任何书籍的最佳封面，请将用户的输入升华具有大师水准的准确且标准且丰富的的英文绘图提示词，以便绘图模型能够完美绘制。"
        outlineSystemPrompt = DefaultPrompts.OUTLINE_SYSTEM_PROMPT
        chapterSystemPrompt = DefaultPrompts.CHAPTER_SYSTEM_PROMPT
        maxChapters = 100
        models = emptyList()
        modelLoadError = null
        showModelDropdown = false
    }

    // 系统返回键安全返回
    BackHandler {
        onBack()
    }

    // 页面加载时自动获取模型列表
    LaunchedEffect(Unit) {
        if (apiKey.isNotBlank() && baseUrl.isNotBlank()) {
            isLoadingModels = true
            try {
                val fetchedModels = withContext(Dispatchers.IO) {
                    apiService.fetchModels(apiKey, baseUrl)
                }
                models = fetchedModels
                modelLoadError = null
                // 如果当前没有选中模型或选中的模型不在列表中，自动选择第一个
                if (model.isBlank() || fetchedModels.none { it.id == model }) {
                    model = fetchedModels.firstOrNull()?.id ?: ""
                }
            } catch (e: Exception) {
                modelLoadError = "自动加载失败: ${e.message}"
            } finally {
                isLoadingModels = false
            }
        }
    }

    // 加载模型列表
    fun loadModels() {
        if (apiKey.isBlank()) {
            modelLoadError = "请先输入 API Key"
            return
        }
        if (baseUrl.isBlank()) {
            modelLoadError = "请先输入 Base URL"
            return
        }
        
        scope.launch {
            isLoadingModels = true
            modelLoadError = null
            try {
                val fetchedModels = withContext(Dispatchers.IO) {
                    apiService.fetchModels(apiKey, baseUrl)
                }
                models = fetchedModels
                // 如果当前选中的模型不在新列表中，自动选择第一个
                if (model.isBlank() || fetchedModels.none { it.id == model }) {
                    model = fetchedModels.firstOrNull()?.id ?: ""
                }
            } catch (e: Exception) {
                modelLoadError = "加载失败: ${e.message}"
                models = emptyList()
            } finally {
                isLoadingModels = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 一键恢复默认设置
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "恢复默认设置",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "将所有模型设置恢复为出厂默认值",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                        )
                    }
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("一键恢复")
                    }
                }
            }

            // API配置区域
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "API 配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.siliconflow.cn/v1") },
                        singleLine = true
                    )
                }
            }

            // 模型选择区域
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "选择模型",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        // 刷新按钮
                        IconButton(
                            onClick = { loadModels() },
                            enabled = !isLoadingModels
                        ) {
                            if (isLoadingModels) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "刷新模型列表"
                                )
                            }
                        }
                    }

                    // 错误提示
                    if (modelLoadError != null) {
                        Text(
                            text = modelLoadError!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    // 模型下拉选择器
                    if (models.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = showModelDropdown,
                            onExpandedChange = { showModelDropdown = it }
                        ) {
                            OutlinedTextField(
                                value = model,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(),
                                label = { Text("已加载 ${models.size} 个模型") },
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(
                                        expanded = showModelDropdown
                                    )
                                },
                                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
                            )
                            
                            ExposedDropdownMenu(
                                expanded = showModelDropdown,
                                onDismissRequest = { showModelDropdown = false }
                            ) {
                                models.forEach { modelInfo ->
                                    DropdownMenuItem(
                                        text = { Text(modelInfo.id) },
                                        onClick = {
                                            model = modelInfo.id
                                            showModelDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    } else if (!isLoadingModels && modelLoadError == null) {
                        Text(
                            text = "点击刷新按钮加载模型列表",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 图片生成配置区域
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "图片生成配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = imagesApiKey,
                        onValueChange = { imagesApiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("图片API Key") },
                        placeholder = { Text("留空则使用上方API配置的Key") },
                        singleLine = true
                    )

                    Text(
                        "图片模型供应商的独立API Key，用于封面图片生成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = imagesModel,
                        onValueChange = { imagesModel = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("图片模型") },
                        placeholder = { Text("Kwai-Kolors/Kolors") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = imagesBaseUrl,
                        onValueChange = { imagesBaseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("图片API地址") },
                        placeholder = { Text("https://api.siliconflow.cn/v1") },
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = imagePrompt,
                        onValueChange = { imagePrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp),
                        label = { Text("封面提示词模板") },
                        placeholder = { Text("你是一个书籍封面制作大师...") },
                        maxLines = 4
                    )
                }
            }

            // 创作设置
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "创作设置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "最大章节数",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            "$maxChapters 章",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Slider(
                        value = maxChapters.toFloat(),
                        onValueChange = { maxChapters = it.toInt().coerceIn(1, 500) },
                        valueRange = 1f..500f,
                        steps = 498,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        "AI 生成书籍时允许的最大章节数量，范围 1–500。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // AI写作系统提示词配置
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "AI写作系统提示词",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        // 恢复默认按钮
                        TextButton(
                            onClick = {
                                outlineSystemPrompt = DefaultPrompts.OUTLINE_SYSTEM_PROMPT
                                chapterSystemPrompt = DefaultPrompts.CHAPTER_SYSTEM_PROMPT
                            }
                        ) {
                            Text(
                                "恢复默认",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Text(
                        "自定义系统提示词可以更精细地控制AI的写作风格。留空则使用默认提示词。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = outlineSystemPrompt,
                        onValueChange = { outlineSystemPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        label = { Text("大纲生成提示词") },
                        placeholder = { Text(DefaultPrompts.OUTLINE_SYSTEM_PROMPT) },
                        maxLines = 8
                    )

                    OutlinedTextField(
                        value = chapterSystemPrompt,
                        onValueChange = { chapterSystemPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp),
                        label = { Text("章节内容提示词") },
                        placeholder = { Text(DefaultPrompts.CHAPTER_SYSTEM_PROMPT) },
                        maxLines = 6
                    )
                }
            }

            // 保存按钮
            Button(
                onClick = {
                    prefs.edit()
                        .putString("api_key", apiKey)
                        .putString("base_url", baseUrl)
                        .putString("model", model)
                        .putString("images_model", imagesModel)
                        .putString("images_base_url", imagesBaseUrl)
                        .putString("images_api_key", imagesApiKey)
                        .putString("image_prompt", imagePrompt)
                        .putString("outline_system_prompt", outlineSystemPrompt)
                        .putString("chapter_system_prompt", chapterSystemPrompt)
                        .putInt("max_chapters", maxChapters.coerceIn(1, 500))
                        .apply()
                    showSaveDialog = true
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = model.isNotBlank()
            ) {
                Text("保存设置")
            }

            // 关于信息
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "关于",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "图灵图书馆 v1.0",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "让每个人都能拥有一个个性化掌上图书馆",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "支持所有 OpenAI API 兼容的服务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 使用文档
            val docUrl = "https://lcniv5vgx901.feishu.cn/wiki/BoVtwV3aQiPEQHkbiVQcvyr2nIg"
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(docUrl))
                        context.startActivity(intent)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        "使用文档",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        docUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    // 保存成功提示
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存成功") },
            text = { Text("设置已保存，新的配置将在下次生成书籍时生效。") },
            confirmButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("确定")
                }
            }
        )
    }

    // 恢复默认确认对话框
    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("恢复默认设置") },
            text = { Text("确定要将所有模型设置恢复为出厂默认值吗？此操作会清空当前所有配置（包括 API Key、模型选择、提示词等），且不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetAllToDefault()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("确定恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            }
        )
    }
}
