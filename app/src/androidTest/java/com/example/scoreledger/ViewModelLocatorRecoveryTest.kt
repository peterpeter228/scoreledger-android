package com.example.scoreledger

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.core.ScoreStatus
import com.example.scoreledger.core.ScoreValue
import com.example.scoreledger.data.*
import com.example.scoreledger.ui.DraftSaveState
import com.example.scoreledger.ui.ScoreViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** SavedStateHandle + ViewModel recreation, NOT an Android process-death test.
 * Uses isolated synthetic Room data. External force-stop evidence is a separate CI phase. */
@RunWith(AndroidJUnit4::class)
class ViewModelLocatorRecoveryTest {
    private fun database()=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
    private suspend fun await(condition:()->Boolean)=withTimeout(10000){while(!condition())delay(20)}
    private suspend fun snapshot(handle:SavedStateHandle):Map<String,Any?> = withContext(Dispatchers.Main) {
        handle.keys().associateWith { handle.get<Any?>(it) }
    }
    private suspend fun model(r:ScoreRepository,state:SavedStateHandle,store:ViewModelStore)=withContext(Dispatchers.Main) {
        ScoreViewModel(r,state).also{store.put("model",it)}
    }
    @Test fun recreatedViewModelRestoresConfirmedDraftAndOwnerNotFailedUnsavedPayload()=runBlocking {
        val db=database();val store=ViewModelStore()
        try {
            val r=ScoreRepository(db,true);val a=r.addChild("合成甲");val b=r.addChild("合成乙")
            val subject=r.saveSubject(null,"合成数学",10000,0)
            val confirmed=EntryDraft(childId=b,title="已确认草稿",rows=listOf(DraftRow(subject,"合成数学","80","100",ScoreStatus.SCORED)),gradeSnapshot="合成年级")
            r.putDraft(confirmed)
            val handle=SavedStateHandle(mapOf("selectedChild" to b))
            val first=model(r,handle,store)
            await{first.childrenLoaded.value && first.selectedChild.value==b}
            withContext(Dispatchers.Main){first.openDraft(requireNotNull(r.draft(confirmed.id,b)))}
            await{first.editor.value==confirmed && !first.saving.value}
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER locator_update BEFORE UPDATE ON drafts BEGIN SELECT RAISE(ABORT,'synthetic fault'); END")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER locator_insert BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT,'synthetic fault'); END")
            val unsaved=confirmed.copy(title="故障后未保存字段")
            withContext(Dispatchers.Main){first.change(unsaved)}
            await{first.draftSaveState.value==DraftSaveState.Error}
            assertEquals(confirmed.encode(),r.draft(confirmed.id,b)!!.payloadJson)
            val saved=snapshot(handle)+("selectedChild" to a) // Locator owner, not stale selected tab, wins.
            assertEquals(confirmed.id,saved["activeDraftId"]);assertEquals(b,saved["activeDraftChildId"])
            withContext(Dispatchers.Main){store.clear()}
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER locator_update")
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER locator_insert")
            val restoredState=SavedStateHandle(saved);val restored=model(r,restoredState,store)
            await{restored.editor.value!=null && !restored.saving.value}
            assertEquals(b,restored.selectedChild.value);assertEquals(confirmed,restored.editor.value)
            assertEquals(confirmed.encode(),restored.persistedPayload.value)
            assertEquals(DraftSaveState.Saved,restored.draftSaveState.value)
            assertFalse(restored.draftConflict.value);assertNull(restored.savedRecordId.value)
        }finally{withContext(Dispatchers.Main){store.clear()};db.close()}
    }
    @Test fun pendingSavedRecordNavigationClearsOnChildSwitchAndChildDeletion()=runBlocking {
        val db=database();val store=ViewModelStore()
        try {
            val r=ScoreRepository(db,true);val a=r.addChild("合成导航甲");val b=r.addChild("合成导航乙")
            val subject=r.saveSubject(null,"合成科目",10000,0);val id=UUID.randomUUID().toString()
            r.saveExam(ExamInput(id,a,"合成已保存",LocalDate.now().toString(),values=listOf(ScoreValue(subject,ScoreStatus.SCORED,0,10000))),false)
            fun state()=SavedStateHandle(mapOf("selectedChild" to a,"savedRecordId" to id,"savedRecordChildId" to a))
            val firstState=state();val first=model(r,firstState,store)
            await{first.childrenLoaded.value && first.savedRecordId.value==id && !first.saving.value}
            withContext(Dispatchers.Main){first.selectChild(b)}
            assertEquals(b,first.selectedChild.value);assertNull(first.savedRecordId.value)
            assertNull(snapshot(firstState)["savedRecordId"]);assertNull(snapshot(firstState)["savedRecordChildId"])
            withContext(Dispatchers.Main){store.clear()}
            val secondState=state();val second=model(r,secondState,store)
            await{second.savedRecordId.value==id && !second.saving.value}
            withContext(Dispatchers.Main){second.deleteChild(a){}}
            await{!second.saving.value && second.selectedChild.value==b}
            assertNull(second.savedRecordId.value);assertNull(snapshot(secondState)["savedRecordId"])
            assertNull(r.exam(id,a))
            // A stale navigation ID cannot reappear in a newly constructed ViewModel.
            withContext(Dispatchers.Main){store.clear()}
            val absentState=state();val absent=model(r,absentState,store)
            await{absent.childrenLoaded.value && !absent.saving.value && absent.selectedChild.value==b}
            assertNull(absent.savedRecordId.value);assertNull(snapshot(absentState)["savedRecordId"])
        }finally{withContext(Dispatchers.Main){store.clear()};db.close()}
    }
    @Test fun replacementGenerationClearsEditorAndStaleLocatorFallsBackWithoutResurrectingDraft()=runBlocking {
        val db=database();val store=ViewModelStore()
        try {
            val r=ScoreRepository(db,true);val a=r.addChild("保留的合成孩子");val b=r.addChild("将替换的合成孩子")
            val draft=EntryDraft(childId=b,title="替换前已确认草稿");r.putDraft(draft)
            val state=SavedStateHandle(mapOf("selectedChild" to b,"activeDraftId" to draft.id,"activeDraftChildId" to b))
            val first=model(r,state,store);await{first.editor.value==draft && !first.saving.value}
            val stale=snapshot(state)
            val before=r.restorePreview();val replacement=before.data.copy(children=before.data.children.filter{it.id==a})
            r.replace(PendingRestore(replacement,before)){}
            withContext(Dispatchers.Main){first.change(draft.copy(title="不应复活的旧编辑"))}
            await{first.editor.value==null && first.selectedChild.value==a}
            assertNull(r.draft(draft.id,b));assertNull(snapshot(state)["activeDraftId"])
            assertNull(first.savedRecordId.value);assertFalse(first.draftConflict.value)
            withContext(Dispatchers.Main){store.clear()}
            val restoredState=SavedStateHandle(stale);val restored=model(r,restoredState,store)
            await{restored.childrenLoaded.value && !restored.saving.value && restored.selectedChild.value==a}
            assertNull(restored.editor.value);assertNull(restored.persistedPayload.value)
            assertNull(snapshot(restoredState)["activeDraftId"]);assertNull(snapshot(restoredState)["activeDraftChildId"])
            assertNull(r.draft(draft.id,b));assertEquals(replacement,r.snapshot())
        }finally{withContext(Dispatchers.Main){store.clear()};db.close()}
    }
}
