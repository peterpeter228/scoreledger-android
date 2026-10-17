package com.example.scoreledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import com.example.scoreledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real production editor and SQLite failure; isolated synthetic repository only. */
@RunWith(AndroidJUnit4::class)
class DraftRetryUiTest {
    @get:Rule val compose=createComposeRule()
    @Test fun sqliteFailureRemainsRetryableAndUiRetryPersistsExactPayload() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val isolated=IsolatedUiContext(context)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        val store=ViewModelStore()
        try {
            val repository=ScoreRepository(db,true)
            val draft=runBlocking {
                val child=repository.addChild("合成重试孩子")
                val subject=repository.saveSubject(null,"合成数学",10000,0)
                EntryDraft(childId=child,title="合成草稿",rows=listOf(DraftRow(subject,"合成数学"))).also { repository.putDraft(it) }
            }
            lateinit var model:ScoreViewModel
            compose.runOnUiThread { model=ScoreViewModel(repository);store.put("retry",model) }
            compose.setContent { ScoreLedgerTheme { StarterScreen(model,isolated.services(repository)) } }
            compose.waitUntil(10000) { model.selectedChild.value==draft.childId }
            compose.onNodeWithText("继续草稿：合成草稿").performClick()
            compose.waitUntil(10000) { model.editor.value!=null && !model.saving.value }
            // Existing draft CAS writes use UPDATE; INSERT is also guarded to catch implementation drift.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_draft_update BEFORE UPDATE ON drafts BEGIN SELECT RAISE(ABORT,'synthetic fault'); END")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_draft_insert BEFORE INSERT ON drafts BEGIN SELECT RAISE(ABORT,'synthetic fault'); END")
            compose.onNodeWithTag("entry-list").performScrollToNode(hasTestTag("score-${draft.rows.single().subjectId}"))
            compose.onNodeWithTag("score-${draft.rows.single().subjectId}").performTextReplacement("８５．５")
            compose.waitUntil(10000) { model.draftSaveState.value==DraftSaveState.Error }
            Thread.sleep(10500) // Error must remain actionable, not turn into endless Saving.
            compose.onNodeWithText("重试保存草稿").assertExists()
            assertEquals(DraftSaveState.Error,model.draftSaveState.value)
            assertEquals(draft.encode(),runBlocking { repository.draft(draft.id,draft.childId)!!.payloadJson })
            val expected=requireNotNull(model.editor.value).encode()
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_draft_update")
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_draft_insert")
            compose.onNodeWithText("重试保存草稿").performClick()
            compose.waitUntil(10000) { model.draftSaveState.value==DraftSaveState.Saved }
            assertEquals(expected,runBlocking { repository.draft(draft.id,draft.childId)!!.payloadJson })
            assertEquals(0,runBlocking { db.dao().examCount() })
        } finally { compose.runOnUiThread { store.clear() };db.close();isolated.cleanup() }
    }
}
