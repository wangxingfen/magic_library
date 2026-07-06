package com.example.tulin_libarary.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tulin_libarary.data.Book
import com.example.tulin_libarary.data.Chapter
import com.example.tulin_libarary.data.GenerationState
import com.example.tulin_libarary.data.LibraryRepository
import com.example.tulin_libarary.data.ReadingProgress
import com.example.tulin_libarary.service.BookGenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = LibraryRepository(application)

    private val _allBooks = MutableStateFlow<List<Book>>(emptyList())
    val allBooks: StateFlow<List<Book>> = _allBooks.asStateFlow()

    private val chapterFlowCache = ConcurrentHashMap<Long, StateFlow<List<Chapter>>>()

    val generationState: StateFlow<GenerationState> = BookGenerationService.state

    private val _hasShownOnboarding = MutableStateFlow(false)
    val hasShownOnboarding: StateFlow<Boolean> = _hasShownOnboarding.asStateFlow()

    init {
        refreshBooks()
        observeServiceState()
    }

    private fun observeServiceState() {
        viewModelScope.launch {
            BookGenerationService.state.collect { state ->
                if (state is GenerationState.Success || state is GenerationState.Error) {
                    refreshBooks()
                }
            }
        }
    }

    private fun refreshBooks() {
        viewModelScope.launch {
            val books = withContext(Dispatchers.IO) {
                repository.getAllBooks().first()
            }
            _allBooks.value = books
        }
    }

    fun generateBook(prompt: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, BookGenerationService::class.java).apply {
            action = BookGenerationService.ACTION_START
            putExtra(BookGenerationService.EXTRA_PROMPT, prompt)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelGeneration() {
        val context = getApplication<Application>()
        val intent = Intent(context, BookGenerationService::class.java).apply {
            action = BookGenerationService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            repository.deleteBook(book.id)
            chapterFlowCache.remove(book.id)
            refreshBooks()
        }
    }

    fun getChapters(bookId: Long): StateFlow<List<Chapter>> {
        return chapterFlowCache.getOrPut(bookId) {
            repository.getChapters(bookId)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
        }
    }

    fun refreshChapters(bookId: Long) {
        chapterFlowCache.remove(bookId)
    }

    fun saveProgress(bookId: Long, chapterId: Long, scrollPosition: Int) {
        viewModelScope.launch {
            repository.saveReadingProgress(
                ReadingProgress(
                    bookId = bookId,
                    lastChapterId = chapterId,
                    scrollPosition = scrollPosition
                )
            )
        }
    }

    suspend fun getProgress(bookId: Long): ReadingProgress? {
        return repository.getReadingProgress(bookId)
    }

    fun resetGenerationState() {
        BookGenerationService.resetState()
    }

    fun markOnboardingShown() {
        _hasShownOnboarding.value = true
    }
}
