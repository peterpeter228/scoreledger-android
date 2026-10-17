package com.example.scoreledger

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.FileInputStream
import java.util.UUID
import java.security.MessageDigest
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.data.*
import com.example.scoreledger.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Real CreateDocument cancellation and retry through AOSP DocumentsUI.
 * Synthetic isolated Room data; no substituted registry, direct manager save, or OEM claims. */
@RunWith(AndroidJUnit4::class)
class RealPdfDocumentsUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private var stage="setup"
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> {
        if(node==null)return emptyList()
        return listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
    }
    private fun awaitNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        val until=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<until) {
            nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull { node ->
                node.isEnabled && node.isVisibleToUser && node.packageName?.toString()?.contains("documentsui")==true && predicate(node)
            }?.let{return it}
            SystemClock.sleep(150)
        }
        evidence("real-pdf-timeout-$stage")
        error("Required enabled visible real DocumentsUI control missing at $stage")
    }
    private fun awaitAppText(text:String,timeoutMillis:Long) {
        val deadline=SystemClock.uptimeMillis()+timeoutMillis
        while(SystemClock.uptimeMillis()<deadline) {
            // SAF can still own the foreground window after a successful click.
            // Do not ask Compose for a mandatory root until the actual app returns.
            val root=instrumentation.uiAutomation.rootInActiveWindow
            if(root?.packageName?.toString()==instrumentation.targetContext.packageName &&
                compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty())return
            SystemClock.sleep(100)
        }
        evidence("real-pdf-app-return-timeout-$stage")
        error("App did not return with the required result within ${timeoutMillis}ms at $stage")
    }
    private fun click(node:AccessibilityNodeInfo) {
        check(node.refresh() && node.isEnabled && node.isVisibleToUser) { "DocumentsUI target became stale" }
        check(node.packageName?.toString()?.contains("documentsui")==true)
        var current:AccessibilityNodeInfo?=node
        while(current!=null && current.packageName?.toString()?.contains("documentsui")==true) {
            val target=current
            if(target.isEnabled && target.isVisibleToUser && target.actionList.any { it.id==AccessibilityNodeInfo.ACTION_CLICK }) {
                check(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "DocumentsUI click action rejected; not replayed" }
                return
            }
            current=target.parent
        }
        // DocumentsUI RecyclerView document rows can use gesture handling without
        // advertising an accessibility CLICK ancestor. Tap this exact matched file's
        // visible bounds, not a guessed coordinate or a mocked activity result.
        val bounds=Rect();node.getBoundsInScreen(bounds)
        val window=Rect();requireNotNull(instrumentation.uiAutomation.rootInActiveWindow).getBoundsInScreen(window)
        check(!bounds.isEmpty && window.contains(bounds.centerX(),bounds.centerY())) { "File target outside active window" }
        val downTime=SystemClock.uptimeMillis()
        val down=MotionEvent.obtain(downTime,downTime,MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
        val up=MotionEvent.obtain(downTime,downTime+80,MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0).apply { source=InputDevice.SOURCE_TOUCHSCREEN }
        try {
            check(instrumentation.uiAutomation.injectInputEvent(down,true)) { "DocumentsUI touch down rejected" }
            SystemClock.sleep(80)
            check(instrumentation.uiAutomation.injectInputEvent(up,true)) { "DocumentsUI touch up rejected" }
        } finally { down.recycle();up.recycle() }
    }
    /** Diagnostic XML is limited to this synthetic app and real DocumentsUI; other text is redacted. */
    private fun evidence(name:String) {
        runCatching { capture(name) }
        runCatching {
            val file=File(instrumentation.targetContext.filesDir,"ui-evidence/$name.xml")
            file.parentFile!!.mkdirs()
            file.outputStream().use { output ->
                val xml=android.util.Xml.newSerializer();xml.setOutput(output,"UTF-8");xml.startDocument("UTF-8",true)
                xml.startTag(null,"synthetic-ui");xml.attribute(null,"stage",stage)
                var count=0
                fun write(node:AccessibilityNodeInfo?,depth:Int) {
                    if(node==null || depth>40 || count++>1000)return
                    val pkg=node.packageName?.toString().orEmpty()
                    val permitted=pkg.contains("documentsui") || pkg==instrumentation.targetContext.packageName
                    val bounds=Rect();node.getBoundsInScreen(bounds)
                    xml.startTag(null,"node")
                    mapOf("package" to pkg,"class" to node.className?.toString().orEmpty(),"id" to node.viewIdResourceName.orEmpty(),
                        "text" to if(permitted)node.text?.toString().orEmpty().take(1024) else "REDACTED",
                        "enabled" to node.isEnabled.toString(),"visible" to node.isVisibleToUser.toString(),"clickable" to node.isClickable.toString(),
                        "bounds" to bounds.toShortString(),"actions" to node.actionList.joinToString(","){it.id.toString()}).forEach { (key,value)->xml.attribute(null,key,value) }
                    for(i in 0 until node.childCount)write(node.getChild(i),depth+1)
                    xml.endTag(null,"node")
                }
                write(instrumentation.uiAutomation.rootInActiveWindow,0)
                xml.endTag(null,"synthetic-ui");xml.endDocument()
            }
        }
    }
    private fun capture(name:String) {
        val file=File(instrumentation.targetContext.filesDir,"ui-evidence/$name.png")
        file.parentFile!!.mkdirs()
        val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try{file.outputStream().use{check(image.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{image.recycle()}
    }
    private fun reportControl(text:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("report-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
    private fun shellBytes(command:String):ByteArray = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
    }
    private fun selectDownloads() {
        val visible=nodes(instrumentation.uiAutomation.rootInActiveWindow)
        if(visible.none{it.isVisibleToUser && it.text?.toString()=="Downloads"}) {
            click(awaitNode { it.contentDescription?.toString() in setOf("Show roots","Open navigation drawer","显示根目录","打开导航抽屉") })
            click(awaitNode { it.text?.toString()=="Downloads" })
        }
        awaitNode { it.text?.toString()=="Downloads" && it.className?.toString()!="android.widget.EditText" }
    }
    private fun filename(value:String) {
        val input=awaitNode { it.className?.toString()=="android.widget.EditText" }
        check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value)
        })) { "Actual filename control rejected text" }
        awaitNode { it.className?.toString()=="android.widget.EditText" && it.text?.toString()==value }
    }
    @Test fun realPdfPickerCancelRetrySavesSamePreviewSnapshotAndPreservesDatabase() {
        val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        check(RecreationHostActivity.testRegistryOwner==null) { "This test requires actual ActivityResultRegistry and DocumentsUI" }
        val id=UUID.randomUUID().toString()
        val cancelledName="scoreledger-pdf-cancel-$id.pdf"
        val savedName="scoreledger-pdf-saved-$id.pdf"
        val priorFlags=instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            val fixture=instrumentation.context.assets.open("backup-valid.json").use(BackupCodec::read)
            runBlocking {
                repository.replace(PendingRestore(fixture,repository.restorePreview())){}
                repository.putDraft(EntryDraft(childId=fixture.children.first().id,title="合成未提交草稿"))
            }
            val baseline=runBlocking{repository.restorePreview()}
            ActivityScenario.launch(RecreationHostActivity::class.java).use {
              try {
                stage="configure"
                compose.onNodeWithText("趋势").performClick()
                compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("open-trend-report"))
                compose.onNodeWithTag("open-trend-report").performClick()
                reportControl("包含单科趋势").assertIsOn().performClick().assertIsOff()
                reportControl("隐藏昵称，使用“孩子A”").assertIsOn()
                reportControl("生成分页报告").performClick()
                awaitAppText("保存PDF",15000)
                stage="native-preview"
                compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("报告第1页图像；原值可在记录与趋势明细查看").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("第1/1页 · 9条成绩").assertIsDisplayed()
                capture("real-pdf-preview")
                compose.onNodeWithText("文字核对").performClick()
                val previewText=compose.onNodeWithText("统计口径",substring=true).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("\n"){it.text}
                listOf("0 / 100","90 / 120","缺考","未录入","孩子A（匿名昵称）").forEach { assertTrue("Missing actual preview value: $it",previewText.contains(it)) }
                assertFalse(previewText.contains(fixture.children.single().nickname))
                compose.onNodeWithText("页面图像").performClick()
                val cached=File(isolated.cacheDir,"reports").listFiles().orEmpty().single{it.extension=="pdf"}
                val expected=cached.readBytes()
                assertTrue(expected.size>1000)
                stage="cancel-create-document"
                compose.onNodeWithText("保存PDF").performClick()
                selectDownloads();filename(cancelledName)
                capture("real-pdf-cancel-picker")
                instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
                awaitAppText("保存PDF",15000)
                compose.onNodeWithText("关闭预览").performClick()
                awaitAppText("已取消保存，没有报告保存成功",15000)
                reportControl("已取消保存，没有报告保存成功").assertIsDisplayed()
                assertEquals("ABSENT",shellBytes("test ! -e /sdcard/Download/$cancelledName && echo ABSENT").toString(Charsets.UTF_8).trim())
                assertEquals(baseline,runBlocking{repository.restorePreview()})
                assertArrayEquals(expected,cached.readBytes())
                assertEquals(1,File(isolated.cacheDir,"reports").listFiles().orEmpty().count{it.extension=="pdf"})
                capture("real-pdf-cancelled")
                stage="retry-create-document"
                reportControl("应用内预览 PDF").performClick()
                compose.onNodeWithText("保存PDF").assertIsEnabled().performClick()
                selectDownloads();filename(savedName)
                instrumentation.uiAutomation.waitForIdle(200,5000)
                capture("real-pdf-save-picker")
                click(awaitNode { it.viewIdResourceName?.endsWith("/menu_save")==true || it.text?.toString()?.equals("Save",true)==true })
                awaitAppText("保存PDF",20000)
                compose.onNodeWithText("关闭预览").performClick()
                awaitAppText("PDF已保存（已回读校验）",20000)
                reportControl("PDF已保存（已回读校验）").assertIsDisplayed()
                stage="verify-exported-bytes"
                val actual=shellBytes("cat /sdcard/Download/$savedName")
                assertArrayEquals("The real Downloads file must equal the already-previewed snapshot byte for byte",expected,actual)
                assertEquals(baseline,runBlocking{repository.restorePreview()})
                assertEquals(1,File(isolated.cacheDir,"reports").listFiles().orEmpty().count{it.extension=="pdf"})
                val pdfEvidence=File(instrumentation.targetContext.filesDir,"pdf-evidence").apply{mkdirs()}
                val exported=File(pdfEvidence,"real-documentsui-saved-report.pdf").apply{writeBytes(actual)}
                PdfRenderer(ParcelFileDescriptor.open(exported,ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                    assertEquals(1,renderer.pageCount)
                    renderer.openPage(0).use { page ->
                        assertEquals(595,page.width);assertEquals(842,page.height)
                        val bitmap=Bitmap.createBitmap(1190,1684,Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(android.graphics.Color.WHITE)
                            page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pixels=IntArray(bitmap.width*bitmap.height)
                            bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                            assertTrue(pixels.count { android.graphics.Color.red(it)<180 && android.graphics.Color.green(it)<180 && android.graphics.Color.blue(it)<180 }>1000)
                            File(pdfEvidence,"real-documentsui-saved-report-page.png").outputStream().use{output->assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))}
                        }finally{bitmap.recycle()}
                    }
                }
                capture("real-pdf-saved")
                fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                File(instrumentation.targetContext.filesDir,"ui-evidence/real-pdf-result.json").writeText(JSONObject()
                    .put("test","realPdfPickerCancelRetrySavesSamePreviewSnapshotAndPreservesDatabase")
                    .put("provider","AOSP DocumentsUI / Downloads")
                    .put("registry","real")
                    .put("cancelledFileAbsent",true).put("sameSnapshotBytes",true).put("databaseAndDraftsUnchanged",true)
                    .put("expectedSha256",hash(expected)).put("exportedSha256",hash(actual))
                    .put("bytes",actual.size).put("pageCount",1).put("scoreRows",fixture.scores.size).toString(2))
              }catch(failure:Throwable){evidence("real-pdf-failure-$stage");throw failure}
            }
        }finally {
            // Only the two unique synthetic filenames owned by this test are removed.
            runCatching { shellBytes("rm -f /sdcard/Download/$cancelledName /sdcard/Download/$savedName") }
            instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply { flags=priorFlags }
            RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null
            db.close();isolated.cleanup()
        }
    }
}
