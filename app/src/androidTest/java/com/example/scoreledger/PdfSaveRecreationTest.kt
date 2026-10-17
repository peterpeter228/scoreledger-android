package com.example.scoreledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
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

@RunWith(AndroidJUnit4::class)
class PdfSaveRecreationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun history(text:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("history-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text)
    }
    private fun saveFailureEvidence() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.getExternalFilesDir(null),"ui-evidence").apply{mkdirs()}
        runCatching { File(directory,"pdf-save-recreation-failure.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString()) }
        runCatching { instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(directory,"pdf-save-recreation-failure.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)} }
            finally {bitmap.recycle()}
        } }
    }
    @Test fun pendingSafSaveSurvivesActivityRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build();val repository=ScoreRepository(db,true)
        val registry=object:ActivityResultRegistry(){var pendingCode:Int?=null
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){pendingCode=requestCode}}
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        RecreationHostActivity.testRegistryOwner=object:ActivityResultRegistryOwner {override val activityResultRegistry=registry}
        try {
            val data=instrumentation.context.assets.open("backup-valid.json").use{BackupCodec.read(it)}
            runBlocking {repository.replace(PendingRestore(data,repository.restorePreview())){}}
            ActivityScenario.launch(RecreationHostActivity::class.java).use{scenario ->
                compose.waitUntil(10000){compose.onAllNodesWithText("PDF 报告").fetchSemanticsNodes().isNotEmpty()}
                history("PDF 报告").performClick()
                history("生成分页报告").performClick()
                compose.waitUntil(15000){compose.onAllNodesWithText("选择位置保存 PDF").fetchSemanticsNodes().isNotEmpty()}
                history("选择位置保存 PDF").performClick()
                compose.waitUntil(10000){registry.pendingCode!=null}
                val code=requireNotNull(registry.pendingCode)
                scenario.recreate();compose.waitForIdle()
                val target=File(isolated.cacheDir,"controlled-target.pdf")
                // Result delivery must not depend on a lazy report item being composed.
                instrumentation.runOnMainSync { assertTrue(registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target)))) }
                try {
                compose.waitUntil(15000){target.isFile && target.length()>0}
                // Recreation removes the non-saveable preview/buttons, so the restored lazy
                // offset may now be below the report item. Bring status into view explicitly.
                history("PDF 报告").assertExists()
                compose.waitUntil(15000){compose.onAllNodesWithText("PDF已保存").fetchSemanticsNodes().isNotEmpty()}
                history("PDF已保存").assertIsDisplayed()
                assertTrue(target.length()>0)
                val original=File(isolated.cacheDir,"reports").listFiles()!!.single{it.extension=="pdf"}
                assertArrayEquals(original.readBytes(),target.readBytes())
                } catch(failure:Throwable) { saveFailureEvidence();throw failure }
            }
        }finally{RecreationHostActivity.testRegistryOwner=null;RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
