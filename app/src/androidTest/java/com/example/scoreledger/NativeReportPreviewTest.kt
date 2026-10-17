package com.example.scoreledger

import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.data.*
import com.example.scoreledger.export.*
import com.example.scoreledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeReportPreviewTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun semanticTrees():String {
        val roots=compose.onAllNodes(isRoot(),useUnmergedTree=true)
        return roots.fetchSemanticsNodes().indices.joinToString("\n") { roots[it].printToString(maxDepth=30) }
    }
    private fun captureNavigation(name:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.filesDir,"ui-evidence").apply{mkdirs()}
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory,"$name.png").outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
        File(directory,"$name.txt").writeText(semanticTrees())
    }
    @Test fun nativePreviewIsBoundedAndNavigableWithoutExternalReader() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        var stage="generate-and-validate"
        try {
            val data=instrumentation.context.assets.open("backup-valid.json").use{BackupCodec.read(it)}
            val repository=ScoreRepository(db,true)
            runBlocking { repository.replace(PendingRestore(data,repository.restorePreview())){} }
            val manager=ReportManager(context,repository)
            val report=runBlocking { manager.generate(RecordQuery(data.children.first().id),ReportOptions(full=true,subjectId=data.subjects.first().id)) }
            assertTrue(report.pageCount>1)
            assertEquals(data.scores.size,report.scoreRows)
            assertEquals(manager.reportId(report),java.util.UUID.fromString(manager.reportId(report)).toString())
            // A positive managed report must not weaken the opaque-ID/path validation contract.
            val unmanaged=File(context.cacheDir,"native-preview-unmanaged.pdf").apply{writeText("synthetic unmanaged file")}
            try {
                manager.reportId(ReportFile(unmanaged,1,1))
                fail("Unmanaged native-preview filename must not become a saved report ID")
            }catch(_:IllegalArgumentException){}
            val untouched=File(context.cacheDir,"invalid-id-destination.pdf")
            val sentinel="synthetic existing destination must survive".toByteArray()
            untouched.writeBytes(sentinel)
            try {
                runBlocking { manager.savePending("../outside",android.net.Uri.fromFile(untouched)) }
                fail("Traversal ID must be rejected before opening/truncating destination")
            }catch(_:IllegalArgumentException){}
            assertArrayEquals(sentinel,untouched.readBytes())
            val absent=File(context.cacheDir,"invalid-id-must-not-create.pdf")
            assertFalse(absent.exists())
            try {
                runBlocking { manager.savePending("native-preview-unmanaged",android.net.Uri.fromFile(absent)) }
                fail("Malformed ID must be rejected before creating destination")
            }catch(_:IllegalArgumentException){}
            assertFalse(absent.exists())
            val bitmap=runBlocking { manager.renderPage(report,0) }.bitmap
            assertTrue(bitmap.width<=1000 && bitmap.height<=1500);bitmap.recycle()
            var closed=false
            stage="first-page-render"
            compose.setContent { ScoreLedgerTheme {
                // A Dialog emits no host layout. A dialog-only fixture produced a measured
                // 0x0 Compose host during the captured loading stall. Real app previews sit
                // above a full-size screen; preserve that host/layout lifecycle here too.
                Box(Modifier.fillMaxSize().testTag("native-preview-host")) {
                    NativeReportPreview(manager,report){closed=true}
                }
            } }
            compose.onNodeWithTag("native-preview-host").assertWidthIsAtLeast(100.dp).assertHeightIsAtLeast(100.dp)
            assertEquals(Lifecycle.State.RESUMED,compose.activityRule.scenario.state)
            compose.waitUntil(10000){
                compose.onAllNodesWithContentDescription("报告第1页图像；原值可在记录与趋势明细查看").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("正在读取本页…").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("报告第1页图像；原值可在记录与趋势明细查看").assertIsDisplayed()
            compose.onNodeWithText("上一页").assertIsNotEnabled()
            stage="next-page-touch"
            compose.onNodeWithText("下一页").assertIsDisplayed().assertIsEnabled()
            captureNavigation("native-preview-before-next")
            compose.onNodeWithText("下一页").performClick()
            captureNavigation("native-preview-after-next")
            stage="second-page-render"
            compose.waitUntil(10000){compose.onAllNodesWithText("第2/${report.pageCount}页 · ${report.scoreRows}条成绩").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("正在读取本页…").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithContentDescription("报告第2页图像；原值可在记录与趋势明细查看").assertIsDisplayed()
            compose.onNodeWithText("上一页").assertIsEnabled()
            stage="close-preview"
            compose.onNodeWithText("关闭预览").performClick();compose.runOnIdle{assertTrue(closed)}
        }catch(failure:Throwable) {
            val directory=File(instrumentation.targetContext.filesDir,"ui-evidence")
            // Capture before teardown; do not depend on Compose idleness for essential evidence.
            runCatching {
                directory.mkdirs()
                File(directory,"native-preview-failure.txt").writeText(
                    "stage=$stage\n${failure.stackTraceToString()}\n" +
                        Thread.getAllStackTraces().entries.joinToString("\n") { (thread,frames) ->
                            "thread=${thread.name} state=${thread.state}\n${frames.joinToString("\n")}"
                        }
                )
            }
            runCatching { instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(directory,"native-preview-failure.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
                finally { bitmap.recycle() }
            } }
            runCatching {
                File(directory,"native-preview-semantics.txt").writeText(semanticTrees())
            }
            throw failure
        }finally{db.close();context.cleanup()}
    }
}
