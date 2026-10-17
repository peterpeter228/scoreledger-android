package com.example.scoreledger

import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
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
        return compose.onNode(matcher)
    }
    /** Diagnostic evidence only; image existence is not a visual correctness assertion. */
    private fun captureSyntheticUi(name:String) {
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)),"ui-evidence")
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
        try { ActivityScenario.launch(RecreationHostActivity::class.java).use {
            item("history-list",hasText("创建孩子档案")).performClick()
            compose.onNodeWithText("创建孩子档案").performClick()
            compose.onNodeWithText("昵称 · 最多24字").performTextInput("合成孩子甲")
            compose.onNodeWithText("保存").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().childCount()==1}}
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithText("设置").performClick()
            item("settings-list",hasText("科目管理 · 0/100")).performClick()
            compose.onNodeWithText("初中").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().subjectCount()==9}}
            compose.onNodeWithText("返回").performClick();compose.onNodeWithText("记录").performClick()
            item("history-list",hasText("记一次成绩")).performClick()
            compose.onNodeWithText("考试名称 · 1至60字").performTextInput("合成混合状态考试")
            listOf("语文","数学","英语","物理").forEach { name ->
                item("entry-list",hasText("选择科目 · 最多30科")).performClick()
                item("subject-picker-list",hasText(name)).performClick()
            }
            val subjects=runBlocking{db.dao().subjects()}.associateBy{it.name}
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).performTextReplacement("0")
            item("entry-list",hasTestTag("score-${subjects.getValue("数学").id}")).performTextReplacement("８５．５")
            item("entry-list",hasText("缺考") and hasAnyAncestor(hasTestTag("subject-${subjects.getValue("英语").id}"))).performClick()
            item("entry-list",hasText("未录入") and hasAnyAncestor(hasTestTag("subject-${subjects.getValue("物理").id}"))).performClick()
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).assertExists()
            captureSyntheticUi("01-entry-before-save")
            item("entry-list",hasText("保存这次记录")).performClick()
            compose.waitUntil(10000){runBlocking{db.dao().examCount()==1}}
            val first=runBlocking{repository.snapshot()};val scores=first.scores.associateBy{it.subjectId}
            assertEquals(0L,scores.getValue(subjects.getValue("语文").id).scoreMinor)
            assertEquals(8550L,scores.getValue(subjects.getValue("数学").id).scoreMinor)
            assertEquals("ABSENT",scores.getValue(subjects.getValue("英语").id).status)
            assertEquals("MISSING",scores.getValue(subjects.getValue("物理").id).status)
            item("history-list",hasText("合成混合状态考试")).assertExists()
            captureSyntheticUi("02-history-after-save")
            item("history-list",hasText("合成混合状态考试")).performClick()
            item("detail-list",hasText("编辑这次记录")).performClick()
            item("entry-list",hasText("合成混合状态考试")).performTextReplacement("合成纠正标题")
            item("entry-list",hasText("保存这次记录")).performClick()
            compose.waitUntil(10000){runBlocking{db.dao().allExams().single().title=="合成纠正标题"}}
            assertEquals(first.exams.single().id,runBlocking{db.dao().allExams().single().id})
            item("detail-list",hasText("返回记录")).performClick()
            // Create another synthetic child in the same isolated repository; exercise UI scope switch.
            runBlocking{repository.addChild("合成孩子乙")}
            compose.waitUntil(10000){compose.onAllNodesWithText("合成孩子乙").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("合成孩子乙").performClick()
            compose.onNodeWithText("合成纠正标题").assertDoesNotExist()
            captureSyntheticUi("03-child-isolation")
            compose.onNodeWithText("合成孩子甲").performClick()
            item("history-list",hasText("合成纠正标题")).assertExists()
        } }finally{RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
