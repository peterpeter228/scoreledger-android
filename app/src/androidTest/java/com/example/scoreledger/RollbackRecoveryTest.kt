package com.example.scoreledger

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.backup.BackupManager
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RollbackRecoveryTest {
    @Test fun previousSnapshotCanBeExportedAndRecoveredWithNewProtection()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        try {
            val repo=ScoreRepository(db,true);val manager=BackupManager(context,repo)
            val original=instrumentation.context.assets.open("backup-valid.json").use(BackupCodec::read)
            repo.replace(PendingRestore(original,repo.restorePreview())){}
            val stored=repo.snapshot()
            val replacement=DataSnapshot(emptyList(),emptyList(),emptyList(),emptyList())
            manager.restore(PendingRestore(replacement,repo.restorePreview()))
            assertTrue(repo.snapshot().exams.isEmpty())
            val copy=manager.listRollbacks().single()
            val preview=manager.previewRollback(copy.id)
            assertEquals(original.exams.toSet(),preview.data.exams.toSet())
            val destination=File(context.cacheDir,"recovered.json")
            assertTrue(manager.exportRollback(copy.id,Uri.fromFile(destination)).verified)
            assertEquals(stored,destination.inputStream().use(BackupCodec::read))
            manager.restore(preview)
            assertEquals(original.exams.toSet(),repo.snapshot().exams.toSet())
            assertEquals(2,manager.listRollbacks().size)
            assertTrue(manager.listRollbacks().any { manager.previewRollback(it.id).data.exams.isEmpty() })
            // A stale recovery preview never replaces intervening changes.
            val stale=manager.previewRollback(copy.id);repo.addChild("合成新增")
            val before=repo.restorePreview()
            try{manager.restore(stale);fail("Stale rollback preview accepted")}catch(_:IllegalStateException){}
            assertEquals(before,repo.restorePreview())
        }finally{db.close();context.cleanup()}
    }
}
