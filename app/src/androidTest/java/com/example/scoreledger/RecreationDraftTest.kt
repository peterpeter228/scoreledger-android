package com.example.scoreledger

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RecreationDraftTest {
    @get:Rule val compose=createEmptyComposeRule()
    /** Best-effort evidence before ActivityScenario cleanup. No Room query, idle wait,
     * retry or sleep here: preserve the original failure and the screen that caused it. */
    private fun captureFailure(scenario:ActivityScenario<RecreationHostActivity>,expected:EntryDraft,failure:Throwable) {
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"ui-evidence")
        runCatching { check(directory.isDirectory || directory.mkdirs()) }
        runCatching {
            val state=JSONObject().put("failureType",failure.javaClass.name).put("phase","activity-recreation")
            scenario.onActivity { activity ->
                val model=activity.diagnosticModel;val editor=model?.editor?.value
                state.put("modelPresent",model!=null).put("editorPresent",editor!=null)
                    .put("editorIdMatches",editor?.id==expected.id).put("editorChildMatches",editor?.childId==expected.childId)
                    .put("editorTitleMatches",editor?.title=="旋转后仍保留").put("saving",model?.saving?.value)
                    .put("draftSaveState",model?.draftSaveState?.value?.name).put("errorPresent",model?.error?.value!=null)
                    .put("conflict",model?.draftConflict?.value).put("selectedChildMatches",model?.selectedChild?.value==expected.childId)
            }
            File(directory,"recreation-draft-failure-state.json").writeText(state.toString())
        }
        runCatching { File(directory,"recreation-draft-failure-stack.txt").writeText(failure.stackTraceToString()) }
        runCatching {
            val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try { File(directory,"recreation-draft-failure.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
        }
    }
    @Test fun activityRecreationRetainsEditorAndPersistedDraft() {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        val draft=runBlocking { val child=repository.addChild("重建测试孩子");EntryDraft(childId=child,title="重建前标题").also{repository.putDraft(it)} }
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        RecreationHostActivity.testRepository=repository
        RecreationHostActivity.testServices=isolated.services(repository)
        try {
            ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
                try {
                    compose.waitUntil(10000){compose.onAllNodesWithText("继续草稿：重建前标题").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithText("继续草稿：重建前标题").performClick()
                    compose.waitUntil(10000){compose.onAllNodesWithText("重建前标题").fetchSemanticsNodes().isNotEmpty()}
                    compose.onNodeWithText("修改考试信息").performClick()
                    compose.onNodeWithText("考试名称").performTextReplacement("旋转后仍保留")
                    compose.waitUntil(10000){runBlocking{repository.draft(draft.id,draft.childId)?.let(EntryDraft::decode)?.title=="旋转后仍保留"}}
                    val persistedBefore=runBlocking { requireNotNull(repository.draft(draft.id,draft.childId)) }
                    assertEquals(draft.copy(title="旋转后仍保留"),EntryDraft.decode(persistedBefore))
                    // Capture, do not wait away a lifecycle race: the last edited draft
                    // must already be authoritative in the retained ViewModel.
                    var beforeModel:com.example.scoreledger.ui.ScoreViewModel?=null
                    val beforePhase=JSONObject()
                    scenario.onActivity { activity ->
                        beforeModel=activity.diagnosticModel
                        assertEquals(draft.copy(title="旋转后仍保留"),beforeModel?.editor?.value)
                        beforePhase.put("editorTitleMatchesBeforeRecreation",true)
                            .put("draftSaveStateBeforeRecreation",beforeModel?.draftSaveState?.value?.name)
                            .put("persistedPayloadMatchesBeforeRecreation",beforeModel?.persistedPayload?.value==persistedBefore.payloadJson)
                    }
                    scenario.recreate()
                    // LazyColumn/IME restoration may leave the title offscreen. First
                    // require the SAME editor, without reopening any draft, then scroll.
                    compose.waitUntil(10000) {
                        var sameEditor=false
                        scenario.onActivity { activity ->
                            val model=activity.diagnosticModel
                            sameEditor=model?.editor?.value?.let { it.id==draft.id && it.childId==draft.childId }==true && model?.saving?.value==false
                        }
                        sameEditor && compose.onAllNodesWithTag("entry-list").fetchSemanticsNodes().size==1
                    }
                    // Independently check persistence before any scroll/focus operation,
                    // so a subsequent failure distinguishes UI state from stored content.
                    val persistedImmediatelyAfter=runBlocking { repository.draft(draft.id,draft.childId) }
                    val stateFile=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
                        "ui-evidence/recreation-draft-phase-state.json")
                    stateFile.parentFile!!.mkdirs()
                    val phase=beforePhase
                        .put("storedEntityUnchangedImmediatelyAfter",persistedBefore==persistedImmediatelyAfter)
                        .put("storedTitleMatchesImmediatelyAfter",persistedImmediatelyAfter?.let(EntryDraft::decode)?.title=="旋转后仍保留")
                    scenario.onActivity { activity ->
                        val model=activity.diagnosticModel
                        phase.put("retainedSameViewModel",model===beforeModel)
                            .put("editorTitleMatchesBeforeScroll",model?.editor?.value?.title=="旋转后仍保留")
                            .put("editorIdMatchesBeforeScroll",model?.editor?.value?.id==draft.id)
                            .put("draftSaveStateBeforeScroll",model?.draftSaveState?.value?.name)
                            .put("persistedPayloadMatchesBeforeScroll",model?.persistedPayload?.value==persistedBefore.payloadJson)
                    }
                    stateFile.writeText(phase.toString())
                    assertEquals("Stored draft changed during recreation before scrolling",persistedBefore,persistedImmediatelyAfter)
                    compose.onNodeWithTag("entry-list").assertExists().performScrollToNode(hasText("考试名称"))
                    compose.onNode(hasText("考试名称") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
                        .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
                            androidx.compose.ui.text.AnnotatedString("旋转后仍保留")))
                    compose.onNodeWithText("草稿已保存到本机").assertExists()
                    val persistedAfter=runBlocking { repository.draft(draft.id,draft.childId) }
                    assertEquals(persistedBefore,persistedAfter)
                    assertEquals(draft.id,persistedAfter?.id)
                    assertEquals(draft.copy(title="旋转后仍保留"),EntryDraft.decode(requireNotNull(persistedAfter)))
                    assertEquals(0,runBlocking{db.dao().examCount()})
                } catch(failure:Throwable) { captureFailure(scenario,draft,failure);throw failure }
            }
        }finally{RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;isolated.cleanup();db.close()}
    }
}
