package com.example.tulin_libarary.data

sealed class GenerationState {
    data object Idle : GenerationState()
    data class Generating(val step: String, val progress: Float) : GenerationState()
    data class Success(val bookId: Long) : GenerationState()
    data class Error(val message: String) : GenerationState()
}
