package com.example.scoreledger

import android.graphics.Bitmap
import android.os.Build
import android.os.Process
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.core.ScoreStatus
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant

/** Two opt-in instrumentation invocations separated by a shell-owned, evidenced
 * adb force-stop. Never claims ActivityScenario close/recreate proves process death.
 * All new business data is created through real MainActivity UI. */
@RunWith(AndroidJUnit4::class)
class ExternalDraftResumeTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val repository get()=(context.applicationContext as ScoreLedgerApplication).repository
    private fun guard(phase:String):String {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Separate external-stop phase required",args.getString("externalDraftResume")==phase)
        check(args.getString("allowPersistentSyntheticData")=="true")
        check(context.packageName=="com.example.scoreledger.beta.debug" && BuildConfig.DEBUG && BuildConfig.INTERNAL_BETA)
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Disposable emulator required" }
        check(RecreationHostActivity.testRepository==null && RecreationHostActivity.testServices==null)
        return requireNotNull(args.getString("upgradeNonce")).also { check(Regex("[0-9a-f]{32,64}").matches(it)) }
    }
    private fun marker(nonce:String)=File(context.noBackupFilesDir,"external-draft-$nonce.json")
    private fun item(list:String,matcher:SemanticsMatcher):SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    private fun awaitEditor()=compose.waitUntil(10000) {
        compose.onAllNodesWithTag("entry-list").fetchSemanticsNodes().size==1 &&
            compose.onAllNodes(hasTestTag("save-record") and isEnabled()).fetchSemanticsNodes().size==1
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        val file=File(context.filesDir,"ui-evidence/$name.png")
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } } finally { bitmap.recycle() }
    }
    private fun digest(baseline:RestoreBaseline):String {
        val sha=MessageDigest.getInstance("SHA-256")
        DigestOutputStream(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},sha).use {
            BackupCodec.write(baseline.data,it,Instant.parse("2026-01-01T00:00:00Z"))
        }
        fun field(text:String) { val bytes=text.toByteArray(Charsets.UTF_8);sha.update(bytes.size.toString().toByteArray());sha.update(0.toByte());sha.update(bytes) }
        baseline.drafts.sortedBy { it.id }.forEach { field(it.id);field(it.childId);field(it.payloadJson);field(it.updatedAt.toString()) }
        return sha.digest().joinToString(""){"%02x".format(it)}
    }
    private fun withoutOwned(before:RestoreBaseline,childId:String):RestoreBaseline {
        val examIds=before.data.exams.filter { it.childId==childId }.map { it.id }.toSet()
        return before.copy(data=before.data.copy(children=before.data.children.filterNot { it.id==childId },
            exams=before.data.exams.filterNot { it.childId==childId },scores=before.data.scores.filterNot { it.examId in examIds }),
            drafts=before.drafts.filterNot { it.childId==childId })
    }
    private fun cleanupOwned(childId:String,nickname:String,baselineDigest:String) {
        val current=runBlocking { repository.restorePreview() }
        check(current.data.children.single { it.id==childId }.nickname==nickname) { "Ownership changed; refuse cleanup" }
        check(digest(withoutOwned(current,childId))==baselineDigest) { "Pre-existing data changed; refuse cleanup" }
        runBlocking { repository.deleteChild(childId) }
        assertEquals(baselineDigest,digest(runBlocking { repository.restorePreview() }))
    }
    @Test fun prepareOwnedDraftThroughMainActivity() {
        val nonce=guard("prepare");val marker=marker(nonce)
        check(!marker.exists()) { "Never replay a prepared fixture" }
        val upgrade=File(context.noBackupFilesDir,"upgrade-retention-$nonce.json")
        check(upgrade.isFile && upgrade.length() in 1L..4096L)
        val seed=JSONObject(upgrade.readText());check(seed.getString("nonce")==nonce)
        val before=runBlocking { repository.restorePreview() }
        check(before.data.children.size==1 && before.data.children.single().id==seed.getString("childId")) { "Expected guarded synthetic upgrade fixture only" }
        val subject=before.data.subjects.single { !it.archived }
        val baseline=digest(before);val nickname="强停续录-${nonce.take(10)}";val title="强停草稿-${nonce.take(10)}"
        check(before.data.children.none { it.nickname==nickname })
        var ownedId:String?=null;var prepared=false
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                try {
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 }
                    compose.onNodeWithText("设置").performClick()
                    item("settings-list",hasText("孩子档案")).performClick()
                    item("children-list",hasText("创建孩子档案")).performClick()
                    compose.onNodeWithText("昵称 · 最多24字").performTextInput(nickname)
                    compose.onNodeWithText("保存").performClick()
                    compose.waitUntil(10000) { runBlocking { repository.snapshot().children.count { it.nickname==nickname }==1 } }
                    ownedId=runBlocking { repository.snapshot().children.single { it.nickname==nickname }.id }
                    item("children-list",hasText("返回")).performClick()
                    compose.onNodeWithText("记录").performClick()
                    compose.onNodeWithTag("new-record").performClick();awaitEditor()
                    item("entry-list",hasText("修改考试信息")).performClick()
                    item("entry-list",hasText("考试名称")).performTextReplacement(title)
                    item("entry-list",hasTestTag("subject-options-${subject.id}")).performClick()
                    item("entry-list",hasText("本次满分")).performTextReplacement("150")
                    item("entry-list",hasTestTag("score-${subject.id}")).performTextReplacement("85.5")
                    compose.waitUntil(10000) { runBlocking {
                        val row=repository.restorePreview().drafts.singleOrNull { it.childId==ownedId }
                        row!=null && EntryDraft.decode(row).let { d->d.title==title && d.rows.single().let { it.score=="85.5" && it.maximum=="150" && it.status==ScoreStatus.SCORED } }
                    } }
                    val stored=runBlocking { repository.restorePreview() }.drafts.single { it.childId==ownedId }
                    assertEquals(baseline,digest(withoutOwned(runBlocking { repository.restorePreview() },requireNotNull(ownedId))))
                    capture("external-draft-prepared")
                    val payload=JSONObject().put("nonce",nonce).put("childId",ownedId).put("nickname",nickname).put("title",title)
                        .put("subjectId",subject.id).put("draftId",stored.id).put("baselineDigest",baseline)
                        .put("initialChildName",before.data.children.single().nickname).put("preparePid",Process.myPid())
                        .put("draftPayload",stored.payloadJson).put("draftUpdatedAt",stored.updatedAt).put("phase","prepared")
                    FileOutputStream(marker).use { out->out.write(payload.toString().toByteArray(Charsets.UTF_8));out.fd.sync() }
                    prepared=true
                } catch(failure:Throwable) { runCatching { capture("external-draft-prepare-failure") };throw failure }
            }
        } finally {
            if(!prepared) {
                val owned=runBlocking { repository.snapshot() }.children.singleOrNull { it.nickname==nickname && before.data.children.none { old->old.id==it.id } }
                if(owned!=null) cleanupOwned(owned.id,nickname,baseline)
            }
        }
    }
    @Test fun resumeOwnedDraftThroughMainActivityAfterExternalStop() {
        val nonce=guard("resume");val marker=marker(nonce)
        check(marker.isFile && marker.length() in 1L..120000L)
        val saved=JSONObject(marker.readText());check(saved.getString("nonce")==nonce && saved.getString("phase")=="prepared")
        check(saved.getInt("preparePid")!=Process.myPid()) { "A distinct app process is required; shell must separately prove force-stop" }
        val childId=saved.getString("childId");val subjectId=saved.getString("subjectId");val draftId=saved.getString("draftId")
        val nickname=saved.getString("nickname");val title=saved.getString("title");val baseline=saved.getString("baselineDigest")
        val before=runBlocking { repository.restorePreview() }
        assertEquals(baseline,digest(withoutOwned(before,childId)))
        val exact=before.drafts.single { it.id==draftId && it.childId==childId }
        assertEquals(saved.getString("draftPayload"),exact.payloadJson);assertEquals(saved.getLong("draftUpdatedAt"),exact.updatedAt)
        var completed=false
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                try {
                    // MainActivity uses a real SavedStateHandle: Android may reopen the owned
                    // editor, its selected child, or the default child. Observe, do not assume.
                    compose.waitUntil(10000) {
                        (compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 &&
                            compose.onAllNodes(hasTestTag("new-record") and isEnabled()).fetchSemanticsNodes().size==1) ||
                            (compose.onAllNodesWithTag("entry-list").fetchSemanticsNodes().size==1 &&
                                compose.onAllNodes(hasTestTag("save-record") and isEnabled()).fetchSemanticsNodes().size==1)
                    }
                    if(compose.onAllNodesWithTag("entry-list").fetchSemanticsNodes().size==1) {
                        awaitEditor()
                        // Refuse to touch any unexpected editor, including the baseline draft.
                        item("entry-list",hasText(title) and !hasSetTextAction()).assertExists()
                        item("entry-list",hasText(nickname)).assertExists()
                        item("entry-list",hasText("返回")).performClick()
                        compose.onNodeWithText("保留草稿并返回").assertIsEnabled().performClick()
                        compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 &&
                            compose.onAllNodes(hasTestTag("new-record") and isEnabled()).fetchSemanticsNodes().size==1 }
                    }
                    val initialName=saved.getString("initialChildName")
                    val initialVisible=compose.onAllNodesWithText(initialName).fetchSemanticsNodes().size
                    val ownedVisible=compose.onAllNodesWithText(nickname).fetchSemanticsNodes().size
                    assertTrue("Home must show exactly the original or owned child selector",
                        initialVisible==1 && ownedVisible==0 || initialVisible==0 && ownedVisible==1)
                    if(initialVisible==1) {
                        compose.onNodeWithText(initialName).assertIsEnabled().performClick()
                        compose.onNodeWithText(nickname).assertIsEnabled().performClick()
                    }
                    compose.onNodeWithText(nickname).assertExists()
                    item("home-list",hasText("继续草稿：$title")).performClick();awaitEditor()
                    item("entry-list",hasTestTag("score-$subjectId")).assert(SemanticsMatcher.expectValue(
                        androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("85.5")))
                    item("entry-list",hasTestTag("subject-options-$subjectId")).performClick()
                    item("entry-list",hasText("本次满分")).assertTextContains("150")
                    item("entry-list",hasText("有分数")).assertIsSelected()
                    capture("external-draft-resumed")
                    item("entry-list",hasTestTag("score-$subjectId")).performTextReplacement("99.99")
                    compose.onNodeWithTag("save-record").assertIsEnabled().performClick()
                    compose.waitUntil(10000) { compose.onAllNodesWithTag("detail-list").fetchSemanticsNodes().size==1 }
                    item("detail-list",hasText(title)).assertExists()
                    item("detail-list",hasText("得分 99.99")).assertExists()
                    item("detail-list",hasText("本次满分 150")).assertExists()
                    capture("external-draft-saved")
                } catch(failure:Throwable) { runCatching { capture("external-draft-resume-failure") };throw failure }
            }
            val disk=ScoreDatabase.create(context)
            try { runBlocking {
                val exam=requireNotNull(disk.dao().exam(draftId));assertEquals(childId,exam.childId);assertEquals(title,exam.title)
                val score=disk.dao().scores(draftId).single();assertEquals(subjectId,score.subjectId)
                assertEquals(9999L,score.scoreMinor);assertEquals(15000L,score.maxMinor);assertEquals("SCORED",score.status)
                assertNull(disk.dao().draft(draftId,childId))
            } } finally { disk.close() }
            completed=true
        } finally { cleanupOwned(childId,nickname,baseline) }
        if(completed) {
            val result=JSONObject().put("status","PASS").put("preparePid",saved.getInt("preparePid")).put("resumePid",Process.myPid())
                .put("externalStopEvidenceRequired","shell phase log: pre-stop PID, force-stop, absent PID, resume invocation")
                .put("uiResumedPersistedDraft",true).put("uiContinuedAndSavedSameId",true).put("originalSnapshotAndDraftsUnchanged",true)
            File(context.filesDir,"ui-evidence/external-draft-integrity.json").writeText(result.toString())
            saved.put("phase","completed")
            FileOutputStream(marker).use { out->out.write(saved.toString().toByteArray(Charsets.UTF_8));out.fd.sync() }
        }
    }
}
