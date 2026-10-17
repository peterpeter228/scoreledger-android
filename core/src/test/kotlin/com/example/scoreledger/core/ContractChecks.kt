package com.example.scoreledger.core

import java.math.BigDecimal
import java.time.LocalDate

/** Shared by the no-dependency CLI runner and JUnit. No Android emulator is required. */
fun runContractChecks(): Int {
    var count = 0
    fun test(name: String, action: () -> Unit) {
        try { action(); count++; println("PASS $name") }
        catch (e: Throwable) { throw AssertionError("FAIL $name", e) }
    }
    fun rejects(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: IllegalArgumentException) { rejected = true }
        catch (_: java.time.DateTimeException) { rejected = true }
        check(rejected) { "Expected validation failure" }
    }
    fun equal(actual: BigDecimal?, expected: String) {
        check(actual != null && actual.compareTo(BigDecimal(expected)) == 0) { "$actual != $expected" }
    }
    fun value(id: String = "math", n: Long = 8000, m: Long = 10000) = ScoreValue(id, ScoreStatus.SCORED, n, m)
    val missing = ScoreValue("english", ScoreStatus.MISSING, null, 10000)
    val absent = ScoreValue("english", ScoreStatus.ABSENT, null, 10000)
    val context = ExamContext("child-a", "2026秋", "初一", ExamKind.MONTHLY)
    test("integer input") { check(ScoreRules.parsePoints("87") == 8700L) }
    test("decimal input") { check(ScoreRules.parsePoints("87.50") == 8750L) }
    test("fullwidth input") { check(ScoreRules.parsePoints("８７．５") == 8750L) }
    test("trim input") { check(ScoreRules.parsePoints(" 0.01 ") == 1L) }
    test("zero input") { check(ScoreRules.parsePoints("0") == 0L) }
    test("upper bound") { check(ScoreRules.parsePoints("10000") == 1_000_000L) }
    test("reject negative") { rejects { ScoreRules.parsePoints("-1") } }
    test("reject excessive decimals") { rejects { ScoreRules.parsePoints("80.001") } }
    test("reject scientific notation") { rejects { ScoreRules.parsePoints("1e2") } }
    test("reject comma") { rejects { ScoreRules.parsePoints("1,000") } }
    test("reject NaN") { rejects { ScoreRules.parsePoints("NaN") } }
    test("reject empty") { rejects { ScoreRules.parsePoints("") } }
    test("reject over limit") { rejects { ScoreRules.parsePoints("10000.01") } }
    test("format removes zeros") { check(ScoreRules.formatPoints(8750) == "87.5") }
    test("format preserves hundredths") { check(ScoreRules.formatPoints(1) == "0.01") }
    test("reject zero denominator") { rejects { ScoreRules.validate(value(m = 0)) } }
    test("reject negative maximum") { rejects { ScoreRules.validate(value(m = -1)) } }
    test("reject bonus above maximum") { rejects { ScoreRules.validate(value(n = 10001)) } }
    test("reject absent encoded zero") { rejects { ScoreRules.validate(absent.copy(scoreMinor = 0)) } }
    test("reject missing encoded zero") { rejects { ScoreRules.validate(missing.copy(scoreMinor = 0)) } }
    test("reject scored null") { rejects { ScoreRules.validate(value().copy(scoreMinor = null)) } }
    test("reject no subject") { rejects { ScoreRules.validate(value(id = "")) } }
    test("zero is valid score") { equal(ScoreRules.percent(value(n = 0)), "0.00") }
    test("absent is null") { check(ScoreRules.percent(absent) == null) }
    test("missing is null") { check(ScoreRules.percent(missing) == null) }
    test("percent rounding") { equal(ScoreRules.percent(value(n = 1, m = 3)), "33.33") }
    test("weighted summary") {
        val s = ScoreRules.summarize(listOf(value("chinese",8000,10000),value("math",9000,15000)))
        equal(s.ratePercent,"68.00"); check(s.isComplete && s.totalScoreMinor == 17000L)
    }
    test("missing excluded but coverage visible") {
        val s = ScoreRules.summarize(listOf(value(), missing))
        equal(s.ratePercent,"80.00"); check(!s.isComplete && s.recordedCount == 1 && s.selectedCount == 2)
    }
    test("all absent gives no rate") {
        val s = ScoreRules.summarize(listOf(absent)); check(s.ratePercent == null && !s.isComplete)
    }
    test("reject no rows") { rejects { ScoreRules.summarize(emptyList()) } }
    test("reject duplicate subject") { rejects { ScoreRules.validateRows(listOf(value(), value())) } }
    test("reject more than 30 subjects") { rejects { ScoreRules.validateRows((1..31).map { value(id = "s$it") }) } }
    test("change with different maximum") { equal(ScoreRules.percentagePointChange(value(),value(n=9000,m=12000)),"-5.00") }
    test("change from missing is null") { check(ScoreRules.percentagePointChange(missing,missing.copy(status=ScoreStatus.SCORED,scoreMinor=1)) == null) }
    test("different subjects cannot compare percent") { rejects { ScoreRules.percentagePointChange(value(),value(id="other")) } }
    test("equal comparison scopes") { check(ScoreRules.canCompareTotals(context,listOf(value()),context,listOf(value(n=9000))).comparable) }
    test("cross child comparison denied") { check(!ScoreRules.canCompareTotals(context,listOf(value()),context.copy(childId="b"),listOf(value())).comparable) }
    test("cross term comparison denied") { check(!ScoreRules.canCompareTotals(context,listOf(value()),context.copy(term="2027春"),listOf(value())).comparable) }
    test("different grade comparison denied") { check(!ScoreRules.canCompareTotals(context,listOf(value()),context.copy(gradeSnapshot="初二"),listOf(value())).comparable) }
    test("different kind comparison denied") { check(!ScoreRules.canCompareTotals(context,listOf(value()),context.copy(kind=ExamKind.FINAL),listOf(value())).comparable) }
    test("changed maxima comparison denied") { check(!ScoreRules.canCompareTotals(context,listOf(value()),context,listOf(value(m=15000))).comparable) }
    test("changed subject comparison denied") { check(!ScoreRules.canCompareTotals(context,listOf(value()),context,listOf(value(id="chinese"))).comparable) }
    test("incomplete comparison denied") { check(!ScoreRules.canCompareTotals(context,listOf(value(),missing),context,listOf(value(),missing)).comparable) }
    test("subject order irrelevant") {
        val a = listOf(value("a"),value("b")); check(ScoreRules.canCompareTotals(context,a,context,a.reversed()).comparable)
    }
    test("free has three retained exams") { check(TrialPolicy.canAddExam(false,2) && !TrialPolicy.canAddExam(false,3)) }
    test("restored oversized dataset stays blocked only for additions") {
        check(!TrialPolicy.canAddExam(false,100));check(TrialPolicy.canAccessExistingData() && TrialPolicy.canExportBasic() && TrialPolicy.canRestoreBackup())
    }
    test("pro permits additions") { check(TrialPolicy.canAddExam(true,100)) }
    test("deletion releases quota") { check(TrialPolicy.canAddExam(false,2)) }
    test("free active child limit") { check(TrialPolicy.canAddChild(false,0) && !TrialPolicy.canAddChild(false,1)) }
    test("reject invalid count") { rejects { TrialPolicy.canAddExam(false,-1) } }
    test("valid leap day") { check(ScoreRules.validateExamDate("2024-02-29",LocalDate.of(2026,10,17)).dayOfMonth==29) }
    test("invalid leap day") { rejects { ScoreRules.validateExamDate("2025-02-29",LocalDate.of(2026,10,17)) } }
    test("reject year zero") { rejects { ScoreRules.validateExamDate("0000-01-01",LocalDate.of(2026,10,17)) } }
    test("reject future exam") { rejects { ScoreRules.validateExamDate("2026-10-18",LocalDate.of(2026,10,17)) } }
    test("reject short date format") { rejects { ScoreRules.validateExamDate("2026-1-1",LocalDate.of(2026,10,17)) } }
    test("nickname trimmed") { check(ScoreRules.validateText(" 孩子A ",24)=="孩子A") }
    test("reject whitespace nickname") { rejects { ScoreRules.validateText("  ",24) } }
    test("reject control character") { rejects { ScoreRules.validateText("a\nb",24) } }
    test("reject long text") { rejects { ScoreRules.validateText("a".repeat(25),24) } }
    test("trend stable order and child isolation") {
        fun record(id:String,child:String="a",at:Long=1)=TrendRecord(id,"2026-01-01",at,id,context.copy(childId=child),listOf(value()))
        val points=TrendRules.points(listOf(record("z"),record("b",at=0),record("a"),record("foreign","b")),"a","math")
        check(points.map{it.record.id}==listOf("b","a","z"))
    }
    test("zero plotted missing breaks and absent subject retained") {
        val rows=listOf(value(n=0),ScoreValue("math",ScoreStatus.MISSING,null,10000),value(n=1))
        val records=rows.mapIndexed{i,v->TrendRecord("$i","2026-01-01",i.toLong(),"test",context,listOf(v))}
        val points=TrendRules.points(records,"child-a",rows.first().subjectId)
        check(points[0].rate?.signum()==0 && points[1].rate==null)
        check(!TrendRules.connects(points[0],points[1]) && !TrendRules.connects(points[1],points[2]))
        check(TrendRules.points(records,"child-a","unselected").size==3)
        check(TrendRules.points(records,"child-a","unselected").all{it.value==null})
    }
    test("trend scope change disconnects") {
        val r=TrendRecord("a","2026-01-01",0,"A",context,listOf(value()))
        val points=TrendRules.points(listOf(r,r.copy(id="b",context=context.copy(term="other"))),"child-a",value().subjectId)
        check(!TrendRules.connects(points[0],points[1]) && TrendRules.change(points[0],points[1])==null)
    }
    println("CONTRACT_CASES_PASSED=$count")
    return count
}
fun main() { runContractChecks() }
