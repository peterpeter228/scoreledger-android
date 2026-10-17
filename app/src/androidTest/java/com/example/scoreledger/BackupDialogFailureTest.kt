package com.example.scoreledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import com.example.scoreledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupDialogFailureTest {
    @get:Rule val compose=createComposeRule()
    private fun waitError(){compose.waitUntil(10000){compose.onAllNodesWithText("操作未完成",substring=true).fetchSemanticsNodes().isNotEmpty()}}
    @Test fun staleClearDismissesConfirmationAndShowsActionableError() {
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build();val r=ScoreRepository(db,true)
        try {
            runBlocking{r.addChild("旧记录")};val services=isolated.services(r)
            compose.setContent{CompositionLocalProvider(LocalUiServices provides services){val content=rememberBackupSettings();ScoreLedgerTheme{Column(Modifier.verticalScroll(rememberScrollState())){content()}}}}
            compose.onNodeWithText("清除本机全部记录").performScrollTo().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("确认删除").fetchSemanticsNodes().isNotEmpty()}
            runBlocking{r.addChild("预览后新增")}
            compose.onNodeWithText("确认删除").performClick();waitError()
            compose.onNodeWithText("确认删除").assertDoesNotExist();assertEquals(2,runBlocking{db.dao().childCount()})
            compose.onNodeWithText("操作未完成",substring=true).assertExists()
        }finally{db.close();isolated.cleanup()}
    }
    @Test fun rollbackFileFailureDismissesRestoreAndPreservesData() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build();val r=ScoreRepository(db,true)
        val registry=object:ActivityResultRegistry(){var code:Int?=null;override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?){code=requestCode}}
        val owner=object:ActivityResultRegistryOwner{override val activityResultRegistry=registry}
        try {
            runBlocking{r.addChild("必须保留")};val before=runBlocking{r.restorePreview()};val services=isolated.services(r)
            val fixture=File(isolated.cacheDir,"fixture.json");instrumentation.context.assets.open("backup-valid.json").use{input->fixture.outputStream().use{input.copyTo(it)}}
            File(isolated.noBackupFilesDir,"rollback").writeText("synthetic blocked directory")
            compose.setContent{CompositionLocalProvider(LocalUiServices provides services,LocalActivityResultRegistryOwner provides owner){val content=rememberBackupSettings();ScoreLedgerTheme{Column(Modifier.verticalScroll(rememberScrollState())){content()}}}}
            compose.onNodeWithText("选择备份并预览恢复（免费）").performScrollTo().performClick()
            compose.waitUntil(10000){registry.code!=null}
            instrumentation.runOnMainSync{registry.dispatchResult(requireNotNull(registry.code),Activity.RESULT_OK,Intent().setData(Uri.fromFile(fixture)))}
            compose.waitUntil(10000){compose.onAllNodesWithText("确认替换并丢弃旧草稿").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("确认替换并丢弃旧草稿").performClick();waitError()
            compose.onNodeWithText("确认替换并丢弃旧草稿").assertDoesNotExist();assertEquals(before,runBlocking{r.restorePreview()})
        }finally{db.close();isolated.cleanup()}
    }
}
