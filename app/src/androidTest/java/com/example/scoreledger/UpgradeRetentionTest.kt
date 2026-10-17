package com.example.scoreledger

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Explicit opt-in ONLY, on a disposable emulator. Never clears, replaces, or deletes app data.
 * Seed requires an entirely empty real app DB. Run separately before/after adb install -r.
 * Normal test discovery skips these methods before touching the application repository. */
@RunWith(AndroidJUnit4::class)
class UpgradeRetentionTest {
    private fun guard(mode:String):String {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue("Opt-in persistent synthetic test",args.getString("upgradeRetention")==mode)
        check(args.getString("allowPersistentSyntheticData")=="true") { "Explicit disposable-emulator opt-in required" }
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.example.scoreledger.beta.debug" && BuildConfig.DEBUG && BuildConfig.INTERNAL_BETA)
        check(Build.HARDWARE in setOf("ranchu","goldfish")) { "Disposable emulator only; physical devices forbidden" }
        val nonce=requireNotNull(args.getString("upgradeNonce"))
        check(Regex("[0-9a-f]{32,64}").matches(nonce))
        return nonce
    }
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun marker(nonce:String)=File(context.noBackupFilesDir,"upgrade-retention-$nonce.json")
    private fun digest(baseline:RestoreBaseline):String {
        val sha=MessageDigest.getInstance("SHA-256")
        // Streaming deterministic business snapshot plus exact ordered persisted draft values.
        DigestOutputStream(object:OutputStream(){override fun write(b:Int){};override fun write(b:ByteArray,off:Int,len:Int){}},sha).use { out ->
            BackupCodec.write(baseline.data,out,Instant.parse("2026-01-01T00:00:00Z"))
        }
        fun field(text:String){val bytes=text.toByteArray(Charsets.UTF_8);sha.update(bytes.size.toString().toByteArray());sha.update(0.toByte());sha.update(bytes)}
        baseline.drafts.sortedBy{it.id}.forEach {field(it.id);field(it.childId);field(it.payloadJson);field(it.updatedAt.toString())}
        return sha.digest().joinToString(""){"%02x".format(it)}
    }
    @Test fun seedPersistentSyntheticData()=runBlocking {
        val nonce=guard("seed");val r=(context.applicationContext as ScoreLedgerApplication).repository
        val before=r.restorePreview()
        check(before.data.children.isEmpty() && before.data.subjects.isEmpty() && before.data.exams.isEmpty() && before.data.scores.isEmpty() && before.drafts.isEmpty()) { "Seed requires empty disposable app; no data will be cleared" }
        val marker=marker(nonce);check(!marker.exists()) { "Marker already exists; seed is not replayed" }
        val child=r.addChild("升级测试-${nonce.take(8)}","合成年级")
        val subject=r.saveSubject(null,"升级测试科目",12000,0)
        val exam=UUID.randomUUID().toString()
        r.saveExam(ExamInput(exam,child,"升级保留测试",LocalDate.now().minusDays(1).toString(),term="合成学期",note="仅用于本次模拟器验证",values=listOf(ScoreValue(subject,ScoreStatus.SCORED,0,12000))),false)
        val draft=EntryDraft(childId=child,title="升级后仍应保留草稿",rows=listOf(DraftRow(subject,"升级测试科目","８５．５","120",ScoreStatus.SCORED)))
        r.putDraft(draft)
        val after=r.restorePreview()
        check(after.data.children.size==1 && after.data.exams.size==1 && after.data.scores.size==1 && after.drafts.size==1)
        val payload=JSONObject().put("nonce",nonce).put("childId",child).put("examId",exam).put("draftId",draft.id).put("digest",digest(after)).toString()
        java.io.FileOutputStream(marker).use{it.write(payload.toByteArray(Charsets.UTF_8));it.fd.sync()}
        assertTrue(marker.isFile)
    }
    @Test fun verifyPersistentSyntheticData()=runBlocking {
        val nonce=guard("verify");val marker=marker(nonce)
        check(marker.isFile && marker.length() in 1L..4096L) { "Seed marker missing; no verification success" }
        val expected=JSONObject(marker.readText(Charsets.UTF_8));assertEquals(nonce,expected.getString("nonce"))
        val r=(context.applicationContext as ScoreLedgerApplication).repository;val actual=r.restorePreview()
        assertEquals(1,actual.data.children.size);assertEquals(1,actual.data.exams.size);assertEquals(1,actual.data.scores.size);assertEquals(1,actual.drafts.size)
        assertEquals(expected.getString("childId"),actual.data.children.single().id)
        assertEquals(expected.getString("examId"),actual.data.exams.single().id)
        assertEquals(expected.getString("draftId"),actual.drafts.single().id)
        assertEquals(expected.getString("digest"),digest(actual))
        // No cleanup: parent disposes the AVD. Never clear/uninstall to make this test pass.
    }
}
