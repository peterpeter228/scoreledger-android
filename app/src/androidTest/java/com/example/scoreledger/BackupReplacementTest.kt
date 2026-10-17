package com.example.scoreledger

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class BackupReplacementTest {
    private fun database()=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
    private fun fixture()=InstrumentationRegistry.getInstrumentation().context.assets.open("backup-valid.json").use { BackupCodec.read(it) }
    private suspend fun fails(action:suspend ()->Unit) { var failed=false;try{action()}catch(_:Exception){failed=true};assertTrue(failed) }
    @Test fun restoreExactRoundtripRepeatAndDraftDiscard()=runBlocking {
        val db=database();try {
            val r=ScoreRepository(db,true);val child=r.addChild("旧记录");r.putDraft(EntryDraft(childId=child))
            val data=fixture();val pending=PendingRestore(data,r.restorePreview());var rollback:DataSnapshot?=null
            r.replace(pending){rollback=it};assertEquals("旧记录",rollback?.children?.single()?.nickname)
            assertEquals(0,db.dao().allDrafts().size)
            assertEquals(data.children.toSet(),r.snapshot().children.toSet());assertEquals(data.exams.toSet(),r.snapshot().exams.toSet());assertEquals(data.scores.toSet(),r.snapshot().scores.toSet())
            val after=r.snapshot();r.addChild("恢复后新记录")
            fails { r.replace(pending){} };assertEquals(after.children.size+1,r.snapshot().children.size)
            r.replace(PendingRestore(data,r.restorePreview())){};assertEquals(data.exams.size,db.dao().examCount())
        } finally {db.close()}
    }
    @Test fun stalePreviewAndRollbackFileFailurePreserveDrafts()=runBlocking {
        val db=database();try {
            val r=ScoreRepository(db,true);val child=r.addChild("旧记录");val draft=EntryDraft(childId=child);r.putDraft(draft)
            val old=PendingRestore(fixture(),r.restorePreview());r.putDraft(draft.copy(title="更改"),draft.encode())
            fails {r.replace(old){fail("stale preview must not write rollback")}}
            val before=r.restorePreview();fails {r.replace(PendingRestore(fixture(),before)){throw IOException("synthetic failure")}}
            assertEquals(before,r.restorePreview())
        }finally{db.close()}
    }
    @Test fun transactionFailureRollsBackClearsAndPreservesDrafts()=runBlocking {
        val db=database();try {
            val r=ScoreRepository(db,true);val child=r.addChild("旧记录");r.putDraft(EntryDraft(childId=child));val before=r.restorePreview()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER test_import_failure BEFORE INSERT ON scores BEGIN SELECT RAISE(ABORT, 'synthetic'); END")
            fails {r.replace(PendingRestore(fixture(),before)){} }
            assertEquals(before.data,r.snapshot());assertEquals(before.drafts,db.dao().allDrafts())
        }finally{db.close()}
    }
    @Test fun previousEditorGenerationCannotRecreateDiscardedDraft()=runBlocking {
        val db=database();try {
            val r=ScoreRepository(db,true);val data=fixture();r.replace(PendingRestore(data,r.restorePreview())){}
            val epoch=r.generation.value;val draft=EntryDraft(childId=data.children.first().id);r.putDraft(draft)
            r.replace(PendingRestore(data,r.restorePreview())){}
            fails {withContext(WriteGeneration(epoch)){r.putDraft(draft)}}
            assertTrue(db.dao().allDrafts().isEmpty())
        }finally{db.close()}
    }
}
