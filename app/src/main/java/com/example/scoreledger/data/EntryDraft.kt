package com.example.scoreledger.data

import com.example.scoreledger.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

data class DraftRow(val subjectId: String, val name: String, val score: String = "", val maximum: String = "100", val status: ScoreStatus = ScoreStatus.MISSING) {
    fun value() = ScoreValue(subjectId,status,if(status == ScoreStatus.SCORED) ScoreRules.parsePoints(score) else null,ScoreRules.parsePoints(maximum)).also(ScoreRules::validate)
    fun error(): String? = try { value(); null } catch (e: IllegalArgumentException) { e.message }
}
data class EntryDraft(val id: String = UUID.randomUUID().toString(), val childId: String, val editing: Boolean = false,
    val title: String = "", val date: String = LocalDate.now().toString(), val kind: ExamKind = ExamKind.OTHER,
    val term: String = "", val note: String = "", val rows: List<DraftRow> = emptyList(), val gradeSnapshot: String? = null) {
    fun input() = ExamInput(id,childId,title,date,kind,term,note,rows.map { it.value() },gradeSnapshot)
    fun encode(): String = JSONObject().put("version",1).put("id",id).put("childId",childId).put("editing",editing)
        .put("title",title).put("date",date).put("kind",kind.name).put("term",term).put("note",note)
        .apply { gradeSnapshot?.let { put("gradeSnapshot",it) } }
        .put("rows",JSONArray().apply { rows.forEach { r -> put(JSONObject().put("subjectId",r.subjectId).put("name",r.name)
            .put("score",r.score).put("maximum",r.maximum).put("status",r.status.name)) } }).toString()
    companion object {
        fun decode(entity: DraftEntity): EntryDraft {
            val o = JSONObject(entity.payloadJson)
            require(o.getInt("version") == 1 && o.getString("id") == entity.id && o.getString("childId") == entity.childId) { "草稿格式不受支持" }
            val a = o.getJSONArray("rows")
            return EntryDraft(entity.id,entity.childId,o.getBoolean("editing"),o.getString("title"),o.getString("date"),
                ExamKind.valueOf(o.getString("kind")),o.getString("term"),o.getString("note"),List(a.length()) { i ->
                    val r=a.getJSONObject(i);DraftRow(r.getString("subjectId"),r.getString("name"),r.getString("score"),r.getString("maximum"),ScoreStatus.valueOf(r.getString("status"))) },
                if(o.has("gradeSnapshot")) o.getString("gradeSnapshot") else null)
        }
        fun from(record: ExamWithScores) = EntryDraft(record.exam.id,record.exam.childId,true,record.exam.title,record.exam.examDate,
            ExamKind.valueOf(record.exam.kind),record.exam.term,record.exam.note,record.scores.map { DraftRow(it.subjectId,it.subjectNameSnapshot,
                it.scoreMinor?.let(ScoreRules::formatPoints).orEmpty(),ScoreRules.formatPoints(it.maxMinor),ScoreStatus.valueOf(it.status)) },record.exam.gradeSnapshot)
    }
}

/** One scope shared by history and upcoming trend/PDF consumers; never includes another child. */
data class RecordQuery(val childId: String, val from: String = "", val through: String = "", val term: String = "", val kind: ExamKind? = null, val titleSearch: String = "") {
    fun apply(records: List<ExamWithScores>): List<ExamWithScores> {
        ScoreRules.validateText(titleSearch,60,true)
        ScoreRules.validateText(term,40,true)
        listOf(from,through).filter { it.isNotBlank() }.forEach {
            require(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$").matches(it)) { "日期格式应为YYYY-MM-DD" }
            require(LocalDate.parse(it).year >= 1) { "日期年份必须大于零" }
        }
        require(from.isBlank() || through.isBlank() || from <= through) { "开始日期不能晚于结束日期" }
        return records.filter { val e=it.exam; e.childId == childId && (from.isBlank() || e.examDate >= from) &&
            (through.isBlank() || e.examDate <= through) && (term.isBlank() || e.term == term) && (kind == null || e.kind == kind.name) && (titleSearch.isBlank() || e.title.contains(titleSearch.trim(),ignoreCase=true)) }
            .sortedWith(compareByDescending<ExamWithScores> { it.exam.examDate }.thenByDescending { it.exam.createdAt }.thenByDescending { it.exam.id })
    }
}
