package com.example.scoreledger.data

import androidx.room.*

@Entity(tableName = "children")
data class ChildEntity(
    @PrimaryKey val id: String, val nickname: String, val gradeLabel: String,
    val archived: Boolean = false, val createdAt: Long, val updatedAt: Long
)
@Entity(tableName = "subjects")
data class SubjectEntity(
    @PrimaryKey val id: String, val name: String, val defaultMaxMinor: Long,
    val sortOrder: Int, val archived: Boolean = false, val createdAt: Long, val updatedAt: Long
)
@Entity(tableName = "exams", foreignKeys = [ForeignKey(
    entity = ChildEntity::class, parentColumns = ["id"], childColumns = ["childId"],
    onDelete = ForeignKey.RESTRICT
)], indices = [Index(value = ["childId", "examDate"])])
data class ExamEntity(
    @PrimaryKey val id: String, val childId: String, val title: String, val examDate: String,
    val kind: String, val term: String, val gradeSnapshot: String, val note: String,
    val createdAt: Long, val updatedAt: Long
)
@Entity(tableName = "scores", primaryKeys = ["examId", "subjectId"], foreignKeys = [
    ForeignKey(entity = ExamEntity::class, parentColumns = ["id"], childColumns = ["examId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = SubjectEntity::class, parentColumns = ["id"], childColumns = ["subjectId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("subjectId")])
data class ScoreEntity(
    val examId: String, val subjectId: String, val subjectNameSnapshot: String,
    val status: String, val scoreMinor: Long?, val maxMinor: Long
)
data class ExamWithScores(
    @Embedded val exam: ExamEntity,
    @Relation(parentColumn = "id", entityColumn = "examId") val scores: List<ScoreEntity>
)

@Entity(tableName = "drafts", foreignKeys = [ForeignKey(
    entity = ChildEntity::class, parentColumns = ["id"], childColumns = ["childId"],
    onDelete = ForeignKey.RESTRICT
)], indices = [Index("childId")])
data class DraftEntity(@PrimaryKey val id: String, val childId: String, val payloadJson: String, val updatedAt: Long)

data class ChildExamCount(val childId: String, val examCount: Int)
