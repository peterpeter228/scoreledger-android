package com.example.scoreledger

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RepositoryIntegrityTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"),ZoneOffset.UTC)
    private suspend fun rejected(block: suspend () -> Unit) {
        var failed=false
        try { block() } catch (_: IllegalArgumentException) { failed=true } catch (_: IllegalStateException) { failed=true }
        assertTrue("Expected rejected operation",failed)
    }
    @Test fun retryIsIdempotentAndConflictingPayloadRejected() = runBlocking {
        val db=database()
        try {
            val r=ScoreRepository(db,true,clock);r.ensureDefaultSubjects()
            val a=r.addChild("测试甲");val b=r.addChild("测试乙")
            val subject=db.dao().subjects().first()
            val input=ExamInput(UUID.randomUUID().toString(),a,"月考","2026-10-15",
                values=listOf(ScoreValue(subject.id,ScoreStatus.SCORED,0,10000)))
            r.saveExam(input,false);r.saveExam(input,false)
            assertEquals(1,db.dao().examCount());assertEquals(1,db.dao().scoreCount())
            rejected { r.saveExam(input.copy(childId=b),false) }
            rejected { r.saveExam(input.copy(title="不同内容"),false) }
            assertEquals(0L,db.dao().scores(input.id).single().scoreMinor)
        } finally {db.close()}
    }
    @Test fun historySnapshotsSurviveSubjectAndChildUpdates() = runBlocking {
        val db=database()
        try {
            val r=ScoreRepository(db,true,clock);r.ensureDefaultSubjects()
            val child=r.addChild("测试甲","三年级");val s=db.dao().subjects().first()
            val input=ExamInput(UUID.randomUUID().toString(),child,"测试","2026-10-15",
                values=listOf(ScoreValue(s.id,ScoreStatus.SCORED,8000,10000)))
            r.saveExam(input,false)
            r.saveSubject(s.id,"改名科目",15000,0,true)
            r.updateChild(child,"新昵称","四年级",true)
            r.saveExam(input.copy(title="纠错"),true)
            assertEquals(s.name,db.dao().scores(input.id).single().subjectNameSnapshot)
            assertEquals(10000L,db.dao().scores(input.id).single().maxMinor)
            assertEquals("三年级",db.dao().exam(input.id)!!.gradeSnapshot)
            rejected {r.saveExam(input.copy(id=UUID.randomUUID().toString()),false)}
        } finally {db.close()}
    }
    @Test fun crossChildEditAndDeleteDoNotMutateStoredData() = runBlocking {
        val db=database()
        try {
            val r=ScoreRepository(db,true,clock);r.ensureDefaultSubjects()
            val a=r.addChild("测试甲");val b=r.addChild("测试乙");val s=db.dao().subjects().first()
            val input=ExamInput(UUID.randomUUID().toString(),a,"测试","2026-10-15",
                values=listOf(ScoreValue(s.id,ScoreStatus.ABSENT,null,10000)))
            r.saveExam(input,false);val before=r.snapshot()
            rejected {r.saveExam(input.copy(childId=b),true)}
            rejected {r.deleteExam(input.id,b)}
            assertEquals(before,r.snapshot())
            r.deleteChild(a)
            assertEquals(0,db.dao().examCount());assertEquals(0,db.dao().scoreCount())
            assertNotNull(db.dao().child(b));assertEquals(3,db.dao().subjectCount())
        } finally {db.close()}
    }
    @Test fun invalidUpdateRollsBackAllRowsAndFreeRetryWorksAtQuota() = runBlocking {
        val db=database()
        try {
            val r=ScoreRepository(db,false,clock);r.ensureDefaultSubjects()
            val a=r.addChild("测试甲");val s=db.dao().subjects().first()
            val input=ExamInput(UUID.randomUUID().toString(),a,"测试","2026-10-15",
                values=listOf(ScoreValue(s.id,ScoreStatus.SCORED,8000,10000)))
            r.saveExam(input,false)
            repeat(2){r.saveExam(input.copy(id=UUID.randomUUID().toString()),false)}
            r.saveExam(input,false)
            val before=r.snapshot()
            rejected {r.saveExam(input.copy(id=UUID.randomUUID().toString()),false)}
            rejected {r.saveExam(input.copy(values=listOf(ScoreValue(UUID.randomUUID().toString(),ScoreStatus.SCORED,8000,10000))),true)}
            assertEquals(before,r.snapshot())
        } finally {db.close()}
    }
    @Test fun scoreInsertionFailureRollsBackCreationAndEdit() = runBlocking {
        val db=database()
        try {
            val r=ScoreRepository(db,true,clock);r.ensureDefaultSubjects()
            val a=r.addChild("测试甲");val subject=db.dao().subjects().first()
            val input=ExamInput(UUID.randomUUID().toString(),a,"测试","2026-10-15",
                values=listOf(ScoreValue(subject.id,ScoreStatus.SCORED,8000,10000)))
            r.saveExam(input,false);val before=r.snapshot()
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_score BEFORE INSERT ON scores BEGIN SELECT RAISE(ABORT, 'injected'); END")
            for (editing in listOf(true,false)) {
                var failed=false
                try {r.saveExam(input.copy(id=if(editing) input.id else UUID.randomUUID().toString(),title="变化"),editing)}
                catch (_: Exception) {failed=true}
                assertTrue(failed);assertEquals(before,r.snapshot())
            }
        } finally {db.close()}
    }

}
