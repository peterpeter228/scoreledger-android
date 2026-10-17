package com.example.scoreledger

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** Actual production History/Trend composables with isolated synthetic Room data.
 * Pixel checks inspect the rendered chart, not a mocked drawing model. Spoken TalkBack is not tested. */
@RunWith(AndroidJUnit4::class)
class TrendHistoryAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val output get()=File(instrumentation.targetContext.filesDir,"ui-evidence").apply { mkdirs() }
    private val chartDescription="得分率趋势，纵轴0至100。可通过下方考试明细选择并读取每个点。"
    private fun awaitFocusedKeyboard(scenario:ActivityScenario<RecreationHostActivity>) {
        val automation=instrumentation.uiAutomation
        val oldFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
            compose.waitUntil(10000) {
                var visible=false
                scenario.onActivity {
                    val insets=androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                    visible=insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true &&
                        (insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0)>0
                }
                visible && automation.windows.any { window ->
                    window.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                        android.graphics.Rect().also { window.getBoundsInScreen(it) }.let { !it.isEmpty && it.height()>0 }
                } && compose.onAllNodes(hasTestTag("filter-search") and isFocused()).fetchSemanticsNodes().size==1
            }
        } finally { automation.serviceInfo=automation.serviceInfo.apply { flags=oldFlags } }
    }
    private fun assertSearchText()=compose.onNodeWithTag("filter-search").assert(
        SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString("合成排序")))
    private fun awaitKeyboardClosed(scenario:ActivityScenario<RecreationHostActivity>) {
        compose.waitUntil(10000) {
            var gone=false
            scenario.onActivity {
                val insets=androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                gone=insets!=null && !insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) &&
                    insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom==0
            }
            gone
        }
    }
    private fun writeImage(name:String,bitmap:Bitmap) {
        File(output,"$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
    private fun capture(name:String,scenario:ActivityScenario<RecreationHostActivity>) {
        compose.waitForIdle()
        val frames=CountDownLatch(2)
        val listener=Window.OnFrameMetricsAvailableListener { window,_,_->
            frames.countDown();if(frames.count>0)window.decorView.postInvalidateOnAnimation()
        }
        scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper()));it.window.decorView.postInvalidateOnAnimation() }
        try { check(frames.await(5,TimeUnit.SECONDS)) } finally { scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) } }
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { writeImage(name,bitmap) } finally { bitmap.recycle() }
    }
    @Test fun filteredHistoryOrdersSameDayAndBackfillWhileChartPreservesGapsFixedAxisAndRawSemantics() {
        val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        val ids=(0..3).map { UUID.randomUUID().toString() }
        try {
            runBlocking {
                val child=repository.addChild("合成图表验收")
                val subject=repository.saveSubject(null,"合成数学",10000,0)
                suspend fun seed(index:Int,title:String,date:String,score:Long?,created:Long) {
                    repository.saveExam(ExamInput(ids[index],child,title,date,values=listOf(ScoreValue(subject,if(score==null)ScoreStatus.ABSENT else ScoreStatus.SCORED,score,10000))),false)
                    db.openHelper.writableDatabase.execSQL("UPDATE exams SET createdAt=? WHERE id=?",arrayOf(created,ids[index]))
                }
                seed(1,"合成排序同日缺考","2026-10-02",null,100L)
                seed(2,"合成排序同日后录","2026-10-02",8100,200L)
                seed(0,"合成排序补录过去","2026-10-01",8000,300L)
                seed(3,"合成不匹配排除","2026-10-03",9900,400L)
            }
            val baseline=runBlocking { repository.snapshot() }
            ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
                try {
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("latest-record").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("home-list").performScrollToNode(hasTestTag("all-records"))
                    compose.onNodeWithTag("all-records").performClick()
                    compose.onNodeWithTag("filter-toggle").performClick()
                    compose.onNodeWithTag("filter-search").performScrollTo().performTouchInput { click() }
                    awaitFocusedKeyboard(scenario)
                    compose.onNodeWithTag("filter-search").performTextReplacement("合成排序")
                    assertSearchText()
                    // A real Back closes the already-proven IME, never navigates blindly.
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    awaitKeyboardClosed(scenario)
                    assertSearchText()
                    compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("apply-filters"))
                    val apply=compose.onNodeWithTag("apply-filters").performScrollTo().assertIsDisplayed().assertIsEnabled()
                    compose.waitForIdle()
                    val initialBounds=apply.fetchSemanticsNode().boundsInRoot
                    capture("trend-filter-before-apply",scenario)
                    assertEquals("Apply bounds stable across real draw frames",initialBounds,apply.fetchSemanticsNode().boundsInRoot)
                    apply.performTouchInput { click() }
                    compose.waitUntil(10000) {
                        var applied=false
                        scenario.onActivity { applied=it.diagnosticModel?.recordQuery?.value?.titleSearch=="合成排序" }
                        applied && compose.onAllNodesWithText("3次考试").fetchSemanticsNodes().size==1 &&
                            compose.onAllNodesWithTag("filter-search").fetchSemanticsNodes().isEmpty()
                    }
                    compose.onNodeWithTag("history-list").performScrollToIndex(0)
                    compose.onNodeWithTag("history-list").performScrollToNode(hasTestTag("record-${ids[2]}"))
                    val ordered=listOf(2,1,0).map { index -> compose.onNodeWithTag("record-${ids[index]}").assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
                    assertTrue("Same-day createdAt order, then late-inserted past date",ordered.zipWithNext().all { (a,b)->a.top<b.top })
                    compose.onNodeWithTag("record-${ids[3]}").assertDoesNotExist()
                    capture("trend-history-filtered-order",scenario)
                    compose.onNodeWithTag("history-list").performScrollToNode(hasText("返回记录"))
                    compose.onNodeWithText("返回记录").performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 }
                    scenario.onActivity { assertEquals("Shared filter survives return", "合成排序",it.diagnosticModel!!.recordQuery.value.titleSearch) }
                    compose.onNodeWithText("趋势",useUnmergedTree=true).performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithContentDescription(chartDescription).fetchSemanticsNodes().isNotEmpty() }
                    val chart=compose.onNodeWithContentDescription(chartDescription)
                    chart.performScrollTo().assertIsDisplayed()
                    compose.waitForIdle()
                    val bounds=chart.fetchSemanticsNode().boundsInRoot
                    val margin=bounds.height*16f/220f
                    listOf("100","75","50","25","0").forEachIndexed { i,label ->
                        val axis=compose.onNodeWithText(label,useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        assertEquals("Fixed axis label $label aligns with grid",bounds.top+margin+(bounds.height-2*margin)*i/4,axis.center.y,3f)
                    }
                    val bitmap=chart.captureToImage().asAndroidBitmap()
                    var density=0f
                    scenario.onActivity { density=it.resources.displayMetrics.density }
                    assertEquals("Whole220dp chart captured",220f*density,bitmap.height.toFloat(),2f)
                    writeImage("trend-gap-chart",bitmap)
                    val m=bitmap.height*16f/220f
                    fun x(index:Int)=(m+index*(bitmap.width-2*m)/2).roundToInt()
                    fun y(rate:Float)=(m+(bitmap.height-2*m)*(1-rate/100f)).roundToInt()
                    val primary=bitmap.getPixel(x(0),y(80f))
                    fun nearPrimary(pixel:Int)=abs(Color.red(pixel)-Color.red(primary))<24 && abs(Color.green(pixel)-Color.green(primary))<24 && abs(Color.blue(pixel)-Color.blue(primary))<24
                    assertTrue("Both real score dots use chart color",nearPrimary(bitmap.getPixel(x(2),y(81f))))
                    assertFalse("Dot is not background",nearPrimary(bitmap.getPixel(x(0),y(65f))))
                    var bridgePixels=0;var zeroPixels=0
                    for(px in bitmap.width/3..2*bitmap.width/3) {
                        for(py in y(85f)..y(75f))if(nearPrimary(bitmap.getPixel(px,py)))bridgePixels++
                        for(py in y(2f)..y(0f))if(nearPrimary(bitmap.getPixel(px,py)))zeroPixels++
                    }
                    assertEquals("No line bridges score/ABSENT/score",0,bridgePixels)
                    assertEquals("ABSENT is not a zero score point",0,zeroPixels)
                    capture("trend-gap-fixed-axis",scenario)
                    val rows=listOf("2026-10-02 · 合成排序同日后录" to "81 / 100 · 得分率 81.00%","2026-10-02 · 合成排序同日缺考" to "缺考","2026-10-01 · 合成排序补录过去" to "80 / 100 · 得分率 80.00%")
                    rows.forEach { (title,value) ->
                        val row=hasText(title) and hasClickAction()
                        compose.onNodeWithTag("trend-list").performScrollToNode(row)
                        compose.onNode(row).assertIsDisplayed().assert(hasText(value))
                    }
                    capture("trend-raw-value-semantics",scenario)
                    File(output,"trend-history-integrity.json").writeText(JSONObject().put("orderedIds",listOf(ids[2],ids[1],ids[0])).put("bridgePixels",bridgePixels).put("absentZeroPixels",zeroPixels).put("fixedAxis","0..100").put("rawSemanticsRows",3).put("spokenTalkBackTested",false).toString(2))
                    assertEquals(baseline,runBlocking { repository.snapshot() })
                } catch(failure:Throwable) {
                    runCatching { capture("trend-history-failure",scenario) }
                    runCatching { File(output,"trend-history-failure-semantics.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString()) }
                    throw failure
                }
            }
        } finally { RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup() }
    }
}
