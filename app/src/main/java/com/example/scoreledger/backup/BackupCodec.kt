package com.example.scoreledger.backup

import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.*
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class BackupFailure { INVALID, VERSION, TOO_LARGE, ACCESS, ROLLBACK }
class BackupException(val reason:BackupFailure=BackupFailure.INVALID) : IOException(when(reason) {
    BackupFailure.INVALID->"备份格式无效或损坏，请重新导出完整备份"
    BackupFailure.VERSION->"不支持此备份版本，请使用版本1备份"
    BackupFailure.TOO_LARGE->"备份超过96MiB上限，请选择受支持的备份"
    BackupFailure.ROLLBACK->"无法完成恢复前副本，未替换原记录。请检查本机存储空间；先导出备份后重试。"
    BackupFailure.ACCESS->"无法完整读取文件，请检查提供方访问权限后重试"
})
/** Streaming v1 only. Never reflects entities, creates a JSON DOM, or logs input diagnostics. */
object BackupCodec {
    const val MAX_BYTES=96L*1024*1024
    const val TOKEN_CHARS=16*1024
    fun read(input: InputStream): DataSnapshot {
        try {
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val budget=TokenBudgetReader(InputStreamReader(LimitedInput(input),decoder))
            JsonReader(budget).use { json ->
                json.setStrictness(Strictness.STRICT);json.setNestingLimit(3)
                val r=Fields(json,budget)
                var children:List<ChildEntity>?=null;var subjects:List<SubjectEntity>?=null;var exams:List<ExamEntity>?=null;var scores:List<ScoreEntity>?=null
                r.obj(setOf("format","schemaVersion","exportedAt","children","subjects","exams","scores")) { key -> when(key) {
                    "format" -> require(r.text()=="scoreledger-backup")
                    "schemaVersion" -> if(r.number()!=1L)throw BackupException(BackupFailure.VERSION)
                    "exportedAt" -> { val stamp=r.text();require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,3})?Z").matches(stamp));Instant.parse(stamp);require(stamp.take(4)!="0000") }
                    "children" -> children=r.array(20){r.child()}
                    "subjects" -> subjects=r.array(100){r.subject()}
                    "exams" -> exams=r.array(10000){r.exam()}
                    "scores" -> scores=r.array(100000){r.score()}
                } }
                r.endDocument()
                return DataSnapshot(requireNotNull(children),requireNotNull(subjects),requireNotNull(exams),requireNotNull(scores)).also(::validate)
            }
        } catch(e: BackupException) { throw e } catch(e: java.nio.charset.CharacterCodingException) { throw BackupException() } catch(e: com.google.gson.stream.MalformedJsonException) { throw BackupException() } catch(e: EOFException) { throw BackupException() } catch(e: IOException) { throw BackupException(BackupFailure.ACCESS) } catch(e: Exception) { throw BackupException() }
    }
    fun write(snapshot: DataSnapshot,output: OutputStream,exportedAt: Instant=Instant.now()) {
        validate(snapshot)
        JsonWriter(OutputStreamWriter(LimitedOutput(output),Charsets.UTF_8)).use { w ->
            w.setStrictness(Strictness.STRICT);w.beginObject();w.name("format").value("scoreledger-backup");w.name("schemaVersion").value(1)
            w.name("exportedAt").value(DateTimeFormatter.ISO_INSTANT.format(exportedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)))
            fun text(k:String,v:String){w.name(k).value(v)};fun num(k:String,v:Long){w.name(k).value(v)}
            fun stamp(c:Long,u:Long){num("createdAt",c);num("updatedAt",u)}
            w.name("children").beginArray();snapshot.children.forEach { w.beginObject();text("id",it.id);text("nickname",it.nickname);text("gradeLabel",it.gradeLabel);w.name("archived").value(it.archived);stamp(it.createdAt,it.updatedAt);w.endObject() };w.endArray()
            w.name("subjects").beginArray();snapshot.subjects.forEach { w.beginObject();text("id",it.id);text("name",it.name);num("defaultMaxMinor",it.defaultMaxMinor);num("sortOrder",it.sortOrder.toLong());w.name("archived").value(it.archived);stamp(it.createdAt,it.updatedAt);w.endObject() };w.endArray()
            w.name("exams").beginArray();snapshot.exams.forEach { w.beginObject();text("id",it.id);text("childId",it.childId);text("title",it.title);text("examDate",it.examDate);text("kind",it.kind);text("term",it.term);text("gradeSnapshot",it.gradeSnapshot);text("note",it.note);stamp(it.createdAt,it.updatedAt);w.endObject() };w.endArray()
            w.name("scores").beginArray();snapshot.scores.forEach { w.beginObject();text("examId",it.examId);text("subjectId",it.subjectId);text("subjectNameSnapshot",it.subjectNameSnapshot);text("status",it.status);w.name("scoreMinor");if(it.scoreMinor==null)w.nullValue() else w.value(it.scoreMinor);num("maxMinor",it.maxMinor);w.endObject() };w.endArray();w.endObject()
        }
    }
    fun validate(s:DataSnapshot) {
        require(s.children.size<=20 && s.subjects.size<=100 && s.exams.size<=10000 && s.scores.size<=100000)
        fun id(value:String){require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value));require(UUID.fromString(value).toString()==value)}
        fun stamp(c:Long,u:Long){require(c in 0..4102444800000L && u in c..4102444800000L)}
        val children=mutableSetOf<String>();val subjects=mutableSetOf<String>();val exams=mutableSetOf<String>()
        s.children.forEach { id(it.id);require(children.add(it.id));ScoreRules.validateExactText(it.nickname,24);ScoreRules.validateExactText(it.gradeLabel,30,true);stamp(it.createdAt,it.updatedAt) }
        s.subjects.forEach { id(it.id);require(subjects.add(it.id));ScoreRules.validateExactText(it.name,30);require(it.defaultMaxMinor in 1..ScoreRules.MAX_MINOR && it.sortOrder in 0..10000);stamp(it.createdAt,it.updatedAt) }
        s.exams.forEach { id(it.id);id(it.childId);require(exams.add(it.id) && it.childId in children);ScoreRules.validateExactText(it.title,60);ScoreRules.validateExactText(it.term,40,true);ScoreRules.validateExactText(it.gradeSnapshot,30,true);ScoreRules.validateExactText(it.note,500,true,true);ExamKind.valueOf(it.kind);require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(it.examDate));require(LocalDate.parse(it.examDate).year>=1);stamp(it.createdAt,it.updatedAt) }
        val seen=mutableSetOf<Pair<String,String>>();val counts=mutableMapOf<String,Int>()
        s.scores.forEach { id(it.examId);id(it.subjectId);require(it.examId in exams && it.subjectId in subjects && seen.add(it.examId to it.subjectId));ScoreRules.validateExactText(it.subjectNameSnapshot,30);ScoreRules.validate(it.toValue());counts[it.examId]=(counts[it.examId] ?: 0)+1 }
        require(exams.all { (counts[it] ?: 0) in 1..30 })
    }
    private class Fields(val j:JsonReader,val budget:TokenBudgetReader) {
        private fun <T> step(block:()->T):T { budget.resetBudget();return block() }
        fun text(max:Int=60,required:Boolean=false,note:Boolean=false):String=step { require(j.peek()==JsonToken.STRING);j.nextString().also { ScoreRules.validateExactText(it,max,!required,note) } }
        fun number():Long=step { require(j.peek()==JsonToken.NUMBER);val raw=j.nextString();require(Regex("-?(0|[1-9][0-9]*)").matches(raw) && raw!="-0");raw.toLong().also { require(it in 0..4102444800000L) } }
        fun bool():Boolean=step { require(j.peek()==JsonToken.BOOLEAN);j.nextBoolean() }
        fun nullableNumber():Long?=step { if(j.peek()==JsonToken.NULL){j.nextNull();null}else number() }
        fun obj(expected:Set<String>,field:(String)->Unit) { step{j.beginObject()};val seen=mutableSetOf<String>();while(step{j.hasNext()}) { val name=step{j.nextName()};require(name in expected && seen.add(name));field(name) };step{j.endObject()};require(seen==expected) }
        fun <T> array(max:Int,item:()->T):List<T> { step{j.beginArray()};val result=ArrayList<T>();while(step{j.hasNext()}){require(result.size<max);result.add(item())};step{j.endArray()};return java.util.Collections.unmodifiableList(result) }
        fun endDocument(){step{require(j.peek()==JsonToken.END_DOCUMENT)}}
        private fun checkId(id:String) { require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id));require(UUID.fromString(id).toString()==id) }
        fun child():ChildEntity { var id="";var name="";var grade="";var archived=false;var c=0L;var u=0L
            obj(setOf("id","nickname","gradeLabel","archived","createdAt","updatedAt")){when(it){"id"->id=text(36,true);"nickname"->name=text(24,true);"gradeLabel"->grade=text(30);"archived"->archived=bool();"createdAt"->c=number();"updatedAt"->u=number()}}
            return ChildEntity(id,name,grade,archived,c,u).also { validate(DataSnapshot(listOf(it),emptyList(),emptyList(),emptyList())) } }
        fun subject():SubjectEntity { var id="";var name="";var maximum=0L;var order=0L;var archived=false;var c=0L;var u=0L
            obj(setOf("id","name","defaultMaxMinor","sortOrder","archived","createdAt","updatedAt")){when(it){"id"->id=text(36,true);"name"->name=text(30,true);"defaultMaxMinor"->maximum=number();"sortOrder"->order=number();"archived"->archived=bool();"createdAt"->c=number();"updatedAt"->u=number()}}
            require(order in 0..10000);return SubjectEntity(id,name,maximum,order.toInt(),archived,c,u).also { validate(DataSnapshot(emptyList(),listOf(it),emptyList(),emptyList())) } }
        fun exam():ExamEntity { var id="";var child="";var title="";var date="";var kind="";var term="";var grade="";var note="";var c=0L;var u=0L
            obj(setOf("id","childId","title","examDate","kind","term","gradeSnapshot","note","createdAt","updatedAt")){when(it){"id"->id=text(36,true);"childId"->child=text(36,true);"title"->title=text(60,true);"examDate"->date=text(10,true);"kind"->kind=text(10,true);"term"->term=text(40);"gradeSnapshot"->grade=text(30);"note"->note=text(500,false,true);"createdAt"->c=number();"updatedAt"->u=number()}}
            checkId(id);checkId(child);require(u>=c);ExamKind.valueOf(kind)
            require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date) && LocalDate.parse(date).year>=1)
            return ExamEntity(id,child,title,date,kind,term,grade,note,c,u) }
        fun score():ScoreEntity { var exam="";var subject="";var name="";var status="";var score:Long?=null;var maximum=0L
            obj(setOf("examId","subjectId","subjectNameSnapshot","status","scoreMinor","maxMinor")){when(it){"examId"->exam=text(36,true);"subjectId"->subject=text(36,true);"subjectNameSnapshot"->name=text(30,true);"status"->status=text(10,true);"scoreMinor"->score=nullableNumber();"maxMinor"->maximum=number()}}
            checkId(exam);checkId(subject)
            return ScoreEntity(exam,subject,name,status,score,maximum).also { ScoreRules.validate(it.toValue()) } }
    }
    private class LimitedInput(input:InputStream):FilterInputStream(input) {
        var count=0L
        private fun add(n:Int){if(n>0){count+=n;if(count>MAX_BYTES)throw BackupException(BackupFailure.TOO_LARGE)}}
        override fun read():Int=super.read().also{if(it>=0)add(1)}
        override fun read(b:ByteArray,off:Int,len:Int):Int=`in`.read(b,off,minOf(len,(MAX_BYTES-count+1).toInt())).also(::add)
    }
    private class LimitedOutput(output:OutputStream):FilterOutputStream(output) {
        var count=0L
        override fun write(b:Int){require(++count<=MAX_BYTES);out.write(b)}
        override fun write(b:ByteArray,off:Int,len:Int){count+=len;require(count<=MAX_BYTES);out.write(b,off,len)}
    }
    /** Budget per parser operation, not a second JSON grammar. Gson has <=1024 buffered lookahead chars.
     * A valid maximum escaped note is <=6000 chars; excessive whitespace is intentionally rejected. */
    private class TokenBudgetReader(reader:Reader):FilterReader(reader) {
        private var remaining=TOKEN_CHARS
        fun resetBudget(){remaining=TOKEN_CHARS}
        override fun read(c:CharArray,off:Int,len:Int):Int { require(remaining>0);val n=super.read(c,off,minOf(len,remaining));if(n>0)remaining-=n;return n }
        override fun read():Int { require(remaining>0);return super.read().also { if(it>=0)remaining-- } }
    }
}
