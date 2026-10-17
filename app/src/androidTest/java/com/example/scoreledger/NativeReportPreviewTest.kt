package com.example.scoreledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
    @get:Rule val compose=createComposeRule()
    @Test fun nativePreviewIsBoundedAndNavigableWithoutExternalReader() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
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
            compose.setContent{ScoreLedgerTheme{NativeReportPreview(manager,report){closed=true}}}
            compose.waitUntil(10000){compose.onAllNodesWithText("正在读取本页…").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithContentDescription("报告第1页图像；原值可在记录与趋势明细查看").assertIsDisplayed()
            compose.onNodeWithText("上一页").assertIsNotEnabled()
            compose.onNodeWithText("下一页").assertIsEnabled().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("第2/${report.pageCount}页 · ${report.scoreRows}条成绩").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("正在读取本页…").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithContentDescription("报告第2页图像；原值可在记录与趋势明细查看").assertIsDisplayed()
            compose.onNodeWithText("上一页").assertIsEnabled()
            compose.onNodeWithText("关闭预览").performClick();compose.runOnIdle{assertTrue(closed)}
        }finally{db.close();context.cleanup()}
    }
}
