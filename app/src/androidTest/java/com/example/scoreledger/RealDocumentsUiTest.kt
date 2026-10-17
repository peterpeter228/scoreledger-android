package com.example.scoreledger

import android.accessibilityservice.AccessibilityServiceInfo
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual AOSP DocumentsUI and Downloads provider; no fake activity-result registry.
 * This proves the AOSP provider only, not OEM/cloud providers or physical devices. */
@RunWith(AndroidJUnit4::class)
class RealDocumentsUiTest {
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
        evidence("real-saf-timeout-$stage")
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
        evidence("real-saf-app-return-timeout-$stage")
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
    private fun setting(text:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
    @Test fun realDownloadsExportCancelAndRestorePreserveExactSyntheticSnapshot() {
        val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        check(RecreationHostActivity.testRegistryOwner==null)
        val priorFlags=instrumentation.uiAutomation.serviceInfo.flags
        instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        try {
            val fixture=instrumentation.context.assets.open("backup-valid.json").use{BackupCodec.read(it)}
            runBlocking{repository.replace(PendingRestore(fixture,repository.restorePreview())){}}
            val before=runBlocking{repository.snapshot()}
            ActivityScenario.launch(RecreationHostActivity::class.java).use {
              try {
                compose.onNodeWithText("设置").performClick()
                setting("导出 JSON 备份（免费）").performClick()
                compose.onNodeWithText("选择保存位置").performClick()
                stage="create-document"
                val filename="scoreledger-real-provider.json"
                val input=awaitNode { it.className?.toString()=="android.widget.EditText" && it.packageName?.toString()?.contains("documentsui")==true }
                check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename)}))
                awaitNode { it.className?.toString()=="android.widget.EditText" && it.text?.toString()==filename }
                instrumentation.uiAutomation.waitForIdle(200,5000)
                capture("real-saf-create")
                click(awaitNode { it.isEnabled && (it.viewIdResourceName?.endsWith("/menu_save")==true || it.text?.toString()?.equals("Save",true)==true) })
                awaitAppText("备份已保存并回读校验，请妥善保管明文文件",20000)
                stage="cancel-picker"
                setting("选择备份并预览恢复（免费）").performClick()
                awaitNode { it.packageName?.toString()?.contains("documentsui")==true }
                instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
                awaitAppText("已取消选择，数据未改变",10000)
                assertEquals(before,runBlocking{repository.snapshot()})
                setting("选择备份并预览恢复（免费）").performClick()
                stage="select-created-document"
                // Newly created document appears in the same Downloads provider (or Recents).
                awaitNode { it.text?.toString()==filename }
                instrumentation.uiAutomation.waitForIdle(200,5000)
                evidence("real-saf-open-document")
                click(awaitNode { it.text?.toString()==filename && it.className?.toString()!="android.widget.EditText" })
                stage="restore-preview"
                awaitAppText("确认替换并丢弃旧草稿",15000)
                capture("real-saf-restore-preview")
                compose.onNodeWithText("确认替换并丢弃旧草稿").performClick()
                awaitAppText("恢复完成，已替换数据；旧草稿已丢弃",15000)
                assertEquals(before,runBlocking{repository.snapshot()})
                capture("real-saf-restored")
              } catch(failure:Throwable) { evidence("real-saf-failure-$stage");throw failure }
            }
        }finally{instrumentation.uiAutomation.serviceInfo=instrumentation.uiAutomation.serviceInfo.apply { flags=priorFlags };RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
