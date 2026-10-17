package com.example.scoreledger.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.scoreledger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

enum class DraftSaveState { Saved, Saving, Error, Conflict }

@OptIn(ExperimentalCoroutinesApi::class)
class ScoreViewModel(internal val repository: ScoreRepository, private val savedState: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    val childCounts = repository.childCounts.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val childLoad = MutableStateFlow(false)
    val childrenLoaded = childLoad.asStateFlow()
    val children = repository.children.onEach { childLoad.value=true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val subjects = repository.subjects.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var visibleGeneration=repository.generation.value
    private var editorGeneration:Long?=null
    private val selected = MutableStateFlow<String?>(savedState["selectedChild"])
    val selectedChild = selected.asStateFlow()
    private val filters = MutableStateFlow<Map<String,RecordQuery>>(restoreQueries())
    val recordQuery = combine(selected, filters) { id, queries -> queries[id] ?: RecordQuery(id.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, filters.value[selected.value] ?: RecordQuery(selected.value.orEmpty()))
    fun setQuery(query: RecordQuery) {
        if(query.childId != selected.value) return
        try {
            query.apply(emptyList()) // Validate the whole candidate before changing the applied scope.
            filters.value=(filters.value + (query.childId to query)).filterKeys { id -> children.value.any { it.id==id } || id==query.childId }
            savedState["queries"]=ArrayList(filters.value.values.map { q -> org.json.JSONObject()
                .put("childId",q.childId).put("from",q.from).put("through",q.through).put("term",q.term)
                .put("kind",q.kind?.name.orEmpty()).put("titleSearch",q.titleSearch).toString() })
        } catch(e: Exception) { message.value=e.message ?: "筛选条件无效" }
    }
    private fun restoreQueries(): Map<String,RecordQuery> =
        savedState.get<ArrayList<String>>("queries").orEmpty().mapNotNull { raw -> runCatching {
            val o=org.json.JSONObject(raw)
            RecordQuery(o.getString("childId"),o.getString("from"),o.getString("through"),o.getString("term"),
                o.optString("kind").takeIf { it.isNotBlank() }?.let(com.example.scoreledger.core.ExamKind::valueOf),o.optString("titleSearch"))
                .also { it.apply(emptyList()) }
        }.getOrNull() }.associateBy { it.childId }

    private val examLoad = MutableStateFlow<String?>(null)
    val loadedExamChildId = examLoad.asStateFlow()
    val exams = selected.flatMapLatest { id -> if(id == null) { examLoad.value=null;flowOf(emptyList()) } else repository.exams(id).onEach { examLoad.value=id }.onStart { examLoad.value=null;emit(emptyList()) } }
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
    private val draftState = MutableStateFlow(DraftSaveState.Saved)
    val draftSaveState = draftState.asStateFlow()
    private val savedId = MutableStateFlow<String?>(null)
    val savedRecordId = savedId.asStateFlow()
    fun consumeSavedRecord() { savedId.value=null;savedState["savedRecordId"]=null;savedState["savedRecordChildId"]=null }
    private fun setSelected(id:String?) {
        if(id!=selected.value) consumeSavedRecord()
        selected.value=id;savedState["selectedChild"]=id
    }
    private suspend fun checkGeneration() {
        check(currentCoroutineContext()[WriteGeneration]?.value==repository.generation.value) { "数据已恢复或清理，请重新打开页面" }
    }
    private fun rememberEditor(d:EntryDraft) {
        savedState["activeDraftId"]=d.id;savedState["activeDraftChildId"]=d.childId
    }
    private fun clearEditor() {
        draft.value=null;persisted.value=null;conflict.value=false;editorGeneration=null
        draftState.value=DraftSaveState.Saved;removedRow=null;rowUndo.value=false;focusSubject.value=null
        savedState["activeDraftId"]=null;savedState["activeDraftChildId"]=null
    }
    private val focusSubject = MutableStateFlow<String?>(null)
    val focusSubjectId = focusSubject.asStateFlow()
    private var deleted: DeletedExam? = null
    private var undoJob: Job? = null
    private val undo = MutableStateFlow(false)
    val undoAvailable = undo.asStateFlow()
    private var removedRow: Pair<Int,DraftRow>? = null
    private val rowUndo = MutableStateFlow(false)
    val rowUndoAvailable = rowUndo.asStateFlow()
    private fun markPersisted(payload:String) {
        persisted.value=payload;conflict.value=false
        draftState.value=if(draft.value == null || draft.value?.encode()==payload) DraftSaveState.Saved else DraftSaveState.Saving
    }
    // One FIFO writer: typing, exit, submit and discard cannot overtake each other.
    private val operations = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        viewModelScope.launch { repository.generation.collect { epoch ->
            if(epoch!=visibleGeneration) {
                val closedEditor=draft.value!=null
                visibleGeneration=epoch;clearEditor()
                deleted=null;undo.value=false;removedRow=null;rowUndo.value=false;consumeSavedRecord()
                // Settings reports its own inline restore/clear result. Only announce an
                // editor closure when one actually happened; do not cover rollback actions.
                if(closedEditor) message.value="数据已恢复或清理，旧编辑页面已关闭"
            }
        } }

        viewModelScope.launch { for (operation in operations) {
            try { operation() } catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                if(e is DraftConflictException) { conflict.value=true;draftState.value=DraftSaveState.Conflict }
                else if(draft.value != null && (draftState.value==DraftSaveState.Saving || draft.value?.encode()!=persisted.value)) draftState.value=DraftSaveState.Error
                message.value=e.message ?: "操作未完成，请重试"
            }
        } }
        viewModelScope.launch { repository.children.collect { rows ->
            if(rows.none { it.id == selected.value }) setSelected(rows.firstOrNull { !it.archived }?.id ?: rows.firstOrNull()?.id)
        } }
        // Store only a locator, never unsaved input. Reopen the last confirmed Room payload.
        val activeId=savedState.get<String>("activeDraftId")
        val activeChild=savedState.get<String>("activeDraftChildId")
        val navigationId=savedState.get<String>("savedRecordId")
        val navigationChild=savedState.get<String>("savedRecordChildId") ?: selected.value
        if(activeId!=null && activeChild!=null || navigationId!=null && navigationChild!=null) perform {
            if(activeId!=null && activeChild!=null) {
                val row=repository.draft(activeId,activeChild)
                checkGeneration()
                if(row!=null) {
                    val restored=EntryDraft.decode(row)
                    consumeSavedRecord();setSelected(activeChild);draft.value=restored;markPersisted(row.payloadJson)
                    draftState.value=DraftSaveState.Saved;editorGeneration=currentCoroutineContext()[WriteGeneration]?.value
                    rememberEditor(restored)
                } else clearEditor() // Submitted, deleted or replaced while this locator was saved.
            }
            if(navigationId!=null && navigationChild!=null && draft.value==null) {
                val record=repository.exam(navigationId,navigationChild)
                checkGeneration()
                if(record!=null && selected.value==navigationChild) {
                    savedState["savedRecordId"]=navigationId;savedState["savedRecordChildId"]=navigationChild;savedId.value=navigationId
                } else consumeSavedRecord()
            }
        }
    }
    fun clearMessage() { message.value=null }
    fun selectChild(id: String) { if(!busy.value && draft.value == null) setSelected(id) }
    fun addChild(name: String, grade: String, done: () -> Unit) = perform { setSelected(repository.addChild(name,grade));done() }
    fun updateChild(child: ChildEntity, name: String, grade: String, archived: Boolean, done: () -> Unit) = perform { repository.updateChild(child.id,name,grade,archived);done() }
    fun deleteChild(id: String, done: () -> Unit) = perform { repository.deleteChild(id);if(savedState.get<String>("savedRecordChildId")==id) consumeSavedRecord();if(draft.value?.childId==id) clearEditor();done() }
    fun saveSubject(subject: SubjectEntity?, name: String, maximum: Long, order: Int, archived: Boolean, done: () -> Unit) = perform {
        repository.saveSubject(subject?.id,name,maximum,order,archived);done()
    }
    fun template(names: List<String>) = perform { repository.addSubjectTemplate(names) }
    fun openNew() = perform {
        val child=requireNotNull(selected.value)
        val d=repository.newDraft(child)
        repository.putDraft(d);checkGeneration();draft.value=d;markPersisted(d.encode());editorGeneration=currentCoroutineContext()[WriteGeneration]?.value;rememberEditor(d)
    }
    fun openDraft(row: DraftEntity) = perform {
        check(row.childId == selected.value)
        val current=requireNotNull(repository.draft(row.id,row.childId));checkGeneration();val d=EntryDraft.decode(current)
        draft.value=d;markPersisted(current.payloadJson);draftState.value=DraftSaveState.Saved;editorGeneration=currentCoroutineContext()[WriteGeneration]?.value;rememberEditor(d)
    }
    fun edit(record: ExamWithScores, subjectId: String? = null) = perform {
        check(record.exam.childId == selected.value)
        val existing=repository.draft(record.exam.id,record.exam.childId)
        val d=existing?.let(EntryDraft::decode) ?: EntryDraft.from(record)
        repository.putDraft(d,existing?.payloadJson);checkGeneration();draft.value=d;markPersisted(d.encode());focusSubject.value=subjectId;editorGeneration=currentCoroutineContext()[WriteGeneration]?.value;rememberEditor(d)
    }
    /** A Compose callback may still hold an older displayed draft. Apply only its
     * changed fields to the current editor; an echoed old value is not a new edit.
     * This is local callback handling only: all writes still use the FIFO/CAS path.
     * Structural row changes require an exact current base, never a stale merge. */
    fun change(value: EntryDraft, base: EntryDraft) {
        val current=draft.value ?: return
        if(busy.value || editorGeneration!=repository.generation.value ||
            base.id!=current.id || base.childId!=current.childId || value.id!=current.id ||
            value.childId!=current.childId || value.childId!=selected.value || base.editing!=current.editing || value.editing!=current.editing) return
        val currentIds=current.rows.map { it.subjectId }
        val baseIds=base.rows.map { it.subjectId }
        val valueIds=value.rows.map { it.subjectId }
        if(baseIds!=currentIds) return // Removed/reordered rows cannot be resurrected by old callbacks.
        if(valueIds!=baseIds) {
            if(base==current) change(value)
            return
        }
        val rows=current.rows.mapIndexed { index,row ->
            val previous=base.rows[index];val proposed=value.rows[index]
            val scoreChanged=proposed.score!=previous.score
            val statusChanged=proposed.status!=previous.status
            // A late scored-input callback must not undo a newer absent/missing selection.
            val acceptScore=scoreChanged && (row.status==previous.status || statusChanged)
            val status=if(statusChanged) proposed.status else row.status
            row.copy(name=if(proposed.name!=previous.name) proposed.name else row.name,
                maximum=if(proposed.maximum!=previous.maximum) proposed.maximum else row.maximum,
                status=status,score=if(status!=com.example.scoreledger.core.ScoreStatus.SCORED) ""
                    else if(acceptScore) proposed.score else row.score)
        }
        val changed=current.copy(
            title=if(value.title!=base.title) value.title else current.title,
            date=if(value.date!=base.date) value.date else current.date,
            kind=if(value.kind!=base.kind) value.kind else current.kind,
            term=if(value.term!=base.term) value.term else current.term,
            gradeSnapshot=if(value.gradeSnapshot!=base.gradeSnapshot) value.gradeSnapshot else current.gradeSnapshot,
            note=if(value.note!=base.note) value.note else current.note,rows=rows)
        if(changed!=current) change(changed)
    }
    fun change(value: EntryDraft) {
        if(busy.value || editorGeneration!=repository.generation.value || value.id != draft.value?.id || value.childId != selected.value) return
        draft.value=value
        draftState.value=DraftSaveState.Saving
        val epoch=requireNotNull(editorGeneration)
        operations.trySend { withContext(WriteGeneration(epoch)) { repository.putDraft(value,persisted.value);if(epoch==repository.generation.value){markPersisted(value.encode())} } }
    }
    fun closeEditor(discard: Boolean) = perform {
        val d=requireNotNull(draft.value)
        if(discard) repository.discardDraft(d.id,d.childId,persisted.value) else repository.putDraft(d,persisted.value)
        clearEditor()
    }
    fun submit() = perform {
        val d=requireNotNull(draft.value)
        check(d.childId == selected.value) { "请返回正确的孩子档案" }
        repository.putDraft(d,persisted.value) // Compare-and-swap also protects a retry after failed autosave.
        markPersisted(d.encode())
        repository.submitDraft(d)
        checkGeneration()
        clearEditor()
        savedState["savedRecordId"]=d.id;savedState["savedRecordChildId"]=d.childId;savedId.value=d.id;message.value="记录已保存"
    }
    fun retryDraft() = perform {
        val d=requireNotNull(draft.value)
        draftState.value=DraftSaveState.Saving
        repository.putDraft(d,persisted.value);markPersisted(d.encode())
    }
    // Called only after the user explicitly confirms replacing local unsaved fields.
    fun reloadDraft() = perform {
        val d=requireNotNull(draft.value)
        val current=repository.draft(d.id,d.childId)
        check(current != null) { "草稿已提交或删除。本页输入仍保留，请返回查看最新记录。" }
        val latest=EntryDraft.decode(current)
        draft.value=latest;editorGeneration=currentCoroutineContext()[WriteGeneration]?.value;markPersisted(current.payloadJson);draftState.value=DraftSaveState.Saved
    }
    fun delete(record: ExamWithScores, done: () -> Unit) = perform {
        deleted=repository.deleteForUndo(record);undo.value=true
        if(savedId.value==record.exam.id) consumeSavedRecord()
        undoJob?.cancel();undoJob=viewModelScope.launch { delay(10000);deleted=null;undo.value=false }
        done()
    }
    /** Undo lasts ten seconds, for the latest deletion only, and never survives process death. */
    fun undoDelete() = perform {
        val token=requireNotNull(deleted) { "撤销时间已结束" }
        repository.undoDelete(token);deleted=null;undo.value=false;undoJob?.cancel()
    }
    fun removeRow(subjectId:String) {
        val d=draft.value ?: return
        val index=d.rows.indexOfFirst { it.subjectId==subjectId }
        if(index<0 || busy.value) return
        removedRow=index to d.rows[index];rowUndo.value=true
        change(d.copy(rows=d.rows.filterNot { it.subjectId==subjectId }))
    }
    fun undoRemoveRow() {
        val d=draft.value ?: return
        val (index,row)=removedRow ?: return
        if(busy.value || d.rows.size>=30 || d.rows.any { it.subjectId==row.subjectId }) return
        change(d.copy(rows=d.rows.toMutableList().apply { add(index.coerceAtMost(size),row) }))
        removedRow=null;rowUndo.value=false
    }
    fun addSuggestedSubjects(names:List<String>) = perform {
        val updated=repository.addSuggestedSubjects(requireNotNull(draft.value),names,persisted.value)
        draft.value=updated;markPersisted(updated.encode())
    }
    fun startFirstRecord(nickname:String, subjectNames:List<String>) = perform {
        val d=repository.startFirstRecord(nickname,subjectNames)
        checkGeneration()
        setSelected(d.childId);draft.value=d;markPersisted(d.encode())
        editorGeneration=currentCoroutineContext()[WriteGeneration]?.value;rememberEditor(d)
    }
    fun showError(text: String) { message.value=text }
    private fun perform(block: suspend () -> Unit) {
        if(busy.value) return
        if(visibleGeneration!=repository.generation.value) { message.value="数据已改变，请重新打开页面";return }
        busy.value=true;message.value=null
        val epoch=visibleGeneration
        operations.trySend { try { withContext(WriteGeneration(epoch)) { check(epoch==repository.generation.value) { "数据已改变，请重新打开页面" };block() } } finally {
            if(epoch!=repository.generation.value){clearEditor();consumeSavedRecord()}
            busy.value=false
        } }
    }
}
