package com.example.scoreledger.data

import androidx.room.withTransaction
import com.example.scoreledger.core.*
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class DataSnapshot(val children: List<ChildEntity>, val subjects: List<SubjectEntity>,
    val exams: List<ExamEntity>, val scores: List<ScoreEntity>)
data class ExamInput(val id: String, val childId: String, val title: String, val date: String,
    val kind: ExamKind = ExamKind.OTHER, val term: String = "", val note: String = "",
    val values: List<ScoreValue>)

class ScoreRepository(private val db: ScoreDatabase, private val internalBeta: Boolean,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val dao = db.dao()
    private fun timestamp(): Long = clock.millis().also { require(it in 0..4102444800000L) { "设备时间超出支持范围，请检查系统日期" } }
    private fun identifier(): String = newId().also { require(UUID.fromString(it).toString() == it) { "记录标识无效" } }
    val children = dao.observeChildren()
    val subjects = dao.observeSubjects()
    fun exams(childId: String) = dao.observeExams(childId)
    suspend fun snapshot(): DataSnapshot = db.withTransaction {
        DataSnapshot(dao.children(), dao.subjects(), dao.allExams(), dao.allScores())
    }
    suspend fun ensureDefaultSubjects() = db.withTransaction {
        if (dao.subjectCount() == 0) {
            val now = timestamp()
            dao.insertSubjects(listOf("语文", "数学", "英语").mapIndexed { index, name ->
                SubjectEntity(identifier(), name, 10000, index, false, now, now)
            })
        }
    }
    suspend fun addChild(rawNickname: String, grade: String = ""): String = db.withTransaction {
        val nickname = ScoreRules.validateText(rawNickname, 24)
        val gradeLabel = ScoreRules.validateText(grade, 30, true)
        check(dao.childCount() < 20) { "已达到20个孩子的数量上限" }
        check(TrialPolicy.canAddChild(internalBeta, dao.activeChildCount())) { "免费版支持1名未归档孩子，已有数据仍可查看与导出" }
        val now = timestamp(); val id = identifier()
        dao.insertChild(ChildEntity(id, nickname, gradeLabel, false, now, now)); id
    }
    suspend fun updateChild(id: String, name: String, grade: String, archived: Boolean) = db.withTransaction {
        val old = requireNotNull(dao.child(id)) { "孩子档案不存在" }
        if (old.archived && !archived) check(TrialPolicy.canAddChild(internalBeta, dao.activeChildCount())) { "未归档孩子数量已达免费上限" }
        check(dao.updateChild(old.copy(nickname=ScoreRules.validateText(name,24),
            gradeLabel=ScoreRules.validateText(grade,30,true),archived=archived,
            updatedAt=maxOf(timestamp(),old.createdAt))) == 1)
    }
    suspend fun deleteChild(id: String) = db.withTransaction {
        requireNotNull(dao.child(id)) { "孩子档案不存在" }
        dao.deleteChildExams(id)
        check(dao.deleteChild(id) == 1)
    }
    suspend fun saveSubject(id: String?, name: String, maxMinor: Long, order: Int, archived: Boolean = false): String = db.withTransaction {
        val normalized = ScoreRules.validateText(name,30)
        require(maxMinor in 1..ScoreRules.MAX_MINOR && order in 0..10000) { "请检查满分和排序" }
        val rows = dao.subjects()
        require(rows.none { it.id != id && it.name == normalized && !it.archived && !archived }) { "已有同名科目，请使用或修改该科目" }
        val now = timestamp()
        if (id == null) {
            check(rows.size < 100) { "已达到100个科目的上限" }
            val value = SubjectEntity(identifier(),normalized,maxMinor,order,archived,now,now)
            dao.insertSubjects(listOf(value)); value.id
        } else {
            val old = requireNotNull(rows.find { it.id == id }) { "科目不存在" }
            check(dao.updateSubject(old.copy(name=normalized,defaultMaxMinor=maxMinor,sortOrder=order,
                archived=archived,updatedAt=maxOf(now,old.createdAt))) == 1); id
        }
    }
    suspend fun saveNewExam(id: String, childId: String, rawTitle: String, date: String, values: List<ScoreValue>) =
        saveExam(ExamInput(id,childId,rawTitle,date,values=values), false)

    suspend fun saveExam(input: ExamInput, editing: Boolean) {
        require(UUID.fromString(input.id).toString() == input.id) { "记录标识无效" }
        val title = ScoreRules.validateText(input.title,60)
        val term = ScoreRules.validateText(input.term,40,true)
        val note = input.note.trim()
        require(note.codePointCount(0,note.length) <= 500 && note.none { it.code < 32 && it != '\n' && it != '\t' }) { "备注过长或包含不支持的字符" }
        ScoreRules.validateExamDate(input.date,LocalDate.now(clock))
        ScoreRules.validateRows(input.values)
        db.withTransaction {
            val old = dao.exam(input.id)
            if (old != null) check(old.childId == input.childId) { "记录不属于当前孩子" }
            val prior = if (old != null) dao.scores(old.id) else emptyList()
            // Retry of an already committed creation is success only for the same owned payload.
            if (!editing && old != null) {
                val same = old.title == title && old.examDate == input.date && old.kind == input.kind.name &&
                    old.term == term && old.note == note && prior.map { it.toValue() }.toSet() == input.values.toSet()
                check(same) { "记录标识已存在且内容不同，请重新打开记录" }
                return@withTransaction
            }
            if (editing) requireNotNull(old) { "待编辑记录已不存在" }
            val child = requireNotNull(dao.child(input.childId)) { "孩子档案不存在" }
            if (!editing) {
                check(!child.archived) { "不能向已归档孩子添加新记录" }
                check(dao.examCount() < 10000) { "已达到考试数量上限" }
                check(TrialPolicy.canAddExam(internalBeta,dao.examCount())) { "免费版可保留3次考试；已有记录仍可修改与导出" }
            }
            check(dao.scoreCount() - prior.size + input.values.size <= 100000) { "已达到成绩总条数上限" }
            val subjects = dao.subjects().associateBy { it.id }
            input.values.forEach { value ->
                val subject = requireNotNull(subjects[value.subjectId]) { "科目不存在" }
                check(!subject.archived || prior.any { it.subjectId == value.subjectId }) { "不能添加已归档科目" }
            }
            val now = maxOf(timestamp(),old?.createdAt ?: 0)
            val exam = ExamEntity(input.id,input.childId,title,input.date,input.kind.name,term,
                old?.gradeSnapshot ?: child.gradeLabel,note,old?.createdAt ?: now,now)
            val scores = input.values.map { v -> ScoreEntity(input.id,v.subjectId,
                prior.find { it.subjectId == v.subjectId }?.subjectNameSnapshot ?: requireNotNull(subjects[v.subjectId]).name,
                v.status.name,v.scoreMinor,v.maxMinor) }
            if (old == null) dao.insertExam(exam) else { check(dao.updateExam(exam)==1);dao.deleteScores(old.id) }
            dao.insertScores(scores)
        }
    }
    suspend fun deleteExam(id: String, childId: String) = db.withTransaction {
        check(dao.deleteExam(id,childId)==1) { "记录不存在或不属于当前孩子" }
    }
}
fun ScoreEntity.toValue() = ScoreValue(subjectId,ScoreStatus.valueOf(status),scoreMinor,maxMinor)
