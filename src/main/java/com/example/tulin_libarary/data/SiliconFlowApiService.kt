@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.example.tulin_libarary.data

import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class ChatMessage(val role: String, val content: String)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 4096,
    val top_p: Double = 0.9
)

@Serializable
data class ChatChoice(
    val message: ChatMessage,
    val index: Int = 0
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList()
)

/**
 * 模型列表响应（OpenAI API 兼容格式）
 */
@Serializable
data class ModelsResponse(
    val data: List<ModelData> = emptyList()
)

@Serializable
data class ModelData(
    val id: String,
    @SerialName("object") val objectType: String = "",
    val created: Long = 0,
    val owned_by: String = ""
)

/**
 * 模型信息（UI 层使用）
 */
data class ModelInfo(val id: String)

@Serializable
data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val seed: Int = 1,
    @SerialName("image_size") val imageSize: String = "720x1280",
    @SerialName("num_inference_steps") val numInferenceSteps: Int = 40,
    @SerialName("negative_prompt") val negativePrompt: String = "bad anime, bad illustration,Incomplete body,lowres, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry, artist name"
)

@Serializable
data class ImageResponse(
    val images: List<ImageData> = emptyList()
)

@Serializable
data class ImageData(
    val url: String = ""
)

class SiliconFlowApiService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generateImagePrompt(
        apiKey: String,
        baseUrl: String,
        model: String,
        imagePrompt: String,
        bookOutline: String
    ): String {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"
        val systemPrompt = "$imagePrompt 请直接使用英文短语形式合理地输出绘图提示词。"

        val requestBody = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = bookOutline)
            ),
            temperature = 0.0,
            max_tokens = 512,
            top_p = 0.9
        )
        val bodyJson = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return executeWithRetry(request)
    }

    suspend fun generateImage(
        apiKey: String,
        imagesBaseUrl: String,
        imagesModel: String,
        prompt: String,
        seed: Int = 1
    ): String {
        val url = "${imagesBaseUrl.trimEnd('/')}/images/generations"

        val requestBody = ImageGenerationRequest(
            model = imagesModel,
            prompt = prompt,
            seed = seed,
            imageSize = "720x1280",
            numInferenceSteps = 40,
            negativePrompt = "bad anime, bad illustration,Incomplete body,lowres, text, error, missing fingers, extra digit, fewer digits, cropped, worst quality, low quality, normal quality, jpeg artifacts, signature, watermark, username, blurry, artist name"
        )
        val bodyJson = json.encodeToString(ImageGenerationRequest.serializer(), requestBody)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("API Error: ${response.code} - $responseBody")
        }

        val parsed = json.decodeFromString<ImageResponse>(responseBody)
        return parsed.images.firstOrNull()?.url ?: throw Exception("No image URL in response")
    }

    suspend fun downloadImage(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val body = response.body ?: throw Exception("Empty response body")

        if (!response.isSuccessful) {
            throw Exception("Download failed: ${response.code}")
        }

        return body.bytes()
    }

    /**
     * 带重试机制的API调用（suspend函数，支持协程取消）
     */
    private suspend fun executeWithRetry(
        request: Request,
        maxRetries: Int = 3,
        initialDelay: Long = 1000
    ): String {
        var lastException: Exception? = null
        var retryDelay = initialDelay

        repeat(maxRetries) { attempt ->
            try {
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    throw Exception("API Error: ${response.code} - $responseBody")
                }

                val parsed = json.decodeFromString<ChatCompletionResponse>(responseBody)
                return parsed.choices.firstOrNull()?.message?.content ?: throw Exception("No content in response")
            } catch (e: Exception) {
                lastException = e
                if (attempt < maxRetries - 1) {
                    // 指数退避重试（使用delay支持协程取消）
                    delay(retryDelay)
                    retryDelay *= 2
                }
            }
        }

        throw lastException ?: Exception("Request failed after $maxRetries retries")
    }

    /**
     * 从 /models 接口获取可用模型列表
     * 兼容 OpenAI API 格式
     */
    fun fetchModels(apiKey: String, baseUrl: String): List<ModelInfo> {
        val url = "${baseUrl.trimEnd('/')}/models"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("获取模型列表失败: ${response.code} - $responseBody")
        }

        val modelsResponse = json.decodeFromString<ModelsResponse>(responseBody)
        return modelsResponse.data.map { ModelInfo(it.id) }
    }

    suspend fun chatCompletion(
        apiKey: String,
        baseUrl: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        temperature: Double = 0.7,
        maxTokens: Int = 4096
    ): String {
        val url = "${baseUrl.trimEnd('/')}/chat/completions"

        // 使用kotlinx.serialization正确构建JSON，避免手动拼接导致转义错误
        val requestBody = ChatCompletionRequest(
            model = model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userPrompt)
            ),
            temperature = temperature,
            max_tokens = maxTokens,
            top_p = 0.9
        )
        val bodyJson = json.encodeToString(ChatCompletionRequest.serializer(), requestBody)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        return executeWithRetry(request)
    }

    private fun escapeJson(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\""
    }
}
