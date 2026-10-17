package com.example.scoreledger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.result.*
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.ActivityOptionsCompat
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

/** Real application confirmation UI with a controlled picker callback, not a real SAF test. */
@RunWith(AndroidJUnit4::class)
class EmptyRestoreUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun setting(text:String):SemanticsNodeInteraction {
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }
    private fun awaitText(text:String) {
        compose.waitUntil(15000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun capture(name:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val destination=File(instrumentation.targetContext.filesDir,"ui-evidence/$name.png")
        destination.parentFile!!.mkdirs()
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { destination.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
    }
    @Test fun controlledPickerEmptyRestoreCancelPreservesDraftsAndConfirmProtectsOriginal() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val isolated=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        val services=isolated.services(repository)
        val registry=object:ActivityResultRegistry() {
            var pendingCode:Int?=null
            override fun <I,O> onLaunch(requestCode:Int,contract:ActivityResultContract<I,O>,input:I,options:ActivityOptionsCompat?) { pendingCode=requestCode }
        }
        RecreationHostActivity.testRepository=repository
        RecreationHostActivity.testServices=services
        RecreationHostActivity.testRegistryOwner=object:ActivityResultRegistryOwner { override val activityResultRegistry=registry }
        var stage="seed"
        try {
            val fixture=instrumentation.context.assets.open("backup-valid.json").use(BackupCodec::read)
            runBlocking {
                repository.replace(PendingRestore(fixture,repository.restorePreview())){}
                repository.putDraft(EntryDraft(childId=fixture.children.single().id,title="合成空备份恢复草稿"))
            }
            val before=runBlocking{repository.restorePreview()}
            assertEquals(1,before.drafts.size)
            val empty=DataSnapshot(emptyList(),emptyList(),emptyList(),emptyList())
            val input=File(isolated.cacheDir,"synthetic-empty-backup.json")
            input.outputStream().use{BackupCodec.write(empty,it)}
            ActivityScenario.launch(RecreationHostActivity::class.java).use {
              try {
                stage="open-settings"
                compose.onNodeWithText("设置").performClick()
                fun selectEmpty() {
                    registry.pendingCode=null
                    setting("选择备份并预览恢复（免费）").performClick()
                    compose.waitUntil(10000){registry.pendingCode!=null}
                    instrumentation.runOnMainSync {
                        assertTrue(registry.dispatchResult(requireNotNull(registry.pendingCode),Activity.RESULT_OK,Intent().setData(Uri.fromFile(input))))
                    }
                    awaitText("确认替换并丢弃旧草稿")
                    compose.onNodeWithText("将导入0个孩子、0次考试、0条成绩。",substring=true).assertIsDisplayed()
                    compose.onNodeWithText("无考试",substring=true).assertIsDisplayed()
                    compose.onNodeWithText("当前3次考试将被完整替换，1份草稿将丢弃。不是合并。",substring=true).assertIsDisplayed()
                    compose.onNodeWithText("快照不含草稿",substring=true).assertIsDisplayed()
                }
                stage="empty-preview-cancel"
                selectEmpty()
                assertEquals(before,runBlocking{repository.restorePreview()})
                capture("empty-restore-warning")
                compose.onNodeWithText("取消").performClick()
                compose.waitUntil(5000){compose.onAllNodesWithText("确认替换并丢弃旧草稿").fetchSemanticsNodes().isEmpty()}
                assertEquals(before,runBlocking{repository.restorePreview()})
                assertTrue(runBlocking{services.backup.listRollbacks()}.isEmpty())
                capture("empty-restore-cancelled")
                stage="empty-preview-confirm"
                selectEmpty()
                compose.onNodeWithText("确认替换并丢弃旧草稿").performClick()
                compose.waitUntil(15000){runBlocking{repository.snapshot()}==empty}
                compose.waitUntil(15000){compose.onAllNodesWithText("确认替换并丢弃旧草稿").fetchSemanticsNodes().isEmpty()}
                setting("恢复完成，已替换数据；旧草稿已丢弃").assertIsDisplayed()
                assertEquals(empty,runBlocking{repository.snapshot()})
                assertTrue(runBlocking{repository.restorePreview()}.drafts.isEmpty())
                val copy=runBlocking{services.backup.listRollbacks()}.single()
                assertEquals(before.data,runBlocking{services.backup.previewRollback(copy.id)}.data)
                stage="recover-protected-original"
                // No editor was open. A redundant global "editor closed" Snackbar used to
                // obscure this immediate rollback action despite successful empty restore.
                compose.onNodeWithText("数据已恢复或清理，旧编辑页面已关闭").assertDoesNotExist()
                val recover=setting("预览并取回").assertIsDisplayed().assertIsEnabled()
                capture("empty-restore-confirmed")
                recover.performClick()
                awaitText("确认替换并丢弃旧草稿")
                compose.onNodeWithText("将导入1个孩子、3次考试、9条成绩。",substring=true).assertIsDisplayed()
                compose.onNodeWithText("确认替换并丢弃旧草稿").performClick()
                compose.waitUntil(15000){runBlocking{repository.snapshot()}==before.data}
                compose.waitUntil(15000){compose.onAllNodesWithText("确认替换并丢弃旧草稿").fetchSemanticsNodes().isEmpty()}
                assertTrue(runBlocking{repository.restorePreview()}.drafts.isEmpty())
                assertEquals(2,runBlocking{services.backup.listRollbacks()}.size)
                assertTrue(runBlocking{services.backup.listRollbacks().any { services.backup.previewRollback(it.id).data==empty }})
                setting("恢复完成，已替换数据；旧草稿已丢弃").assertIsDisplayed()
                capture("empty-restore-recovered")
                File(instrumentation.targetContext.filesDir,"ui-evidence/empty-restore-result.json").writeText(
                    org.json.JSONObject().put("picker","controlled ActivityResult callback; not real SAF")
                        .put("emptyWarningVisible",true).put("cancelPreservedExactSnapshotAndDrafts",true)
                        .put("confirmedDataEmpty",true).put("confirmedDraftsEmpty",true)
                        .put("originalRollbackRecoveredThroughUi",true).put("draftsExcludedFromRollback",true)
                        .put("protectiveCopies",2).toString(2)
                )
              } catch(failure:Throwable) {
                runCatching { File(instrumentation.targetContext.filesDir,"ui-evidence/empty-restore-failure.txt").apply{parentFile!!.mkdirs();writeText("stage=$stage\n${failure.stackTraceToString()}")} }
                runCatching { capture("empty-restore-failure") }
                throw failure
              }
            }
        } finally {
            RecreationHostActivity.testRegistryOwner=null;RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null
            db.close();isolated.cleanup()
        }
    }
}
