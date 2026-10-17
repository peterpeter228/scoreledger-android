package com.example.scoreledger

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.graphics.Rect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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
import java.time.LocalDate
import java.util.UUID

/** Observational workload, not a benchmark SLA: timings include test synchronization.
 * In-memory Room and isolated managers; 20-exam report segment respects the PDF bound. */
@RunWith(AndroidJUnit4::class)
class ScaleWorkloadUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun item(list:String,matcher:SemanticsMatcher):SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    /** Wait for the actual focused input connection before injecting exactly one edit.
     * Genuine Back ends the keyboard session; never repair lost content by retyping. */
    private fun replaceText(scenario:ActivityScenario<RecreationHostActivity>,list:String,matcher:SemanticsMatcher,value:String) {
        item(list,matcher).performClick()
        val automation=instrumentation.uiAutomation
        val flags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply { this.flags=this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            compose.waitUntil(10000) {
                var visible=false
                scenario.onActivity {
                    val insets=ViewCompat.getRootWindowInsets(it.window.decorView)
                    visible=insets?.isVisible(WindowInsetsCompat.Type.ime())==true &&
                        (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)>0
                }
                visible && automation.windows.any { window -> window.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    Rect().also { window.getBoundsInScreen(it) }.let { !it.isEmpty && it.height()>0 } } &&
                    compose.onAllNodes(matcher and isFocused()).fetchSemanticsNodes().size==1
            }
        } finally { automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags } }
        val exact=SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString(value))
        compose.onNode(matcher).assertIsFocused().performTextReplacement(value)
        compose.onNode(matcher).assert(exact)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(10000) {
            var hidden=false
            scenario.onActivity {
                val insets=ViewCompat.getRootWindowInsets(it.window.decorView)
                hidden=insets!=null && !insets.isVisible(WindowInsetsCompat.Type.ime()) && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom==0
            }
            hidden
        }
        item(list,matcher).assert(exact)
    }
    private fun capture(name:String) {
        val file=File(instrumentation.targetContext.filesDir,"ui-evidence/$name.png")
        file.parentFile!!.mkdirs()
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
    }
    private fun memory():JSONObject {
        val info=Debug.MemoryInfo();Debug.getMemoryInfo(info)
        val runtime=Runtime.getRuntime()
        return JSONObject().put("totalPssKiB",info.totalPss).put("nativeAllocatedBytes",Debug.getNativeHeapAllocatedSize())
            .put("managedUsedBytes",runtime.totalMemory()-runtime.freeMemory()).put("managedMaxBytes",runtime.maxMemory())
    }
    @Test fun fiveHundredExamsScrollEditSaveAndSegmentedReportRecordMeasuredCost() {
        val context=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        fun id(n:Long)=UUID(0,n).toString()
        val child=ChildEntity(id(1),"合成规模测试","四年级",false,1,1)
        val subjects=(0 until 10).map { SubjectEntity(id(10L+it),"合成科目${it.toString().padStart(2,'0')}",10000,it,false,1,1) }
        val exams=(0 until 500).map { ExamEntity(id(100L+it),child.id,"合成考试${it.toString().padStart(3,'0')}",LocalDate.of(2024,1,1).plusDays(it.toLong()).toString(),"OTHER","合成学期","四年级","",1,1) }
        val scores=exams.flatMap { exam -> subjects.map { ScoreEntity(exam.id,it.id,it.name,"SCORED",8000,10000) } }
        val fixture=DataSnapshot(listOf(child),subjects,exams,scores)
        val evidence=JSONObject().put("examCount",500).put("scoreCount",5000)
            .put("timingMeaning","elapsed wall time including instrumentation and Compose synchronization; no performance SLA asserted")
            .put("storage","isolated in-memory Room").put("acceptanceScope","AC068 PARTIAL observations; not physical SQLite storage performance, peak memory, or StrictMode proof").put("memoryBeforeSeed",memory())
        runBlocking { repository.replace(PendingRestore(fixture,repository.restorePreview())){} }
        val before=runBlocking { repository.restorePreview() }
        assertEquals(500,before.data.exams.size);assertEquals(5000,before.data.scores.size)
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=context.services(repository)
        var stage="launch"
        try {
            val launched=SystemClock.elapsedRealtime()
            ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
                try {
                    compose.waitUntil(30000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 }
                    evidence.put("launchToHomeMs",SystemClock.elapsedRealtime()-launched).put("memoryHome",memory())
                    item("home-list",hasTestTag("all-records")).performClick()
                    compose.waitUntil(30000) { compose.onAllNodesWithTag("history-list").fetchSemanticsNodes().size==1 }
                    item("history-list",hasText("500次考试")).assertIsDisplayed()
                    stage="history-scroll"
                    val scrollStart=SystemClock.elapsedRealtime()
                    item("history-list",hasTestTag("record-${exams.first().id}")).assertIsDisplayed()
                    evidence.put("scrollLatestToOldestMs",SystemClock.elapsedRealtime()-scrollStart).put("memoryAfterScroll",memory())
                    capture("scale-history-oldest")
                    compose.onNodeWithTag("record-${exams.first().id}").performClick()
                    item("detail-list",hasText("编辑这次记录")).performClick()
                    compose.waitUntil(30000) { compose.onAllNodesWithTag("entry-list").fetchSemanticsNodes().size==1 }
                    replaceText(scenario,"entry-list",hasTestTag("score-${subjects.first().id}"),"81.25")
                    stage="save"
                    val saveStart=SystemClock.elapsedRealtime()
                    compose.onNodeWithTag("save-record").assertIsEnabled().performClick()
                    compose.waitUntil(30000) { compose.onAllNodesWithTag("saved-record-notice").fetchSemanticsNodes().size==1 }
                    val after=runBlocking { repository.restorePreview() }
                    assertEquals(500,after.data.exams.size);assertEquals(5000,after.data.scores.size);assertTrue(after.drafts.isEmpty())
                    assertEquals(before.data.children,after.data.children);assertEquals(before.data.subjects,after.data.subjects)
                    assertEquals(before.data.exams.filterNot { it.id==exams.first().id },after.data.exams.filterNot { it.id==exams.first().id })
                    assertEquals(before.data.scores.map { if(it.examId==exams.first().id && it.subjectId==subjects.first().id)it.copy(scoreMinor=8125) else it }.toSet(),after.data.scores.toSet())
                    evidence.put("saveToDurableDetailMs",SystemClock.elapsedRealtime()-saveStart).put("memoryAfterSave",memory())
                    capture("scale-saved-detail")
                    // Navigate through real UI into all-record scope, then deliberately bound output.
                    item("detail-list",hasText("返回记录")).performClick()
                    item("history-list",hasText("返回记录")).performClick()
                    compose.onNodeWithText("趋势").performClick()
                    item("trend-list",hasTestTag("open-trend-report")).performClick()
                    compose.waitUntil(30000) { compose.onAllNodesWithText("孩子：合成规模测试 · 500次考试").fetchSemanticsNodes().size==1 }
                    item("report-list",hasText("孩子：合成规模测试 · 500次考试")).assertExists()
                    item("report-list",hasText("按考试序号分段（同日考试也可拆分）")).performClick()
                    replaceText(scenario,"report-list",hasText("起始考试序号") and hasSetTextAction(),"1")
                    replaceText(scenario,"report-list",hasText("结束考试序号") and hasSetTextAction(),"20")
                    stage="report"
                    val reportStart=SystemClock.elapsedRealtime()
                    item("report-list",hasText("生成分页报告")).assertIsEnabled().performClick()
                    compose.waitUntil(60000) { compose.onAllNodesWithText("关闭预览").fetchSemanticsNodes().size==1 }
                    compose.waitUntil(30000) { compose.onAllNodesWithContentDescription("报告第1页图像；原值可在记录与趋势明细查看").fetchSemanticsNodes().size==1 }
                    evidence.put("reportGenerateAndFirstPageMs",SystemClock.elapsedRealtime()-reportStart).put("reportExamCount",20).put("reportScoreCount",200).put("memoryWithPreview",memory())
                    compose.onNode(hasText("条成绩",substring=true)).assertTextContains("200条成绩",substring=true)
                    val pdf=File(context.cacheDir,"reports").listFiles().orEmpty().single { it.extension=="pdf" }
                    ParcelFileDescriptor.open(pdf,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer -> assertTrue(renderer.pageCount>0);evidence.put("reportPages",renderer.pageCount) }
                    }
                    assertEquals(after,runBlocking { repository.restorePreview() })
                    capture("scale-report-preview")
                    evidence.put("status","PASS").put("reportBytes",pdf.length()).put("exactUnrelatedRowsPreserved",true)
                    File(instrumentation.targetContext.filesDir,"ui-evidence/scale-workload-observations.json").writeText(evidence.toString())
                } catch(failure:Throwable) {
                    evidence.put("status","FAIL").put("stage",stage).put("memoryAtFailure",memory())
                    val file=File(instrumentation.targetContext.filesDir,"ui-evidence/scale-workload-failure.json");file.parentFile!!.mkdirs();file.writeText(evidence.toString())
                    runCatching { capture("scale-workload-failure") };throw failure
                }
            }
        } finally { RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();context.cleanup() }
    }
}
