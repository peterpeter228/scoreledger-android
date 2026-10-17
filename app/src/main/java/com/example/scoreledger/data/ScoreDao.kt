package com.example.scoreledger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScoreDao {
    @Query("SELECT * FROM drafts ORDER BY id") suspend fun allDrafts(): List<DraftEntity>
    @Query("DELETE FROM drafts") suspend fun clearDrafts()
    @Query("DELETE FROM scores") suspend fun clearScores()
    @Query("DELETE FROM exams") suspend fun clearExams()
    @Query("DELETE FROM subjects") suspend fun clearSubjects()
    @Query("DELETE FROM children") suspend fun clearChildren()
    @Query("SELECT * FROM children ORDER BY archived, createdAt, id")
    fun observeChildren(): Flow<List<ChildEntity>>
    @Query("SELECT * FROM subjects ORDER BY sortOrder, name, id")
    fun observeSubjects(): Flow<List<SubjectEntity>>
    @Transaction
    @Query("SELECT * FROM exams WHERE childId = :childId ORDER BY examDate DESC, createdAt DESC, id DESC")
    fun observeExams(childId: String): Flow<List<ExamWithScores>>
    @Query("SELECT childId, COUNT(*) AS examCount FROM exams GROUP BY childId")
    fun observeChildCounts(): Flow<List<ChildExamCount>>
    @Query("SELECT * FROM drafts WHERE childId = :childId ORDER BY updatedAt DESC, id")
    fun observeDrafts(childId: String): Flow<List<DraftEntity>>
    @Query("SELECT * FROM drafts WHERE id = :id AND childId = :childId") suspend fun draft(id: String, childId: String): DraftEntity?
    @Query("SELECT * FROM drafts WHERE id = :id") suspend fun draftById(id: String): DraftEntity?
    @Upsert suspend fun putDraft(draft: DraftEntity)
    @Query("DELETE FROM drafts WHERE id = :id AND childId = :childId") suspend fun deleteDraft(id: String, childId: String)
    @Query("DELETE FROM drafts WHERE childId = :childId") suspend fun deleteChildDrafts(childId: String)
    @Query("SELECT * FROM children WHERE id = :id LIMIT 1") suspend fun child(id: String): ChildEntity?
    @Query("SELECT * FROM children ORDER BY createdAt, id") suspend fun children(): List<ChildEntity>
    @Query("SELECT * FROM subjects ORDER BY sortOrder, id") suspend fun subjects(): List<SubjectEntity>
    @Query("SELECT * FROM exams ORDER BY examDate, createdAt, id") suspend fun allExams(): List<ExamEntity>
    @Query("SELECT * FROM scores ORDER BY examId, subjectId") suspend fun allScores(): List<ScoreEntity>
    @Query("SELECT * FROM scores WHERE examId = :id ORDER BY subjectId") suspend fun scores(id: String): List<ScoreEntity>
    @Query("SELECT COUNT(*) FROM subjects") suspend fun subjectCount(): Int
    @Query("SELECT COUNT(*) FROM children WHERE archived = 0") suspend fun activeChildCount(): Int
    @Query("SELECT COUNT(*) FROM children") suspend fun childCount(): Int
    @Query("SELECT COUNT(*) FROM exams") suspend fun examCount(): Int
    @Query("SELECT COUNT(*) FROM scores") suspend fun scoreCount(): Int
    @Query("SELECT * FROM exams WHERE id = :id LIMIT 1") suspend fun exam(id: String): ExamEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertChild(child: ChildEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertSubjects(subjects: List<SubjectEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertExam(exam: ExamEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertScores(scores: List<ScoreEntity>)
    @Update suspend fun updateChild(child: ChildEntity): Int
    @Update suspend fun updateSubject(subject: SubjectEntity): Int
    @Update suspend fun updateExam(exam: ExamEntity): Int
    @Query("DELETE FROM scores WHERE examId = :id") suspend fun deleteScores(id: String)
    @Query("DELETE FROM exams WHERE id = :id AND childId = :childId") suspend fun deleteExam(id: String, childId: String): Int
    @Query("DELETE FROM exams WHERE childId = :childId") suspend fun deleteChildExams(childId: String)
    @Query("DELETE FROM children WHERE id = :id") suspend fun deleteChild(id: String): Int
}
