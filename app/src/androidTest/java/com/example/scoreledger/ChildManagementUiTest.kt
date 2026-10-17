package com.example.scoreledger

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.core.ScoreStatus
import com.example.scoreledger.core.ScoreValue
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** One bounded archive/delete UI regression; synthetic in-memory repository only.
 * Does not claim real-disk, process-death or external-provider coverage. */
@RunWith(AndroidJUnit4::class)
class ChildManagementUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun item(list:String,matcher:SemanticsMatcher):SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val file=File(instrumentation.targetContext.filesDir,"ui-evidence/$name.png")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
    }
    private fun childAction(name:String,other:String,action:String):SemanticsNodeInteraction {
        // Scope the action to a semantic ancestor containing this nickname, not the
        // other child. Never choose a destructive button by its global list index.
        val ownCard=hasAnyDescendant(hasText(name)) and !hasAnyDescendant(hasText(other))
        return item("children-list",hasText(action) and hasAnyAncestor(ownCard))
    }
    private fun manageChildren() {
        compose.onNodeWithText("设置").performClick()
        item("settings-list",hasText("孩子档案")).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("children-list").fetchSemanticsNodes().size==1 }
    }
    @Test fun archiveAndCancelThenConfirmChildDeletionPreservesOtherChildAndAllTheirRows() {
        val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val clock=Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"),ZoneOffset.UTC)
        var sequence=0L
        val repository=ScoreRepository(db,true,clock) { UUID(0,++sequence).toString() }
        val aName="合成删除目标甲";val bName="合成保留孩子乙"
        val fixture=runBlocking {
            val a=repository.addChild(aName,"三年级");val b=repository.addChild(bName,"四年级")
            val subject=repository.saveSubject(null,"合成数学",10000,0)
            val aExam=UUID(0,++sequence).toString();val bExam=UUID(0,++sequence).toString()
            repository.saveExam(ExamInput(aExam,a,"甲的零分记录","2026-10-15",values=listOf(ScoreValue(subject,ScoreStatus.SCORED,0,10000))),false)
            repository.saveExam(ExamInput(bExam,b,"乙的保留记录","2026-10-15",values=listOf(ScoreValue(subject,ScoreStatus.SCORED,8550,10000))),false)
            repository.putDraft(EntryDraft(id=UUID(0,++sequence).toString(),childId=a,title="甲的待删除草稿",rows=listOf(DraftRow(subject,"合成数学",status=ScoreStatus.ABSENT))))
            repository.putDraft(EntryDraft(id=UUID(0,++sequence).toString(),childId=b,title="乙的保留草稿",rows=listOf(DraftRow(subject,"合成数学",status=ScoreStatus.MISSING))))
            repository.restorePreview()
        }
        val a=fixture.data.children.single { it.nickname==aName }.id
        val b=fixture.data.children.single { it.nickname==bName }.id
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        try {
            ActivityScenario.launch(RecreationHostActivity::class.java).use {
                try {
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 }
                    manageChildren()
                    childAction(aName,bName,"归档").assertIsEnabled().performClick()
                    compose.waitUntil(10000) { runBlocking { repository.snapshot().children.single { it.id==a }.archived } }
                    val archived=runBlocking { repository.restorePreview() }
                    assertEquals(fixture.data.copy(children=fixture.data.children.map { if(it.id==a) it.copy(archived=true) else it }),archived.data)
                    assertEquals(fixture.drafts,archived.drafts)
                    compose.waitUntil(10000) { compose.onAllNodesWithText("取消归档").fetchSemanticsNodes().size==1 }
                    childAction(aName,bName,"取消归档").assertExists()
                    item("children-list",hasText("返回")).performClick()
                    compose.onNodeWithText("记录").performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 }
                    compose.onNodeWithTag("new-record").assertIsNotEnabled()
                    item("home-list",hasText("继续草稿：甲的待删除草稿")).assertExists()
                    item("home-list",hasTestTag("latest-record")).performClick()
                    item("detail-list",hasText("甲的零分记录")).assertExists()
                    item("detail-list",hasText("得分 0")).assertExists()
                    item("detail-list",hasText("返回记录")).performClick()
                    manageChildren()
                    childAction(aName,bName,"删除").assertIsEnabled().performClick()
                    compose.onNodeWithText("删除孩子档案？").assertExists()
                    compose.onNodeWithText("将永久删除“$aName”的档案、1次考试、全部成绩及草稿。无法撤销；归档可以保留历史。").assertExists()
                    capture("child-delete-confirmation")
                    compose.onNodeWithText("取消").performClick()
                    compose.onNodeWithText("删除孩子档案？").assertDoesNotExist()
                    assertEquals(archived,runBlocking { repository.restorePreview() })
                    childAction(aName,bName,"删除").performClick()
                    compose.onNodeWithText("确认删除全部关联数据").assertIsEnabled().performClick()
                    compose.waitUntil(10000) { runBlocking { db.dao().child(a)==null } &&
                        compose.onAllNodesWithText("删除孩子档案？").fetchSemanticsNodes().isEmpty() }
                    val after=runBlocking { repository.restorePreview() }
                    val aExams=archived.data.exams.filter { it.childId==a }.map { it.id }.toSet()
                    assertEquals(archived.data.copy(children=archived.data.children.filterNot { it.id==a },
                        exams=archived.data.exams.filterNot { it.childId==a },scores=archived.data.scores.filterNot { it.examId in aExams }),after.data)
                    assertEquals(archived.drafts.filterNot { it.childId==a },after.drafts)
                    assertEquals(b,after.data.children.single().id);assertEquals(1,after.data.exams.size)
                    assertEquals(1,after.data.scores.size);assertEquals(1,after.drafts.size)
                    compose.onNodeWithText("删除孩子档案？").assertDoesNotExist()
                    item("children-list",hasText("返回")).performClick()
                    compose.onNodeWithText("记录").performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 && compose.onAllNodesWithText(bName).fetchSemanticsNodes().size==1 }
                    compose.onNodeWithTag("new-record").assertIsEnabled()
                    item("home-list",hasText("继续草稿：乙的保留草稿")).assertExists()
                    item("home-list",hasTestTag("latest-record")).performClick()
                    item("detail-list",hasText("乙的保留记录")).assertExists()
                    item("detail-list",hasText("得分 85.5")).assertExists()
                    capture("child-delete-survivor-detail")
                    File(instrumentation.targetContext.filesDir,"ui-evidence/child-management-integrity.json").writeText(JSONObject()
                        .put("status","PASS").put("storage","isolated in-memory Room").put("archivePreservedAllScoresAndDrafts",true)
                        .put("cancelPreservedExactSnapshotAndDrafts",true).put("confirmedDeleteOnlyRemovedTargetChildRows",true)
                        .put("otherChildHistoryAndDraftVisible",true).put("survivingChildren",1).put("survivingExams",1)
                        .put("survivingScores",1).put("survivingDrafts",1).toString())
                } catch(failure:Throwable) { runCatching { capture("child-management-failure") };throw failure }
            }
        } finally {
            RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null
            db.close();isolated.cleanup()
        }
    }
}
