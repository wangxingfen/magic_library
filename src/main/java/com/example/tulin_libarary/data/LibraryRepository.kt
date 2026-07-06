package com.example.tulin_libarary.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class LibraryRepository(private val context: Context) {
    private val json = Json { 
        ignoreUnknownKeys = true 
        prettyPrint = true
        encodeDefaults = true
    }
    
    private val libraryFile: File
        get() = File(context.filesDir, "library.json")
    
    private fun loadData(): LibraryData {
        return try {
            if (libraryFile.exists()) {
                val content = libraryFile.readText()
                json.decodeFromString<LibraryData>(content)
            } else {
                LibraryData()
            }
        } catch (e: Exception) {
            LibraryData()
        }
    }
    
    private fun saveData(data: LibraryData) {
        try {
            libraryFile.writeText(json.encodeToString(data))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun getAllBooks(): Flow<List<Book>> = flow {
        val data = loadData()
        emit(data.books.sortedByDescending { it.updatedAt })
    }.flowOn(Dispatchers.IO)
    
    suspend fun getBook(bookId: Long): Book? = withContext(Dispatchers.IO) {
        loadData().books.find { it.id == bookId }
    }
    
    suspend fun insertBook(book: Book): Long = withContext(Dispatchers.IO) {
        val data = loadData()
        val newId = if (data.books.isEmpty()) 1L else data.books.maxOf { it.id } + 1
        val newBook = book.copy(id = newId)
        data.books.add(newBook)
        saveData(data)
        newId
    }
    
    suspend fun updateBook(book: Book) = withContext(Dispatchers.IO) {
        val data = loadData()
        val index = data.books.indexOfFirst { it.id == book.id }
        if (index >= 0) {
            data.books[index] = book.copy(updatedAt = System.currentTimeMillis())
            saveData(data)
        }
    }
    
    suspend fun deleteBook(bookId: Long) = withContext(Dispatchers.IO) {
        val data = loadData()
        data.books.removeAll { it.id == bookId }
        data.chapters.removeAll { it.bookId == bookId }
        data.readingProgress.removeAll { it.bookId == bookId }
        saveData(data)
    }
    
    fun getChapters(bookId: Long): Flow<List<Chapter>> = flow {
        val data = loadData()
        emit(data.chapters.filter { it.bookId == bookId }.sortedBy { it.chapterNumber })
    }.flowOn(Dispatchers.IO)
    
    suspend fun getChapter(chapterId: Long): Chapter? = withContext(Dispatchers.IO) {
        loadData().chapters.find { it.id == chapterId }
    }
    
    suspend fun insertChapter(chapter: Chapter): Long = withContext(Dispatchers.IO) {
        val data = loadData()
        val newId = if (data.chapters.isEmpty()) 1L else data.chapters.maxOf { it.id } + 1
        val newChapter = chapter.copy(id = newId)
        data.chapters.add(newChapter)
        saveData(data)
        newId
    }
    
    suspend fun getReadingProgress(bookId: Long): ReadingProgress? = withContext(Dispatchers.IO) {
        loadData().readingProgress.find { it.bookId == bookId }
    }
    
    suspend fun saveReadingProgress(progress: ReadingProgress) = withContext(Dispatchers.IO) {
        val data = loadData()
        data.readingProgress.removeAll { it.bookId == progress.bookId }
        data.readingProgress.add(progress)
        saveData(data)
    }

    suspend fun getBooksByStatus(status: String): List<Book> = withContext(Dispatchers.IO) {
        loadData().books.filter { it.status == status }
    }

    suspend fun getCompletedChapterCount(bookId: Long): Int = withContext(Dispatchers.IO) {
        loadData().chapters.count { it.bookId == bookId }
    }

    suspend fun getLastChapterSummary(bookId: Long): String = withContext(Dispatchers.IO) {
        val data = loadData()
        val lastChapter = data.chapters
            .filter { it.bookId == bookId }
            .maxByOrNull { it.chapterNumber }
        lastChapter?.content?.let { content ->
            val cleanContent = content.replace(Regex("\\s+"), " ").trim()
            val titleLine = cleanContent.lines().firstOrNull { it.contains("第") && it.contains("章") } ?: ""
            val titleContext = if (titleLine.length in 2..50) "\n【上章标题】${titleLine.trim()}" else ""
            if (cleanContent.length <= 200) {
                titleContext + cleanContent
            } else {
                titleContext + "\n【上章结尾】..." + cleanContent.takeLast(200)
            }
        } ?: ""
    }
}
