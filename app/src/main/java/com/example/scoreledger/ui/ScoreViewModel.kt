package com.example.scoreledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.scoreledger.core.ScoreValue
import com.example.scoreledger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreViewModel(private val repository: ScoreRepository) : ViewModel() {
    val children = repository.children.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val subjects = repository.subjects.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val selected = MutableStateFlow<String?>(null)
    val selectedChild: StateFlow<String?> = selected.asStateFlow()
    val exams = selected.flatMapLatest { id ->
        if (id == null) flowOf(emptyList<ExamWithScores>()) else repository.exams(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val message = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = message.asStateFlow()
    private val busy = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = busy.asStateFlow()
    init {
        perform { repository.ensureDefaultSubjects() }
        viewModelScope.launch {
            children.collect { rows ->
                if (rows.none { it.id == selected.value }) selected.value = rows.firstOrNull()?.id
            }
        }
    }
    fun selectChild(id: String) { if (!busy.value) selected.value = id }
    fun addChild(name: String) = perform { selected.value = repository.addChild(name) }
    fun save(id: String, title: String, date: String, values: List<ScoreValue>, onSaved: () -> Unit) = perform {
        val child = requireNotNull(selected.value) { "请先创建孩子档案" }
        repository.saveNewExam(id, child, title, date, values)
        onSaved()
    }
    fun showError(text: String) { message.value = text }
    fun delete(id: String) = perform {
        repository.deleteExam(id, requireNotNull(selected.value))
    }
    private fun perform(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true; message.value = null
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "操作未完成，请重试" }
            finally { busy.value = false }
        }
    }
}
