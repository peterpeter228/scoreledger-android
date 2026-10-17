package com.example.scoreledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.data.*
import com.example.scoreledger.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID

/** Device font metrics and native renderer are intentionally exercised, not JVM layout stubs. */
@RunWith(AndroidJUnit4::class)
class PdfCompactLayoutTest {
    private fun fixture()=InstrumentationRegistry.getInstrumentation().context.assets.open("backup-valid.json").use { BackupCodec.read(it) }
    private fun id(value:String)=UUID.nameUUIDFromBytes(value.toByteArray()).toString()

    @Test fun nineRowsFitOneBasicA4PageWithReadableHierarchyAndRealColumns() {
        val data=fixture()
        val plan=PdfReport.plan(data,RecordQuery(data.children.single().id),ReportOptions(),Instant.EPOCH)
        assertEquals(9,plan.scoreRows)
        assertEquals("Nine sample score rows must fit one basic A4 page",1,plan.pages.size)
        val blocks=plan.pages.single().blocks
        assertEquals(20f,blocks.first().fontSize)
        assertTrue(blocks.any { it.fontSize==14f && it.bold })
        val rows=blocks.filter { it.sourceId!=null }
        assertEquals(9,rows.size)
        assertTrue(rows.all { it.fontSize==11f && it.cells?.size==4 })
        assertTrue(rows.none { it.text.contains("2026-") })
        assertEquals(3,blocks.count { it.cells!=null && it.bold })
        assertEquals(9,plan.sourceIds.toSet().size)
        assertTrue(blocks.zipWithNext().all { (a,b)->a.top+a.height<=b.top })
        ByteArrayOutputStream().use { PdfReport.write(plan,it);assertTrue(it.size()>0) }
    }

    @Test fun selectedExamIdsIntersectScopeEvenForSameDateAndType() {
        val original=fixture();val source=original.exams.first()
        val exams=(0..2).map { source.copy(id=id("exam-$it"),title="同日同名",createdAt=it.toLong()) }
        val scores=exams.flatMap { exam -> original.scores.filter{it.examId==source.id}.map{it.copy(examId=exam.id)} }
        val data=original.copy(exams=exams,scores=scores)
        val selected=setOf(exams[0].id,exams[2].id)
        val plan=PdfReport.plan(data,RecordQuery(source.childId),ReportOptions(full=true,examIds=selected))
        assertEquals(6,plan.scoreRows)
        assertEquals(scores.filter { it.examId in selected }.map{it.examId+":"+it.subjectId}.toSet(),plan.sourceIds.toSet())
        val blocks=plan.pages.flatMap{it.blocks}
        val points=blocks.single { it.trend!=null }.trend!!
        assertEquals(listOf(exams[0].id,exams[2].id),points.map{it.record.id})
        points.forEachIndexed { index,point ->
            val mapped="${index+1} · ${point.record.date} · ${point.record.title}"
            assertTrue("Trend point must map to a dated detail group",blocks.count { it.text==mapped }>=2)
        }
        try {
            PdfReport.plan(data,RecordQuery(source.childId,from="2099-01-01"),ReportOptions(examIds=selected))
            fail("Exam IDs must not bypass the query")
        } catch(_:IllegalArgumentException) { }
        try {
            PdfReport.plan(data,RecordQuery(source.childId),ReportOptions(examIds=emptySet()))
            fail("Empty selection must not silently include all exams")
        } catch(_:IllegalArgumentException) { }
    }

    @Test fun continuationRepeatsExamAndColumnsWithoutDuplicatingSourcesOrLosingNotes() {
        val original=fixture();val source=original.exams.first()
        val exam=source.copy(title="中文长标题".repeat(10),note="中\n".repeat(249)+"中")
        val scores=(0 until 30).map { i -> original.scores.first().copy(examId=exam.id,subjectId=id("subject-$i"),subjectNameSnapshot="很长的中文科目名称".repeat(3)) }
        val plan=PdfReport.plan(original.copy(exams=listOf(exam),scores=scores),RecordQuery(exam.childId),ReportOptions(includeNotes=true))
        assertTrue(plan.pages.size>1)
        plan.pages.drop(1).forEach { page ->
            assertTrue(page.blocks.first().text.endsWith("（续）"))
            assertEquals(14f,page.blocks.first().fontSize)
            assertEquals(listOf("科目","分数 / 满分","得分率","状态"),page.blocks[1].cells)
        }
        assertEquals(scores.size,plan.sourceIds.size)
        assertEquals(scores.size,plan.sourceIds.toSet().size)
        val blocks=plan.pages.flatMap{it.blocks}
        val noteGroup=blocks.first{it.text.startsWith("原记录备注：")}.textGroup
        assertEquals("原记录备注："+exam.note,blocks.filter{it.textGroup==noteGroup}.joinToString(""){it.text})
        assertTrue(blocks.all { it.top>=PdfReport.TOP && it.top+it.height<=PdfReport.BOTTOM })
    }

    @Test fun measured199And200PagesRemainCompleteAnd201IsRejectedBeforeWriting() {
        val original=fixture();val source=original.exams.first()
        val exams=(0 until 400).map { source.copy(id=id("boundary-exam-$it"),title="边界考试",createdAt=it.toLong()) }
        val subjects=(0 until 20).map { original.subjects.first().copy(id=id("boundary-subject-$it"),name="科目$it",sortOrder=it) }
        val scores=exams.flatMap { e -> subjects.map { s -> original.scores.first().copy(examId=e.id,subjectId=s.id,subjectNameSnapshot=s.name) } }
        val data=original.copy(subjects=subjects,exams=exams,scores=scores)
        fun plan(count:Int)=PdfReport.plan(data,RecordQuery(source.childId),ReportOptions(examIds=exams.take(count).map{it.id}.toSet()),Instant.EPOCH)
        fun countPages(count:Int)=try { plan(count).pages.size } catch(_:ReportPageLimitException) { 201 }
        for(target in listOf(199,200)) {
            var low=1;var high=exams.size
            while(low<high) { val middle=(low+high)/2;if(countPages(middle)>=target)high=middle else low=middle+1 }
            val result=plan(low)
            assertEquals(target,result.pages.size)
            assertEquals(low*20,result.sourceIds.size)
            assertEquals(low*20,result.sourceIds.toSet().size)
            ByteArrayOutputStream().use { PdfReport.write(result,it);assertTrue(it.size()>0) }
            if(target==200) {
                var next=low+1
                while(countPages(next)==200)next++
                try { plan(next);fail("201st measured page must fail, never truncate") }
                catch(_:ReportPageLimitException) { }
            }
        }
        val page=ReportPage(listOf(ReportBlock("边界页",40f,20f)))
        ByteArrayOutputStream().use { output ->
            try { PdfReport.write(ReportPlan(List(201){page},Instant.EPOCH,1),output);fail("Direct write must reject before bytes") }
            catch(_:ReportPageLimitException) { assertEquals(0,output.size()) }
        }
    }
}
