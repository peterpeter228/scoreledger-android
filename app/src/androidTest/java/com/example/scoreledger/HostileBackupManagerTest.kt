package com.example.scoreledger

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.*
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual manager entry points, isolated Room/files; not an external SAF-provider test. */
@RunWith(AndroidJUnit4::class)
class HostileBackupManagerTest {
    @Test fun duplicateAndOrphanBackupsFailPreviewAndRestoreWithoutChangingRowsOrDrafts()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        try {
            val repository=ScoreRepository(db,true)
            val manager=BackupManager(context,repository)
            val source=instrumentation.context.assets.open("backup-valid.json").bufferedReader().use { it.readText() }
            val valid=source.byteInputStream().use(BackupCodec::read)
            repository.replace(PendingRestore(valid,repository.restorePreview())){}
            repository.putDraft(EntryDraft(childId=valid.children.first().id,title="合成待保护草稿"))
            val before=repository.restorePreview()
            val missing="ffffffff-ffff-ffff-ffff-ffffffffffff"
            val cases=listOf<Pair<String,(JSONObject)->Unit>>(
                "duplicate-child" to { j -> j.getJSONArray("children").put(JSONObject(j.getJSONArray("children").getJSONObject(0).toString()));Unit },
                "duplicate-subject" to { j -> j.getJSONArray("subjects").put(JSONObject(j.getJSONArray("subjects").getJSONObject(0).toString()));Unit },
                "duplicate-exam" to { j -> j.getJSONArray("exams").put(JSONObject(j.getJSONArray("exams").getJSONObject(0).toString()));Unit },
                "duplicate-score" to { j -> j.getJSONArray("scores").put(JSONObject(j.getJSONArray("scores").getJSONObject(0).toString()));Unit },
                "orphan-child" to { j -> j.getJSONArray("exams").getJSONObject(0).put("childId",missing);Unit },
                "orphan-subject" to { j -> j.getJSONArray("scores").getJSONObject(0).put("subjectId",missing);Unit },
                "orphan-exam" to { j -> j.getJSONArray("scores").getJSONObject(0).put("examId",missing);Unit }
            )
            val invalid=listOf(
                valid.copy(children=valid.children+valid.children.first()),
                valid.copy(subjects=valid.subjects+valid.subjects.first()),
                valid.copy(exams=valid.exams+valid.exams.first()),
                valid.copy(scores=valid.scores+valid.scores.first()),
                valid.copy(exams=valid.exams.mapIndexed { i,e -> if(i==0)e.copy(childId=missing) else e }),
                valid.copy(scores=valid.scores.mapIndexed { i,s -> if(i==0)s.copy(subjectId=missing) else s }),
                valid.copy(scores=valid.scores.mapIndexed { i,s -> if(i==0)s.copy(examId=missing) else s })
            )
            val evidence=JSONObject()
            cases.forEachIndexed { index,(label,mutate) ->
                val json=JSONObject(source).also(mutate)
                val file=File(context.cacheDir,"hostile-$index.json").apply { writeText(json.toString()) }
                try {
                    try { manager.preview(Uri.fromFile(file));fail("$label preview accepted") }
                    catch(error:BackupException) { assertEquals(label,BackupFailure.INVALID,error.reason) }
                    assertEquals("$label preview changed Room",before,repository.restorePreview())
                    // Defense in depth: bypass parsing with an in-memory invalid pending snapshot.
                    val pending=PendingRestore(invalid[index],before)
                    try { manager.restore(pending);fail("$label restore accepted") }
                    catch(_:IllegalArgumentException) { }
                    assertEquals("$label restore changed Room",before,repository.restorePreview())
                    assertFalse(pending.used)
                    assertTrue("invalid restore must not create rollback",manager.listRollbacks().isEmpty())
                    evidence.put(label,JSONObject().put("previewRejected",true).put("restoreRejected",true).put("exactBaselinePreserved",true))
                } finally { file.delete() }
            }
            val out=File(instrumentation.targetContext.filesDir,"ui-evidence/hostile-backup-manager-integrity.json")
            out.parentFile!!.mkdirs()
            out.writeText(JSONObject().put("status","PASS").put("storage","isolated in-memory Room").put("cases",evidence).put("draftCount",before.drafts.size).toString())
        } finally { db.close();context.cleanup() }
    }
}
