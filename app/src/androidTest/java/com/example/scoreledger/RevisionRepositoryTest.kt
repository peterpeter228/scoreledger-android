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
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RevisionRepositoryTest {
    @Test fun recentExamDateNotLateBackfillDrivesReuseWithoutScoresOrArchivedSubjects()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
        try {
            val r=ScoreRepository(db,true);val child=r.addChild("合成复用","当前年级")
            val a=r.saveSubject(null,"合成甲",10000,0);val b=r.saveSubject(null,"合成乙",12000,1)
            val recent=ExamInput(UUID.randomUUID().toString(),child,"最近场次",LocalDate.now().minusDays(1).toString(),note="不要复制",values=listOf(ScoreValue(a,ScoreStatus.SCORED,0,15000),ScoreValue(b,ScoreStatus.ABSENT,null,12000)),gradeSnapshot="历史年级")
            r.saveExam(recent,false)
            r.saveExam(ExamInput(UUID.randomUUID().toString(),child,"后来补录的旧考试",LocalDate.now().minusDays(30).toString(),values=listOf(ScoreValue(a,ScoreStatus.SCORED,5000,8000))),false)
            r.saveSubject(b,"合成乙",12000,1,true)
            val draft=r.newDraft(child)
            assertEquals(listOf(a),draft.rows.map{it.subjectId});assertEquals("150",draft.rows.single().maximum)
            assertEquals("",draft.rows.single().score);assertEquals(ScoreStatus.MISSING,draft.rows.single().status)
            assertEquals("",draft.note);assertEquals("当前年级",draft.gradeSnapshot)
            val existing=db.dao().allExams().first{it.id==recent.id}
            assertEquals("历史年级",existing.gradeSnapshot)
        }finally{db.close()}
    }
    @Test fun explicitGradeChangesAreNotSilentlyAcceptedAsIdempotentRetryAndUndoIsExact()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
        try {
            val r=ScoreRepository(db,true);val child=r.addChild("合成年级","当前年级");val subject=r.saveSubject(null,"合成科目",10000,0)
            val input=ExamInput(UUID.randomUUID().toString(),child,"合成记录",LocalDate.now().toString(),values=listOf(ScoreValue(subject,ScoreStatus.SCORED,0,10000)),gradeSnapshot="历史年级")
            r.saveExam(input,false);r.saveExam(input,false)
            var rejected=false
            try{r.saveExam(input.copy(gradeSnapshot="错误重试年级"),false)}catch(_:IllegalStateException){rejected=true}
            assertTrue(rejected)
            val before=r.snapshot();val record=ExamWithScores(before.exams.single(),before.scores)
            val token=r.deleteForUndo(record);assertEquals(0,db.dao().examCount())
            r.undoDelete(token);assertEquals(before,r.snapshot())
            r.updateChild(child,"合成年级","新当前年级",false)
            assertEquals("历史年级",db.dao().exam(input.id)!!.gradeSnapshot)
        }finally{db.close()}
    }
}
