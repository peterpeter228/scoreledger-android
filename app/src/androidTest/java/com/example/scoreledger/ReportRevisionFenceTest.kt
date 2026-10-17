package com.example.scoreledger

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.data.*
import com.example.scoreledger.export.*
import com.example.scoreledger.ui.FileOperationModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReportRevisionFenceTest {
    @Test fun changedScoreWithSameQueryRejectsOldResultButPendingSaveKeepsSnapshot()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=IsolatedUiContext(instrumentation.targetContext)
        val db=Room.inMemoryDatabaseBuilder(context,ScoreDatabase::class.java).build()
        try {
            val repository=ScoreRepository(db,true)
            val fixture=instrumentation.context.assets.open("backup-valid.json").use(BackupCodec::read)
            repository.replace(PendingRestore(fixture,repository.restorePreview())){}
            val before=repository.snapshot();val query=RecordQuery(before.children.first().id)
            val manager=ReportManager(context,repository);val model=FileOperationModel()
            val oldKey=listOf(query,before)
            model.invalidateReport(oldKey)
            val original=manager.generate(query,ReportOptions())
            assertTrue(model.publishReport(oldKey,original))
            val approvedSaveId=manager.reportId(original)
            val originalBytes=original.file.readBytes()
            val index=before.scores.indexOfFirst { it.status=="SCORED" }
            val changed=before.copy(scores=before.scores.mapIndexed { i,row -> if(i==index)row.copy(scoreMinor=if(row.scoreMinor==0L)1L else 0L) else row })
            repository.replace(PendingRestore(changed,repository.restorePreview())){}
            val after=repository.snapshot()
            assertNotEquals(before,after)
            val currentKey=listOf(query,after)
            model.invalidateReport(currentKey)
            assertNull(model.report)
            assertFalse(model.publishReport(oldKey,original))
            assertNull(model.report)
            val saved=File(context.cacheDir,"already-approved.pdf")
            assertTrue(manager.savePending(approvedSaveId,Uri.fromFile(saved)))
            assertArrayEquals(originalBytes,saved.readBytes())
            val regenerated=manager.generate(query,ReportOptions())
            assertTrue(model.publishReport(currentKey,regenerated))
            assertSame(regenerated,model.report)
            model.pending=PendingRestore(after,repository.restorePreview())
            model.baseline=repository.restorePreview()
            model.clearPreviews()
            assertNull(model.pending);assertNull(model.baseline)
        }finally{db.close();context.cleanup()}
    }
}
