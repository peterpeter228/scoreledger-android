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
class BackupSafRecreationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun setting(text:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
    @Test fun exportAndRestorePickerResultsSurviveRecreation() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build();val repository=ScoreRepository(db,true)
        val registry=object:ActivityResultRegistry(){var code:Int?=null
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){code=requestCode}}
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        RecreationHostActivity.testRegistryOwner=object:ActivityResultRegistryOwner{override val activityResultRegistry=registry}
        try {
            runBlocking{repository.addChild("合成备份重建")}
            ActivityScenario.launch(RecreationHostActivity::class.java).use{scenario ->
                compose.onNodeWithText("设置").performClick()
                setting("导出 JSON 备份（免费）").performClick()
                compose.onNodeWithText("选择保存位置").performClick()
                compose.waitUntil(10000){registry.code!=null};val exportCode=requireNotNull(registry.code)
                scenario.recreate();compose.waitForIdle()
                val target=File(isolated.cacheDir,"synthetic-backup.json")
                instrumentation.runOnMainSync{assertTrue(registry.dispatchResult(exportCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target))))}
                compose.waitUntil(15000){target.isFile && target.length()>0}
                setting("导出 JSON 备份（免费）").assertExists()
                compose.waitUntil(15000){compose.onAllNodesWithText("备份已保存并回读校验，请妥善保管明文文件").fetchSemanticsNodes().isNotEmpty()}
                setting("备份已保存并回读校验，请妥善保管明文文件").assertIsDisplayed()
                val exported=target.inputStream().use{BackupCodec.read(it)}
                assertEquals("合成备份重建",exported.children.single().nickname)
                registry.code=null
                setting("选择备份并预览恢复（免费）").performClick()
                compose.waitUntil(10000){registry.code!=null};val restoreCode=requireNotNull(registry.code)
                scenario.recreate();compose.waitForIdle()
                setting("选择备份并预览恢复（免费）").assertExists()
                instrumentation.runOnMainSync{assertTrue(registry.dispatchResult(restoreCode,Activity.RESULT_OK,Intent().setData(Uri.fromFile(target))))}
                compose.waitUntil(15000){compose.onAllNodesWithText("确认替换并丢弃旧草稿").fetchSemanticsNodes().isNotEmpty()}
                assertEquals(exported.children,runBlocking{repository.snapshot()}.children)
                // Preview only: never confirms a destructive replacement in this lifecycle test.
            }
        }finally{RecreationHostActivity.testRegistryOwner=null;RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
}
