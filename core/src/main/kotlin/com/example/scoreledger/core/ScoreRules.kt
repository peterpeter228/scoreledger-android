package com.example.scoreledger.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate

/** Original business rules; no Android, network or persistence dependencies. */
enum class ScoreStatus { SCORED, ABSENT, MISSING }
enum class ExamKind { UNIT, MONTHLY, MIDTERM, FINAL, OTHER }

data class ScoreValue(
    val subjectId: String,
    val status: ScoreStatus,
    val scoreMinor: Long?,
    val maxMinor: Long
)
data class ExamContext(
    val childId: String,
    val term: String,
    val gradeSnapshot: String,
    val kind: ExamKind
)
data class ScoreSummary(
    val totalScoreMinor: Long,
    val totalMaxMinor: Long,
    val recordedCount: Int,
    val selectedCount: Int,
    val ratePercent: BigDecimal?
) { val isComplete: Boolean get() = selectedCount > 0 && recordedCount == selectedCount }

data class Comparison(val comparable: Boolean, val reason: String?)

object ScoreRules {
    const val MAX_MINOR: Long = 1_000_000L // 10,000.00 points.
    private val plainNumber = Regex("^[0-9]{1,5}(\\.[0-9]{1,2})?$")

    /** Never silently round invalid input, including more than two fractional digits. */
    fun parsePoints(raw: String): Long {
        val normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC)
        require(plainNumber.matches(normalized)) { "请输入不超过两位小数的非负分数" }
        val value = BigDecimal(normalized).movePointRight(2).longValueExact()
        require(value in 0..MAX_MINOR) { "分数超出支持范围" }
        return value
    }

    fun formatPoints(minor: Long): String {
        require(minor >= 0) { "分数不能为负" }
        return BigDecimal.valueOf(minor, 2).stripTrailingZeros().toPlainString()
    }

    fun validate(score: ScoreValue) {
        require(score.subjectId.isNotBlank()) { "科目不能为空" }
        require(score.maxMinor in 1..MAX_MINOR) { "满分必须大于零且不超过10000" }
        when (score.status) {
            ScoreStatus.SCORED -> {
                val n = requireNotNull(score.scoreMinor) { "有分数状态必须填写分数" }
                require(n in 0..score.maxMinor) { "得分必须介于零和满分之间" }
            }
            ScoreStatus.ABSENT, ScoreStatus.MISSING ->
                require(score.scoreMinor == null) { "缺考或未录入不能保存为零分" }
        }
    }

    fun validateRows(scores: List<ScoreValue>) {
        require(scores.size in 1..30) { "一次考试需要1至30个科目" }
        require(scores.map { it.subjectId }.toSet().size == scores.size) { "同一考试不能重复添加科目" }
        scores.forEach(::validate)
    }

    fun percent(score: ScoreValue): BigDecimal? {
        validate(score)
        return score.scoreMinor?.let {
            BigDecimal.valueOf(it).multiply(BigDecimal(100))
                .divide(BigDecimal.valueOf(score.maxMinor), 2, RoundingMode.HALF_UP)
        }
    }

    fun summarize(scores: List<ScoreValue>): ScoreSummary {
        validateRows(scores)
        val recorded = scores.filter { it.status == ScoreStatus.SCORED }
        val total = recorded.sumOf { requireNotNull(it.scoreMinor) }
        val maximum = recorded.sumOf { it.maxMinor }
        val rate = if (recorded.isEmpty()) null else BigDecimal.valueOf(total)
            .multiply(BigDecimal(100)).divide(BigDecimal.valueOf(maximum), 2, RoundingMode.HALF_UP)
        return ScoreSummary(total, maximum, recorded.size, scores.size, rate)
    }

    /** Weighted summary of scoped exams; each exam retains the 1..30 row validation. */
    fun summarizeGroups(groups: List<List<ScoreValue>>): ScoreSummary {
        val summaries=groups.map(::summarize)
        val total=summaries.sumOf{it.totalScoreMinor};val maximum=summaries.sumOf{it.totalMaxMinor}
        val recorded=summaries.sumOf{it.recordedCount};val selected=summaries.sumOf{it.selectedCount}
        val rate=if(recorded==0)null else BigDecimal.valueOf(total).multiply(BigDecimal(100))
            .divide(BigDecimal.valueOf(maximum),2,RoundingMode.HALF_UP)
        return ScoreSummary(total,maximum,recorded,selected,rate)
    }

    /** Difference of unrounded rational values, rounded only for display. */
    fun percentagePointChange(previous: ScoreValue, current: ScoreValue): BigDecimal? {
        validate(previous); validate(current)
        require(previous.subjectId == current.subjectId) { "不能跨科目比较" }
        val old = previous.scoreMinor ?: return null
        val new = current.scoreMinor ?: return null
        val numerator = BigDecimal.valueOf(new).multiply(BigDecimal.valueOf(previous.maxMinor))
            .subtract(BigDecimal.valueOf(old).multiply(BigDecimal.valueOf(current.maxMinor)))
            .multiply(BigDecimal(100))
        val denominator = BigDecimal.valueOf(current.maxMinor).multiply(BigDecimal.valueOf(previous.maxMinor))
        return numerator.divide(denominator, 2, RoundingMode.HALF_UP)
    }

    fun canCompareTotals(
        previous: ExamContext, before: List<ScoreValue>,
        current: ExamContext, after: List<ScoreValue>
    ): Comparison {
        validateRows(before); validateRows(after)
        if (previous.childId != current.childId) return Comparison(false, "不同孩子")
        if (previous.term != current.term || previous.gradeSnapshot != current.gradeSnapshot ||
            previous.kind != current.kind) return Comparison(false, "考试范围不同")
        if (before.any { it.status != ScoreStatus.SCORED } || after.any { it.status != ScoreStatus.SCORED })
            return Comparison(false, "存在缺考或未录入")
        if (before.map { it.subjectId }.toSet() != after.map { it.subjectId }.toSet())
            return Comparison(false, "科目集合不同")
        if (before.associate { it.subjectId to it.maxMinor } != after.associate { it.subjectId to it.maxMinor })
            return Comparison(false, "满分不同")
        return Comparison(true, null)
    }

    fun validateExamDate(value: String, today: LocalDate): LocalDate {
        require(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(value)) { "日期格式应为YYYY-MM-DD" }
        val date = LocalDate.parse(value)
        require(date.year >= 1) { "日期年份必须大于零" }
        require(!date.isAfter(today)) { "不能记录未来的考试日期" }
        return date
    }

    /** Exact-text import validation; never normalizes historical snapshots. */
    fun validateExactText(value: String, maximum: Int, allowEmpty: Boolean = false, note: Boolean = false) {
        var i=0
        while(i<value.length) {
            val c=value[i]
            if(Character.isHighSurrogate(c)) { require(i+1<value.length && Character.isLowSurrogate(value[i+1])) { "包含无效字符" };i+=2 }
            else { require(!Character.isLowSurrogate(c)) { "包含无效字符" };i++ }
        }
        require(value.codePointCount(0,value.length)<=maximum) { "内容过长" }
        require(allowEmpty || value.any { !it.isWhitespace() && !Character.isSpaceChar(it) && it!='\u0085' }) { "内容不能为空" }
        require(value.none { it.code<32 && !(note && (it=='\n' || it=='\t')) }) { "包含不支持的控制字符" }
    }
    fun validateText(value: String, maxCodePoints: Int, allowEmpty: Boolean = false): String {
        validateExactText(value, maxCodePoints, allowEmpty)
        val text = value.trim()
        require(allowEmpty || text.isNotEmpty()) { "内容不能为空" }
        require(text.codePointCount(0, text.length) <= maxCodePoints) { "内容过长" }
        require(text.none { it.code < 32 }) { "包含不支持的控制字符" }
        return text
    }
}

/** Deliberately not a DRM system: deleting records releases the free quota. */
object TrialPolicy {
    const val FREE_EXAMS = 3
    const val FREE_ACTIVE_CHILDREN = 1
    fun canAddExam(pro: Boolean, retainedExams: Int): Boolean {
        require(retainedExams >= 0)
        return pro || retainedExams < FREE_EXAMS
    }
    fun canAddChild(pro: Boolean, activeChildren: Int): Boolean {
        require(activeChildren >= 0)
        return pro || activeChildren < FREE_ACTIVE_CHILDREN
    }
    fun canAccessExistingData(): Boolean = true
    fun canExportBasic(): Boolean = true
    fun canRestoreBackup(): Boolean = true
}
