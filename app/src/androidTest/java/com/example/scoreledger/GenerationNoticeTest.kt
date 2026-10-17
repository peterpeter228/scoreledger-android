package com.example.scoreledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import com.example.scoreledger.ui.ScoreViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated state regression. EmptyRestoreUiTest separately proves that the real
 * rollback button remains immediately actionable after an empty settings restore. */
@RunWith(AndroidJUnit4::class)
class GenerationNoticeTest {
    private suspend fun await(condition:()->Boolean)=withTimeout(10000) { while(!condition()) delay(20) }
    @Test fun restoreNoticeOnlyReportsAnEditorThatWasActuallyClosed()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        val store=ViewModelStore()
        try {
            val repository=ScoreRepository(db,true)
            val a=repository.addChild("合成待替换甲");val b=repository.addChild("合成保留乙")
            val handle=SavedStateHandle(mapOf("selectedChild" to a))
            val model=withContext(Dispatchers.Main) { ScoreViewModel(repository,handle).also { store.put("model",it) } }
            await { model.childrenLoaded.value && model.selectedChild.value==a }
            assertNull(model.editor.value);assertNull(model.error.value)
            val before=repository.restorePreview()
            val replacement=before.data.copy(children=before.data.children.filter { it.id==b })
            repository.replace(PendingRestore(replacement,before)){}
            // Child invalidation is emitted after the replacement transaction commits;
            // observe the actual fallback rather than sleeping past the notice.
            await { model.selectedChild.value==b && model.children.value.size==1 }
            assertNull("Settings restore must not claim a nonexistent editor was closed",model.error.value)
            assertNull(model.editor.value);assertFalse(model.draftConflict.value)
            assertEquals(replacement,repository.snapshot())

            val draft=EntryDraft(childId=b,title="合成打开的草稿")
            repository.putDraft(draft)
            val row=requireNotNull(repository.draft(draft.id,b))
            withContext(Dispatchers.Main) { model.openDraft(row) }
            await { model.editor.value==draft && !model.saving.value }
            val withEditor=repository.restorePreview()
            repository.replace(PendingRestore(withEditor.data,withEditor)){}
            await { model.editor.value==null && model.error.value!=null }
            assertEquals("数据已恢复或清理，旧编辑页面已关闭",model.error.value)
            assertNull(model.persistedPayload.value);assertFalse(model.draftConflict.value)
            assertNull(model.savedRecordId.value);assertNull(repository.draft(draft.id,b))
            withContext(Dispatchers.Main) {
                assertNull(handle.get<String>("activeDraftId"));assertNull(handle.get<String>("activeDraftChildId"))
                model.change(draft.copy(title="不允许复活的旧输入"))
            }
            assertNull(model.editor.value);assertNull(repository.draft(draft.id,b))
            assertEquals(withEditor.data,repository.snapshot())
        } finally { withContext(Dispatchers.Main) { store.clear() };db.close() }
    }
}
