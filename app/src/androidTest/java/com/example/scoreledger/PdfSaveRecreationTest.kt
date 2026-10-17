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
        compose.onNodeWithTag("report-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
    private fun saveFailureEvidence(stage:String,failure:Throwable) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.filesDir,"ui-evidence")
        // Essential evidence must not query Compose: a non-idle tree can block diagnostics.
        runCatching {
            directory.mkdirs()
            File(directory,"pdf-save-recreation-failure.txt")
                .writeText("stage=$stage\n${failure.stackTraceToString()}")
        }
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
        var stage="seed"
        var failureRecorded=false
        try {
            val data=instrumentation.context.assets.open("backup-valid.json").use{BackupCodec.read(it)}
            runBlocking {repository.replace(PendingRestore(data,repository.restorePreview())){}}
            stage="launch"
            ActivityScenario.launch(RecreationHostActivity::class.java).use{scenario ->
              try {
                stage="navigate-report"
                compose.onNodeWithText("趋势").performClick()
                compose.onNodeWithTag("trend-list").performScrollToNode(hasTestTag("open-trend-report"))
                compose.onNodeWithTag("open-trend-report").performClick()
                stage="generate-report"
                history("生成分页报告").performClick()
                stage="wait-preview"
                compose.waitUntil(15000){compose.onAllNodesWithText("保存PDF").fetchSemanticsNodes().isNotEmpty()}
                stage="request-save"
                compose.onNodeWithText("保存PDF").performClick()
                stage="wait-picker-request"
                compose.waitUntil(10000){registry.pendingCode!=null}
                val code=requireNotNull(registry.pendingCode)
                stage="recreate"
                scenario.recreate()
                stage="wait-recreated-idle"
                compose.waitForIdle()
                val target=File(isolated.cacheDir,"controlled-target.pdf")
                // Result delivery must not depend on a lazy report item being composed.
                stage="dispatch-result"
                instrumentation.runOnMainSync { assertTrue(registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target)))) }
                stage="wait-target-written"
                compose.waitUntil(15000){target.isFile && target.length()>0}
                // Recreation removes the non-saveable preview/buttons, so the restored lazy
                // offset may now be below the report item. Bring status into view explicitly.
                stage="close-preview-and-show-status"
                compose.onNodeWithText("关闭预览").performClick()
                history("PDF 报告").assertExists()
                stage="wait-saved-status"
                compose.waitUntil(15000){compose.onAllNodesWithText("PDF已保存（已回读校验）").fetchSemanticsNodes().isNotEmpty()}
                history("PDF已保存（已回读校验）").assertIsDisplayed()
                stage="verify-snapshot-bytes"
                assertTrue(target.length()>0)
                val original=File(isolated.cacheDir,"reports").listFiles()!!.single{it.extension=="pdf"}
                assertArrayEquals(original.readBytes(),target.readBytes())
                stage="scenario-close"
              } catch(failure:Throwable) {
                // Record the original operation failure before use invokes scenario.close().
                saveFailureEvidence(stage,failure);failureRecorded=true;throw failure
              }
            }
        }catch(failure:Throwable) {
            // Also capture a close-only failure, without overwriting a pre-cleanup failure.
            if(!failureRecorded)saveFailureEvidence(stage,failure)
            throw failure
        }finally{RecreationHostActivity.testRegistryOwner=null;RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
