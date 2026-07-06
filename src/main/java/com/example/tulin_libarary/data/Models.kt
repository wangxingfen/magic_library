@file:OptIn(kotlinx.serialization.InternalSerializationApi::class)

package com.example.tulin_libarary.data

import kotlinx.serialization.Serializable

@Serializable
data class Book(
    val id: Long = 0,
    val title: String = "",
    val outline: String = "",
    val coverColor: Long = 0xFF64B5F6,
    val coverImagePath: String = "",
    val totalChapters: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: String = "completed" // generating, completed
)

@Serializable
data class Chapter(
    val id: Long = 0,
    val bookId: Long = 0,
    val chapterNumber: Int = 0,
    val title: String = "",
    val content: String = "",
    val outline: String = ""
)

@Serializable
data class ReadingProgress(
    val bookId: Long = 0,
    val lastChapterId: Long = 0,
    val scrollPosition: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class LibraryData(
    val books: MutableList<Book> = mutableListOf(),
    val chapters: MutableList<Chapter> = mutableListOf(),
    val readingProgress: MutableList<ReadingProgress> = mutableListOf()
)

data class ApiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val imagesModel: String = "",
    val imagesBaseUrl: String = "",
    val imagePrompt: String = "",
    val imagesApiKey: String = "",
    val outlineSystemPrompt: String = "",
    val chapterSystemPrompt: String = "",
    val maxChapters: Int = 100
)

// 默认系统提示词常量
object DefaultPrompts {
    const val OUTLINE_SYSTEM_PROMPT = """你是一位才华横溢的天才作家，请根据用户要求写出书籍大纲，格式如下：

标题：《书名》（自拟标题）
要求：【一句话概括主题】
总章数：共X章
然后输出完整目录，每章一段话介绍大概内容。"""

    const val CHAPTER_SYSTEM_PROMPT = """你是一位INFJ型人格的才华横溢的作家，请根据提供的大纲撰写章节内容。
要求：细节丰富，语言优美，情节引人入胜，1000字左右。"""
}
