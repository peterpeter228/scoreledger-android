package com.example.scoreledger.core

import java.math.BigDecimal

/** One ordered point per scoped exam, including exams where this subject was not selected. */
data class TrendRecord(val id: String, val date: String, val createdAt: Long, val title: String,
    val context: ExamContext, val scores: List<ScoreValue>)
data class TrendPoint(val record: TrendRecord, val value: ScoreValue?, val rate: BigDecimal?)
object TrendRules {
    fun points(records: List<TrendRecord>, childId: String, subjectId: String): List<TrendPoint> =
        records.filter { it.context.childId == childId }
            .sortedWith(compareBy<TrendRecord> { it.date }.thenBy { it.createdAt }.thenBy { it.id })
            .map { record ->
                ScoreRules.validateRows(record.scores)
                val value = record.scores.singleOrNull { it.subjectId == subjectId }
                TrendPoint(record, value, value?.let(ScoreRules::percent))
            }
    fun connects(before: TrendPoint, after: TrendPoint): Boolean = before.rate != null && after.rate != null &&
        before.record.context == after.record.context
    fun change(before: TrendPoint, after: TrendPoint): BigDecimal? =
        if (connects(before, after)) ScoreRules.percentagePointChange(requireNotNull(before.value), requireNotNull(after.value)) else null
}
