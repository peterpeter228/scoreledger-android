package com.example.scoreledger

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Window
import androidx.compose.ui.graphics.luminance
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real composables/managers, synthetic Room data. Night mode is a scoped configuration
 * fixture, not an assertion about the physical OS settings panel. */
@RunWith(AndroidJUnit4::class)
class PrivacyThemeAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun item(list:String,matcher:SemanticsMatcher):SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun screen(tag:String) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size==1 }
        compose.onNodeWithTag(tag).assertIsDisplayed()
    }
    private fun capture(scenario:ActivityScenario<RecreationHostActivity>,name:String) {
        compose.waitForIdle()
        val frames=CountDownLatch(2)
        val listener=Window.OnFrameMetricsAvailableListener { window,_,_->
            frames.countDown();if(frames.count>0)window.decorView.postInvalidateOnAnimation()
        }
        scenario.onActivity { it.window.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper()));it.window.decorView.postInvalidateOnAnimation() }
        try { assertTrue("Rendered frames required",frames.await(5,TimeUnit.SECONDS)) }
        finally { scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) } }
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(100,3000)
        val directory=File(instrumentation.targetContext.filesDir,"ui-evidence").apply { mkdirs() }
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory,"$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
    }
    private fun fixture(night:Int,body:(ActivityScenario<RecreationHostActivity>,ScoreRepository,String)->Unit) {
        val context=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        val previous=RecreationHostActivity.testNightMode
        try {
            val child=runBlocking { repository.addChild("合成主题孩子") }
            val subject=runBlocking { repository.saveSubject(null,"数学",10000,0) }
            runBlocking { repository.saveNewExam(UUID.randomUUID().toString(),child,"合成主题考试","2026-10-01",listOf(ScoreValue(subject,ScoreStatus.SCORED,8250,10000))) }
            RecreationHostActivity.testNightMode=night
            RecreationHostActivity.testRepository=repository
            RecreationHostActivity.testServices=context.services(repository)
            ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
                screen("home-list")
                try { body(scenario,repository,subject) }
                catch(failure:Throwable) {
                    runCatching { capture(scenario,"privacy-theme-failure") }
                    throw failure
                }
            }
        } finally {
            RecreationHostActivity.testNightMode=previous
            RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null
            db.close();context.cleanup()
        }
    }
    @Test fun privacyAndBackupWarningsExplainLocalDataBoundaries() = fixture(Configuration.UI_MODE_NIGHT_NO) { scenario,repository,_->
        val before=runBlocking { repository.snapshot() }
        compose.onNodeWithText("设置").performClick();screen("settings-list")
        val privacy=item("settings-list",hasText("隐私：记录仅保存在本机",substring=true))
        privacy.assertTextContains("不注册、不云同步、不使用广告分析",substring=true)
        privacy.assertTextContains("JSON备份为明文，不是加密文件",substring=true)
        privacy.assertTextContains("是你主动将文件交给该服务",substring=true)
        capture(scenario,"privacy-local-plaintext")
        val help=item("settings-list",hasText("帮助：卸载通常会删除本机数据",substring=true))
        help.assertTextContains("PDF无法恢复数据",substring=true)
        help.assertTextContains("系统或厂商换机不能替代备份",substring=true)
        help.assertTextContains("备份不含草稿或购买权益",substring=true)
        help.assertTextContains("仅用于本机回滚准备，不是丢机或卸载后的备份",substring=true)
        capture(scenario,"privacy-uninstall-rollback")
        item("settings-list",hasText("免费内测，未开通付费",substring=true)).assertIsDisplayed()
        capture(scenario,"privacy-internal-beta")
        item("settings-list",hasText("导出 JSON 备份（免费）")).assertIsEnabled().performClick()
        compose.onNodeWithText("备份含全部孩子昵称和成绩",substring=true).assertIsDisplayed()
            .assertTextContains("未加密明文",substring=true)
            .assertTextContains("选择第三方文件提供方会将文件交给该服务",substring=true)
            .assertTextContains("备份不包含草稿和购买权益",substring=true)
        capture(scenario,"privacy-export-warning")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("备份含全部孩子昵称和成绩",substring=true).assertDoesNotExist()
        assertEquals(before,runBlocking { repository.snapshot() })
    }
    @Test fun lightScreensRenderWithAccessibleThemeTokens() = themedScreens(Configuration.UI_MODE_NIGHT_NO,"light")
    @Test fun darkScreensRenderWithAccessibleThemeTokens() = themedScreens(Configuration.UI_MODE_NIGHT_YES,"dark")
    private fun themedScreens(night:Int,name:String) = fixture(night) { scenario,_,subject->
        val evidence=org.json.JSONObject().put("mode",name).put("configurationFixture",true)
        scenario.onActivity { activity ->
            val c=requireNotNull(activity.diagnosticColors)
            assertEquals("Actual theme follows scoped configuration",night==Configuration.UI_MODE_NIGHT_YES,c.background.luminance()<0.1f)
            val pairs=listOf("body" to (c.onBackground to c.background),"surface" to (c.onSurface to c.surface),
                "secondaryText" to (c.onSurfaceVariant to c.surfaceVariant),"primaryButton" to (c.onPrimary to c.primary),
                "container" to (c.onPrimaryContainer to c.primaryContainer),"error" to (c.error to c.surface))
            pairs.forEach { (label,pair)->
                val a=pair.first.luminance();val b=pair.second.luminance()
                val ratio=(maxOf(a,b)+0.05)/(minOf(a,b)+0.05)
                evidence.put(label,ratio);assertTrue("$name $label text contrast must be >=4.5:1",ratio>=4.5)
            }
        }
        val directory=File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir,"ui-evidence").apply { mkdirs() }
        File(directory,"theme-$name-contrast.json").writeText(evidence.toString(2))
        compose.onNodeWithTag("latest-record").assertIsDisplayed()
        capture(scenario,"theme-$name-home")
        compose.onNodeWithTag("new-record").assertIsEnabled().performClick();screen("entry-list")
        item("entry-list",hasTestTag("score-$subject")).assertIsDisplayed()
        compose.onNodeWithTag("save-record").assertIsDisplayed()
        capture(scenario,"theme-$name-entry")
        item("entry-list",hasText("返回")).performClick()
        compose.onNodeWithText("保留草稿并返回").performClick();screen("home-list")
        item("home-list",hasTestTag("all-records")).performClick();screen("history-list")
        item("history-list",hasText("1次考试")).assertExists()
        capture(scenario,"theme-$name-history")
        item("history-list",hasText("返回记录")).performClick();screen("home-list")
        compose.onNodeWithText("趋势").performClick();screen("trend-list")
        capture(scenario,"theme-$name-trend")
        item("trend-list",hasTestTag("open-trend-report")).performClick();screen("report-list")
        item("report-list",hasText("生成分页报告")).assertIsDisplayed()
        compose.waitUntil(10000) { compose.onAllNodes(hasText("生成分页报告") and isEnabled()).fetchSemanticsNodes().size==1 }
        item("report-list",hasText("生成分页报告")).assertIsEnabled().performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("报告第1页图像；原值可在记录与趋势明细查看").fetchSemanticsNodes().size==1 && compose.onAllNodesWithText("正在读取本页…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithContentDescription("报告第1页图像；原值可在记录与趋势明细查看").assertIsDisplayed()
        capture(scenario,"theme-$name-native-report")
        compose.onNodeWithText("关闭预览").performClick()
        item("report-list",hasText("返回")).performClick();screen("trend-list")
        compose.onNodeWithText("设置").performClick();screen("settings-list")
        capture(scenario,"theme-$name-settings")
    }
}
