package com.example.scoreledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.scoreledger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreViewModel(private val repository: ScoreRepository) : ViewModel() {
    val childCounts = repository.childCounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val children = repository.children.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val subjects = repository.subjects.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val selected = MutableStateFlow<String?>(null)
    val selectedChild = selected.asStateFlow()
    val exams = selected.flatMapLatest { id -> if(id == null) flowOf(emptyList()) else repository.exams(id).onStart { emit(emptyList()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val drafts = selected.flatMapLatest { id -> if(id == null) flowOf(emptyList()) else repository.drafts(id).onStart { emit(emptyList()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val message = MutableStateFlow<String?>(null)
    val error = message.asStateFlow()
    private val busy = MutableStateFlow(false)
    val saving = busy.asStateFlow()
    private val draft = MutableStateFlow<EntryDraft?>(null)
    val editor = draft.asStateFlow()
    private val conflict = MutableStateFlow(false)
    val draftConflict = conflict.asStateFlow()
    private val persisted = MutableStateFlow<String?>(null)
    val persistedPayload = persisted.asStateFlow()
    // One FIFO writer: typing, exit, submit and discard cannot overtake each other.
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        viewModelScope.launch { for (operation in operations) {
            try { operation() } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(e is DraftConflictException) conflict.value=true;message.value=e.message ?: "操作未完成，请重试" }
        } }
        viewModelScope.launch { children.collect { rows ->
            if(rows.none { it.id == selected.value }) selected.value=rows.firstOrNull { !it.archived }?.id ?: rows.firstOrNull()?.id
        } }
    }
    fun clearMessage() { message.value=null }
    fun selectChild(id: String) { if(!busy.value && draft.value == null) selected.value=id }
    fun addChild(name: String, grade: String, done: () -> Unit) = perform { selected.value=repository.addChild(name,grade);done() }
    fun updateChild(child: ChildEntity, name: String, grade: String, archived: Boolean, done: () -> Unit) = perform { repository.updateChild(child.id,name,grade,archived);done() }
    fun deleteChild(id: String, done: () -> Unit) = perform { repository.deleteChild(id);done() }
    fun saveSubject(subject: SubjectEntity?, name: String, maximum: Long, order: Int, archived: Boolean, done: () -> Unit) = perform {
        repository.saveSubject(subject?.id,name,maximum,order,archived);done()
    }
    fun template(names: List<String>) = perform { repository.addSubjectTemplate(names) }
    fun openNew() = perform {
        val child=requireNotNull(selected.value)
        val d=EntryDraft(childId=child)
        repository.putDraft(d);persisted.value=d.encode();conflict.value=false;draft.value=d
    }
    fun openDraft(row: DraftEntity) = perform {
        check(row.childId == selected.value)
        val current=requireNotNull(repository.draft(row.id,row.childId));val d=EntryDraft.decode(current)
        persisted.value=d.encode();conflict.value=false;draft.value=d
    }
    fun edit(record: ExamWithScores) = perform {
        check(record.exam.childId == selected.value)
        val existing=repository.draft(record.exam.id,record.exam.childId)
        val d=existing?.let(EntryDraft::decode) ?: EntryDraft.from(record)
        repository.putDraft(d,existing?.payloadJson);persisted.value=d.encode();conflict.value=false;draft.value=d
    }
    fun change(value: EntryDraft) {
        if(busy.value || value.id != draft.value?.id || value.childId != selected.value) return
        draft.value=value
        operations.trySend { repository.putDraft(value,persisted.value);persisted.value=value.encode();conflict.value=false }
    }
    fun closeEditor(discard: Boolean) = perform {
        val d=requireNotNull(draft.value)
        if(discard) repository.discardDraft(d.id,d.childId,persisted.value) else repository.putDraft(d,persisted.value)
        draft.value=null;persisted.value=null
    }
    fun submit() = perform {
        val d=requireNotNull(draft.value)
        check(d.childId == selected.value) { "请返回正确的孩子档案" }
        repository.putDraft(d,persisted.value) // Compare-and-swap also protects a retry after failed autosave.
        persisted.value=d.encode()
        repository.submitDraft(d)
        draft.value=null;persisted.value=null;message.value="记录已保存"
    }
    fun retryDraft() = perform {
        val d=requireNotNull(draft.value)
        repository.putDraft(d,persisted.value);persisted.value=d.encode();conflict.value=false
    }
    // Called only after the user explicitly confirms replacing local unsaved fields.
    fun reloadDraft() = perform {
        val d=requireNotNull(draft.value)
        val current=repository.draft(d.id,d.childId)
        check(current != null) { "草稿已提交或删除。本页输入仍保留，请返回查看最新记录。" }
        val latest=EntryDraft.decode(current)
        draft.value=latest;persisted.value=current.payloadJson;conflict.value=false
    }
    fun delete(record: ExamWithScores, done: () -> Unit) = perform {
        repository.deleteExam(record.exam.id,record.exam.childId);done()
    }
    fun showError(text: String) { message.value=text }
    private fun perform(block: suspend () -> Unit) {
        if(busy.value) return
        busy.value=true;message.value=null
        operations.trySend { try { block() } finally { busy.value=false } }
    }
}
