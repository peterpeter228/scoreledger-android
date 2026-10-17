package com.example.scoreledger

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TrendInteractionTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun selectedEarlierPointShowsItsActualScoreAndReportKeepsExplicitScope() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build();val r=ScoreRepository(db,true)
        RecreationHostActivity.testRepository=r;RecreationHostActivity.testServices=isolated.services(r)
        val firstDate=LocalDate.now().minusDays(2).toString()
        try {
            runBlocking {
                val child=r.addChild("合成趋势孩子");val subject=r.saveSubject(null,"合成数学",10000,0)
                r.saveExam(ExamInput(UUID.randomUUID().toString(),child,"合成较早考试",firstDate,values=listOf(ScoreValue(subject,ScoreStatus.SCORED,8000,10000))),false)
                r.saveExam(ExamInput(UUID.randomUUID().toString(),child,"合成较新考试",LocalDate.now().minusDays(1).toString(),values=listOf(ScoreValue(subject,ScoreStatus.SCORED,9000,15000))),false)
            }
            ActivityScenario.launch(RecreationHostActivity::class.java).use {
                compose.onNodeWithText("趋势").performClick()
                compose.waitUntil(10000){compose.onAllNodesWithTag("selected-trend-value").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithTag("selected-trend-value").assertTextEquals("90 / 150")
                compose.onNodeWithTag("trend-list").performScrollToNode(hasText("$firstDate · 合成较早考试"))
                compose.onNodeWithText("$firstDate · 合成较早考试").performClick()
                compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("selected-trend-value"))
                compose.onNodeWithTag("selected-trend-value").performScrollTo().assertTextEquals("80 / 100")
                val file=File(instrumentation.targetContext.filesDir,"ui-evidence/trend-selected-earlier.png");file.parentFile!!.mkdirs()
                val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try{file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{image.recycle()}
                compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("open-trend-report"))
                compose.onNodeWithTag("open-trend-report").performScrollTo().performClick()
                compose.onNodeWithTag("report-list").assertExists()
                compose.waitUntil(10000){compose.onAllNodesWithText("孩子：合成趋势孩子 · 2次考试").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("孩子：合成趋势孩子 · 2次考试").assertExists()
                compose.onNodeWithText("合成数学").assertExists()
            }
        }finally{RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
