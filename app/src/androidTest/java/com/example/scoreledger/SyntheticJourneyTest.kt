package com.example.scoreledger

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic isolated data and managers. Does not clear or mutate the app database. */
@RunWith(AndroidJUnit4::class)
class SyntheticJourneyTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun item(list:String,matcher:SemanticsMatcher):SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    /** Room-backed commands outlive Compose idleness. Await the actual destination and
     * enabled save action, not elapsed time or only a database row count. */
    private fun awaitEditorReady() {
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("entry-list").fetchSemanticsNodes().size==1 &&
                compose.onAllNodes(hasTestTag("save-record") and isEnabled()).fetchSemanticsNodes().size==1
        }
        compose.onNodeWithTag("entry-list").assertExists()
        compose.onNodeWithTag("save-record").assertIsEnabled()
    }
    private fun awaitScreen(tag:String) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size==1 }
        compose.onNodeWithTag(tag).assertExists()
    }
    private fun assertSaveAboveVisibleIme(scenario:ActivityScenario<RecreationHostActivity>) {
        var imeTop=0
        var contentTop=0
        compose.waitUntil(10000) {
            var visible=false
            scenario.onActivity { activity ->
                val decor=activity.window.decorView
                val insets=ViewCompat.getRootWindowInsets(decor)
                visible=insets?.isVisible(WindowInsetsCompat.Type.ime())==true
                val decorLocation=IntArray(2);decor.getLocationOnScreen(decorLocation)
                imeTop=decorLocation[1]+decor.height-(insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)
                val content=activity.findViewById<android.view.View>(android.R.id.content)
                val location=IntArray(2);content.getLocationOnScreen(location);contentTop=location[1]
            }
            visible
        }
        compose.onNodeWithTag("save-record").assertIsDisplayed()
        val bounds=compose.onNodeWithTag("save-record").fetchSemanticsNode().boundsInRoot
        assertTrue("Save action must remain entirely above the visible system IME",bounds.bottom+contentTop<=imeTop+1f)
    }
    /** Diagnostic evidence only; image existence is not a visual correctness assertion. */
    private fun captureSyntheticUi(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.filesDir,"ui-evidence")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory,"synthetic-$name.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
            }
        } finally { bitmap.recycle() }
    }
    @Test fun emptyInstallCreateTemplateEnterMixedStatesEditAndIsolateChildren() {
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build();val repository=ScoreRepository(db,true)
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        try { ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
            awaitScreen("home-list")
            compose.onNodeWithTag("first-nickname").performTextInput("合成孩子甲")
            compose.onNodeWithTag("new-record").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().childCount()==1 && db.dao().subjectCount()==3}}
            awaitEditorReady()
            item("entry-list",hasText("修改考试信息")).performClick()
            item("entry-list",hasText("考试名称")).performTextReplacement("合成混合状态考试")
            item("entry-list",hasText("本次年级（可选）")).performTextReplacement("合成七年级")
            val subjects=runBlocking{db.dao().subjects()}.associateBy{it.name}
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).performTextReplacement("0")
            item("entry-list",hasTestTag("score-${subjects.getValue("数学").id}")).performTextReplacement("８５．５")
            item("entry-list",hasText("改满分 / 标记缺考") and hasAnyAncestor(hasTestTag("subject-${subjects.getValue("英语").id}"))).performClick()
            item("entry-list",hasText("缺考") and hasAnyAncestor(hasTestTag("subject-${subjects.getValue("英语").id}"))).performClick()
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).assertExists()
            compose.onNodeWithTag("score-${subjects.getValue("语文").id}").performClick()
            assertSaveAboveVisibleIme(scenario)
            captureSyntheticUi("01-entry-before-save")
            compose.onNodeWithTag("save-record").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().examCount()==1}}
            compose.waitUntil(10000){compose.onAllNodesWithTag("detail-list").fetchSemanticsNodes().isNotEmpty()}
            val first=runBlocking{repository.snapshot()};val scores=first.scores.associateBy{it.subjectId}
            assertEquals(0L,scores.getValue(subjects.getValue("语文").id).scoreMinor)
            assertEquals(8550L,scores.getValue(subjects.getValue("数学").id).scoreMinor)
            assertEquals("ABSENT",scores.getValue(subjects.getValue("英语").id).status)
            assertEquals("合成七年级",first.exams.single().gradeSnapshot)
            item("detail-list",hasText("合成混合状态考试")).assertExists()
            captureSyntheticUi("02-history-after-save")
            item("detail-list",hasText("编辑这次记录")).performClick()
            awaitEditorReady()
            val editDraft=runBlocking { repository.draft(first.exams.single().id,first.exams.single().childId) }
            assertNotNull("Edit must reopen the same persisted record",editDraft)
            assertTrue(EntryDraft.decode(requireNotNull(editDraft)).editing)
            assertEquals(first.exams.single().id,EntryDraft.decode(requireNotNull(editDraft)).id)
            item("entry-list",hasText("修改考试信息")).performClick()
            item("entry-list",hasText("考试名称")).performTextReplacement("合成纠正标题")
            compose.onNodeWithTag("save-record").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().allExams().single().title=="合成纠正标题"}}
            assertEquals(first.exams.single().id,runBlocking{db.dao().allExams().single().id})
            awaitScreen("detail-list")
            item("detail-list",hasText("返回记录")).performClick()
            // Create another synthetic child in the same isolated repository; exercise UI scope switch.
            runBlocking{repository.addChild("合成孩子乙")}
            compose.onNodeWithText("合成孩子甲").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("合成孩子乙").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("合成孩子乙").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 && compose.onAllNodesWithText("还没有记录。先记下最近一次考试。").fetchSemanticsNodes().size==1 }
            compose.onNodeWithText("还没有记录。先记下最近一次考试。").assertExists()
            compose.onNodeWithText("合成纠正标题").assertDoesNotExist()
            captureSyntheticUi("03-child-isolation")
            compose.onNodeWithText("合成孩子乙").performClick()
            compose.onNodeWithText("合成孩子甲").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 && compose.onAllNodesWithText("合成纠正标题").fetchSemanticsNodes().isNotEmpty() }
            item("home-list",hasText("合成纠正标题")).assertExists()
            compose.onNodeWithTag("new-record").performClick()
            awaitEditorReady()
            val resumed=runBlocking{db.dao().allExams().single()}
            assertEquals("合成七年级",resumed.gradeSnapshot)
            // Real UI reuse must not inherit prior score or absence status.
            subjects.values.forEach { subject ->
                item("entry-list",hasTestTag("score-${subject.id}")).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
                item("entry-list",hasTestTag("score-${subject.id}")).assertIsEnabled()
            }
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).performTextReplacement("99.99")
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).performImeAction()
            compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("score-${subjects.getValue("数学").id}") and isFocused()).fetchSemanticsNodes().size==1 }
            compose.onNodeWithTag("score-${subjects.getValue("数学").id}").assertIsFocused().performTextReplacement("８５．５")
            compose.onNodeWithTag("score-${subjects.getValue("数学").id}").performImeAction()
            compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("score-${subjects.getValue("英语").id}") and isFocused()).fetchSemanticsNodes().size==1 }
            compose.onNodeWithTag("score-${subjects.getValue("英语").id}").assertIsFocused().performImeAction()
            captureSyntheticUi("04-continuous-entry")
            compose.onNodeWithTag("save-record").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().examCount()==2}}
            compose.waitUntil(10000){compose.onAllNodesWithTag("detail-list").fetchSemanticsNodes().isNotEmpty()}
            val second=runBlocking{repository.snapshot()}.exams.first{it.id!=first.exams.single().id}
            val secondScores=runBlocking{repository.snapshot()}.scores.filter{it.examId==second.id}.associateBy{it.subjectId}
            assertEquals(9999L,secondScores.getValue(subjects.getValue("语文").id).scoreMinor)
            assertEquals("MISSING",secondScores.getValue(subjects.getValue("英语").id).status)
            item("detail-list",hasText("返回记录")).performClick()
            item("home-list",hasTestTag("all-records")).performClick()
            item("history-list",hasTestTag("filter-toggle")).performClick()
            item("history-list",hasTestTag("filter-search")).performTextInput("合成纠正标题")
            item("history-list",hasTestTag("apply-filters")).performClick()
            item("history-list",hasTestTag("record-${first.exams.single().id}")).performClick()
            item("detail-list",hasText("合成纠正标题")).assertExists()
            item("detail-list",hasText("返回记录")).performClick()
            item("history-list",hasText("1次考试")).assertExists()
            captureSyntheticUi("05-filter-return")
        } }finally{RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
