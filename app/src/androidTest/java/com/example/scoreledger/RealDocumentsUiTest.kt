package com.example.scoreledger

import android.graphics.Bitmap
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
    private fun nodes(node:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> {
        if(node==null)return emptyList()
        return listOf(node)+(0 until node.childCount).flatMap{nodes(node.getChild(it))}
    }
    private fun awaitNode(predicate:(AccessibilityNodeInfo)->Boolean):AccessibilityNodeInfo {
        val until=SystemClock.uptimeMillis()+15000
        while(SystemClock.uptimeMillis()<until) {
            nodes(instrumentation.uiAutomation.rootInActiveWindow).firstOrNull(predicate)?.let{return it}
            SystemClock.sleep(150)
        }
        error("Required real DocumentsUI control missing")
    }
    private fun click(node:AccessibilityNodeInfo) {
        var current:AccessibilityNodeInfo?=node
        while(current!=null && !current.isClickable)current=current.parent
        check(requireNotNull(current).performAction(AccessibilityNodeInfo.ACTION_CLICK))
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
        try {
            val fixture=instrumentation.context.assets.open("backup-valid.json").use{BackupCodec.read(it)}
            runBlocking{repository.replace(PendingRestore(fixture,repository.restorePreview())){}}
            val before=runBlocking{repository.snapshot()}
            ActivityScenario.launch(RecreationHostActivity::class.java).use {
                compose.onNodeWithText("设置").performClick()
                setting("导出 JSON 备份（免费）").performClick()
                compose.onNodeWithText("选择保存位置").performClick()
                val filename="scoreledger-real-provider.json"
                val input=awaitNode { it.className?.toString()=="android.widget.EditText" && it.packageName?.toString()?.contains("documentsui")==true }
                check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename)}))
                capture("real-saf-create")
                click(awaitNode { it.isEnabled && (it.viewIdResourceName?.endsWith("/menu_save")==true || it.text?.toString()?.equals("Save",true)==true) })
                compose.waitUntil(20000){compose.onAllNodesWithText("备份已保存并回读校验，请妥善保管明文文件").fetchSemanticsNodes().isNotEmpty()}
                setting("选择备份并预览恢复（免费）").performClick()
                awaitNode { it.packageName?.toString()?.contains("documentsui")==true }
                instrumentation.uiAutomation.executeShellCommand("input keyevent 4").close()
                compose.waitUntil(10000){compose.onAllNodesWithText("已取消选择，数据未改变").fetchSemanticsNodes().isNotEmpty()}
                assertEquals(before,runBlocking{repository.snapshot()})
                setting("选择备份并预览恢复（免费）").performClick()
                // Newly created document appears in the same Downloads provider (or Recents).
                click(awaitNode { it.text?.toString()==filename })
                compose.waitUntil(15000){compose.onAllNodesWithText("确认替换并丢弃旧草稿").fetchSemanticsNodes().isNotEmpty()}
                capture("real-saf-restore-preview")
                compose.onNodeWithText("确认替换并丢弃旧草稿").performClick()
                compose.waitUntil(15000){compose.onAllNodesWithText("恢复完成，已替换数据；旧草稿已丢弃").fetchSemanticsNodes().isNotEmpty()}
                assertEquals(before,runBlocking{repository.snapshot()})
                capture("real-saf-restored")
            }
        }finally{RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
