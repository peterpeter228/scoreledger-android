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
                instrumentation.runOnMainSync { registry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target))) }
                compose.waitUntil(15000){compose.onAllNodesWithText("PDF已保存").fetchSemanticsNodes().isNotEmpty()}
                assertTrue(target.length()>0)
                val original=File(isolated.cacheDir,"reports").listFiles()!!.single{it.extension=="pdf"}
                assertArrayEquals(original.readBytes(),target.readBytes())
            }
        }finally{RecreationHostActivity.testRegistryOwner=null;RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
