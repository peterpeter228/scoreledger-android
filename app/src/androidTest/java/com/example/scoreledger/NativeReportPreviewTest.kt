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
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        val file=File.createTempFile("native-preview-",".pdf",context.cacheDir)
        try {
            val data=instrumentation.context.assets.open("backup-valid.json").use{BackupCodec.read(it)}
            val plan=PdfReport.plan(data,RecordQuery(data.children.first().id),ReportOptions(full=true))
            assertTrue(plan.pages.size>1)
            file.outputStream().use{PdfReport.write(plan,it)}
            val report=ReportFile(file,plan.pages.size,plan.scoreRows);val manager=ReportManager(context,ScoreRepository(db,true))
            val bitmap=runBlocking { manager.renderPage(report,0) }.bitmap
            assertTrue(bitmap.width<=1000 && bitmap.height<=1500);bitmap.recycle()
            var closed=false
            compose.setContent{ScoreLedgerTheme{NativeReportPreview(manager,report){closed=true}}}
            compose.waitUntil(10000){compose.onAllNodesWithText("正在读取本页…").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithText("上一页").assertIsNotEnabled()
            compose.onNodeWithText("下一页").assertIsEnabled().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("第2/${plan.pages.size}页 · ${plan.scoreRows}条成绩").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("正在读取本页…").fetchSemanticsNodes().isEmpty()}
            compose.onNodeWithText("上一页").assertIsEnabled()
            compose.onNodeWithText("关闭预览").performClick();compose.runOnIdle{assertTrue(closed)}
        }finally{file.delete();db.close()}
    }
}
