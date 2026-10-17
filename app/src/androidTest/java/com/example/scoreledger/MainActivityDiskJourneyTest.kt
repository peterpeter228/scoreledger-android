package com.example.scoreledger

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
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

/** Genuine installed MainActivity, production factory/services and on-disk Room.
 * Guarded disposable emulator only. UI creates all new business rows; repository is
 * used for assertions and strictly owned synthetic-child cleanup, never UI seeding.
 * Activity close/relaunch is NOT process-death proof (separate external CI phase). */
@RunWith(AndroidJUnit4::class)
class MainActivityDiskJourneyTest {
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
        try{file.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
    }
    private fun assertVisibleAboveRealIme(scenario:ActivityScenario<MainActivity>,scoreTag:String) {
        val automation=instrumentation.uiAutomation
        val flags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply { this.flags=this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            fun keyboardBounds():Rect? = automation.windows.firstOrNull { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { window->Rect().also { window.getBoundsInScreen(it) } }?.takeUnless { it.isEmpty }
            compose.waitUntil(10000){keyboardBounds()!=null}
            val ime=requireNotNull(keyboardBounds())
            val offset=IntArray(2);scenario.onActivity { it.window.decorView.getLocationOnScreen(offset) }
            val save=compose.onNodeWithTag("save-record").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            val score=compose.onNodeWithTag(scoreTag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("Save action overlaps real IME",save.bottom+offset[1]<=ime.top+1)
            assertTrue("Focused score overlaps real IME",score.bottom+offset[1]<=ime.top+1)
            capture("main-disk-entry-ime")
        } finally { automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags } }
    }
    @Test fun genuineMainActivityCreatesSavesReopensDiskRecordAndPreservesUpgradeBaseline() {
        val context=instrumentation.targetContext;val args=InstrumentationRegistry.getArguments()
        check(args.getString("realMainJourney")=="true" && args.getString("allowPersistentSyntheticData")=="true")
        check(context.packageName=="com.example.scoreledger.beta.debug" && BuildConfig.DEBUG && BuildConfig.INTERNAL_BETA)
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Disposable emulator required" }
        val nonce=requireNotNull(args.getString("upgradeNonce"));check(Regex("[0-9a-f]{32,64}").matches(nonce))
        val marker=File(context.noBackupFilesDir,"upgrade-retention-$nonce.json")
        check(marker.isFile && marker.length() in 1L..4096L)
        val seed=JSONObject(marker.readText());check(seed.getString("nonce")==nonce)
        check(RecreationHostActivity.testRepository==null && RecreationHostActivity.testServices==null)
        val repository=(context.applicationContext as ScoreLedgerApplication).repository
        val before=runBlocking{repository.restorePreview()}
        check(before.data.children.any{it.id==seed.getString("childId")})
        val subject=before.data.subjects.single{!it.archived}
        val nickname="主流程-${nonce.take(12)}";val title="主页面实测-${nonce.take(12)}"
        check(before.data.children.none{it.nickname==nickname})
        var ownedId:String?=null;var examId:String?=null;var verified=false
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
              try {
                compose.onNodeWithText("设置").performClick()
                item("settings-list",hasText("孩子档案")).performClick()
                item("children-list",hasText("创建孩子档案")).performClick()
                compose.onNodeWithText("昵称 · 最多24字").performTextInput(nickname)
                compose.onNodeWithText("年级 · 可选").performTextInput("合成年级")
                compose.onNodeWithText("保存").performClick()
                compose.waitUntil(10000){runBlocking{repository.snapshot().children.count{it.nickname==nickname}==1}}
                ownedId=runBlocking{repository.snapshot().children.single{it.nickname==nickname}.id}
                check(before.data.children.none{it.id==ownedId})
                item("children-list",hasText("返回")).performClick()
                compose.onNodeWithText("记录").performClick()
                compose.onNodeWithTag("new-record").performClick()
                compose.waitUntil(10000){compose.onAllNodesWithTag("save-record").fetchSemanticsNodes().isNotEmpty()}
                item("entry-list",hasText("修改考试信息")).performClick()
                item("entry-list",hasText("考试名称")).performTextReplacement(title)
                item("entry-list",hasText("本次年级（可选）")).performTextReplacement("本次合成年级")
                item("entry-list",hasTestTag("score-${subject.id}")).performClick().performTextReplacement("８５．５")
                assertVisibleAboveRealIme(scenario,"score-${subject.id}")
                compose.onNodeWithTag("save-record").performClick()
                compose.waitUntil(10000){compose.onAllNodesWithTag("detail-list").fetchSemanticsNodes().isNotEmpty()}
                item("detail-list",hasText(title)).assertExists()
                val saved=runBlocking{repository.snapshot()}.exams.single{it.childId==ownedId}
                examId=saved.id;assertEquals(title,saved.title);assertEquals("本次合成年级",saved.gradeSnapshot)
                assertEquals(8550L,runBlocking{repository.snapshot()}.scores.single{it.examId==examId}.scoreMinor)
                compose.onNodeWithTag("saved-record-notice").assertIsDisplayed()
                capture("main-disk-saved-detail")
                item("detail-list",hasText("返回记录")).performClick()
                compose.onNodeWithTag("home-list").assertExists()
                capture("main-disk-home")
                item("home-list",hasTestTag("all-records")).performClick()
                item("history-list",hasTestTag("record-$examId")).performClick()
                item("detail-list",hasText(title)).assertExists()
              } catch(failure:Throwable) { runCatching { capture("main-disk-failure-recording") };throw failure }
            }
            // A fresh Room connection independently reads the actual app database.
            val disk=ScoreDatabase.create(context)
            try { runBlocking {
                val exam=requireNotNull(disk.dao().exam(requireNotNull(examId)))
                assertEquals(ownedId,exam.childId);assertEquals(title,exam.title)
                assertEquals("本次合成年级",exam.gradeSnapshot)
                assertEquals(8550L,disk.dao().scores(exam.id).single().scoreMinor)
                assertNull(disk.dao().draft(exam.id,exam.childId))
            } } finally { disk.close() }
            ActivityScenario.launch(MainActivity::class.java).use {
              try {
                val originalName=before.data.children.first().nickname
                compose.waitUntil(10000){compose.onAllNodesWithText(originalName).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText(originalName).performClick()
                compose.onNodeWithText(nickname).performClick()
                item("home-list",hasTestTag("all-records")).performClick()
                item("history-list",hasTestTag("record-$examId")).performClick()
                item("detail-list",hasText(title)).assertExists()
                item("detail-list",hasText("得分 85.5")).assertExists()
                capture("main-disk-reopened-detail")
              } catch(failure:Throwable) { runCatching { capture("main-disk-failure-reopen") };throw failure }
            }
            verified=true
        } finally {
            // No clearing or baseline replacement. Delete only a unique child created by
            // this exact UI journey after proving all pre-existing business/draft rows intact.
            val current=runBlocking{repository.restorePreview()}
            val own=current.data.children.filter{it.nickname==nickname && before.data.children.none{old->old.id==it.id}}
            check(own.size<=1) { "Ambiguous cleanup ownership; leave evidence and fail" }
            val child=own.singleOrNull()
            if(child!=null) {
                check(ownedId==null || child.id==ownedId)
                val ownExams=current.data.exams.filter{it.childId==child.id}.map{it.id}.toSet()
                check(current.data.children.filterNot{it.id==child.id}==before.data.children)
                check(current.data.subjects==before.data.subjects)
                check(current.data.exams.filterNot{it.childId==child.id}==before.data.exams)
                check(current.data.scores.filterNot{it.examId in ownExams}==before.data.scores)
                check(current.drafts.filterNot{it.childId==child.id}==before.drafts)
                runBlocking{repository.deleteChild(child.id)}
            }
            val after=runBlocking{repository.restorePreview()}
            assertEquals(before.data,after.data);assertEquals(before.drafts,after.drafts)
            if(verified) {
                val file=File(context.filesDir,"ui-evidence/main-disk-integrity.json")
                file.parentFile!!.mkdirs()
                file.writeText(JSONObject().put("status","PASS").put("activity",MainActivity::class.java.name)
                    .put("storage","production scoreledger.db; independent Room connection read")
                    .put("processDeathClaim",false).put("testCreatedChildId",ownedId).put("testCreatedExamId",examId)
                    .put("originalBusinessSnapshotUnchanged",true).put("originalDraftsUnchanged",true)
                    .put("cleanup","only nonce-owned UI-created child deleted").toString())
            }
        }
    }
}
