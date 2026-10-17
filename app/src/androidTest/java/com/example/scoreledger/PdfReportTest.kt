package com.example.scoreledger

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.backup.BackupCodec
import com.example.scoreledger.data.*
import com.example.scoreledger.export.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PdfReportTest {
    private fun fixture()=InstrumentationRegistry.getInstrumentation().context.assets.open("backup-valid.json").use{BackupCodec.read(it)}
    @Test fun paginationContainsEachSourceExactlyOnceAndHidesFields() {
        val original=fixture();val child=original.children.single().copy(nickname="隐私昵称不应出现")
        val exams=(0 until 50).map{i->original.exams[i%3].copy(id=UUID.randomUUID().toString(),title="长中文标题".repeat(10),note="私密备注".repeat(100),createdAt=i.toLong(),updatedAt=i.toLong())}
        val scores=exams.flatMapIndexed{i,e->original.scores.filter{it.examId==original.exams[i%3].id}.map{it.copy(examId=e.id)}}
        val data=DataSnapshot(listOf(child),original.subjects,exams,scores)
        val plan=PdfReport.plan(data,RecordQuery(child.id),ReportOptions(anonymous=true,includeNotes=false,full=true),Instant.parse("2026-10-17T00:00:00Z"))
        assertTrue(plan.pages.size>1);assertEquals(scores.size,plan.sourceIds.size)
        assertEquals(scores.map{it.examId+":"+it.subjectId}.toSet(),plan.sourceIds.toSet())
        assertTrue(plan.pages.all{it.blocks.all{b->b.top>=40 && b.top+b.height<=780}})
        val text=plan.pages.flatMap{it.blocks}.joinToString{it.text}
        assertFalse(text.contains(child.nickname));assertFalse(text.contains("私密备注"));assertTrue(text.contains("缺考"));assertTrue(text.contains("未录入"));assertTrue(text.contains("0 / 100"));assertTrue(text.contains("90 / 120"))
        val noted=PdfReport.plan(data,RecordQuery(child.id),ReportOptions(anonymous=false,includeNotes=true))
        assertTrue(noted.pages.flatMap{it.blocks}.any{it.text.contains(child.nickname)})
        assertTrue(noted.pages.flatMap{it.blocks}.any{it.text.contains("私密备注")})
        assertEquals(scores.size,noted.sourceIds.size)
    }
    @Test fun rendererOpensNativeA4PdfAndProducesScreenshotEvidence() {
        val data=fixture();val plan=PdfReport.plan(data,RecordQuery(data.children.single().id),ReportOptions(full=true))
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val evidence=File(context.filesDir,"pdf-evidence").apply{mkdirs()}
        val file=File(evidence,"synthetic-report.pdf")
        file.outputStream().use{PdfReport.write(plan,it)}
        PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer ->
            assertEquals(plan.pages.size,renderer.pageCount)
            renderer.openPage(0).use{page ->
                assertEquals(595,page.width);assertEquals(842,page.height)
                val bitmap=Bitmap.createBitmap(1190,1684,Bitmap.Config.ARGB_8888)
                try {
                    // PdfRenderer preserves untouched pixels; production preview also initializes white.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(evidence,"synthetic-report-first-page.png").outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                    assertEquals(android.graphics.Color.WHITE,bitmap.getPixel(10,10))
                    val pixels=IntArray(bitmap.width*bitmap.height)
                    bitmap.getPixels(pixels,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                    val ink=pixels.count { color -> android.graphics.Color.alpha(color)>0 &&
                        android.graphics.Color.red(color)<180 && android.graphics.Color.green(color)<180 && android.graphics.Color.blue(color)<180 }
                    assertTrue("Native A4 render must contain substantial visible text/lines, not a blank white page: $ink",ink>1000)
                }finally{bitmap.recycle()}
            }
        }
    }
    @Test fun emptyScopeRejectedAndSingleExamNotDuplicated() {
        val data=fixture();val child=data.children.single().id
        var failed=false;try{PdfReport.plan(data,RecordQuery(child,term="不存在"),ReportOptions())}catch(_:IllegalArgumentException){failed=true};assertTrue(failed)
        val exam=data.exams.first();val plan=PdfReport.plan(data,RecordQuery(child,from=exam.examDate,through=exam.examDate),ReportOptions())
        assertEquals(data.scores.count{it.examId==exam.id},plan.scoreRows)
        assertEquals(plan.scoreRows,plan.sourceIds.toSet().size)
    }
    @Test fun newlineRichMaximumNoteSplitsWithoutLossOrScoreDuplication() {
        val original=fixture();val exam=original.exams.first().copy(note="中\n".repeat(249)+"中")
        val scores=original.scores.filter{it.examId==exam.id}
        val data=original.copy(exams=listOf(exam),scores=scores)
        val plan=PdfReport.plan(data,RecordQuery(exam.childId),ReportOptions(includeNotes=true))
        assertTrue(plan.pages.size>3)
        val blocks=plan.pages.flatMap{it.blocks}
        val noteGroup=blocks.first{it.text.startsWith("原记录备注：")}.textGroup
        assertEquals("原记录备注："+exam.note,blocks.filter{it.textGroup==noteGroup}.joinToString(""){it.text})
        assertTrue(blocks.all{it.top>=PdfReport.TOP && it.top+it.height<=PdfReport.BOTTOM})
        assertEquals(scores.size,plan.sourceIds.size);assertEquals(scores.size,plan.sourceIds.toSet().size)
        java.io.ByteArrayOutputStream().use{PdfReport.write(plan,it);assertTrue(it.size()>0)}
    }

    @Test fun reportPageBoundaryRejectsWithoutTruncationAndDirectWriteChecksLimit() {
        val original=fixture();val source=original.exams.first()
        val exams=(0 until 60).map { source.copy(id=UUID.randomUUID().toString(),note="中\n".repeat(249)+"中") }
        val scores=exams.flatMap { e->original.scores.filter{it.examId==source.id}.map{it.copy(examId=e.id)} }
        try {
            PdfReport.plan(original.copy(exams=exams,scores=scores),RecordQuery(source.childId),ReportOptions(includeNotes=true))
            fail("More than 200 measured pages must fail, not truncate")
        } catch(_:ReportPageLimitException) { }
        val page=ReportPage(listOf(ReportBlock("边界页",40f,20f)))
        val boundary=ReportPlan(List(200){page},Instant.EPOCH,1)
        java.io.ByteArrayOutputStream().use { output -> PdfReport.write(boundary,output);assertTrue(output.size()>0) }
        java.io.ByteArrayOutputStream().use { output ->
            try { PdfReport.write(boundary.copy(pages=List(201){page}),output);fail("Direct plan must be bounded") }
            catch(_:ReportPageLimitException) { assertEquals(0,output.size()) }
        }
    }
    @Test fun cancellationStopsPlanningAndRemovesPartialPublication() {
        val data=fixture();var planningChecks=0
        try {
            PdfReport.plan(data,RecordQuery(data.children.single().id),ReportOptions(),checkCancelled={
                if(++planningChecks==5)throw kotlinx.coroutines.CancellationException("synthetic cancellation")
            });fail("Planning must cancel")
        } catch(_:kotlinx.coroutines.CancellationException) { assertEquals(5,planningChecks) }
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=androidx.room.Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        try {
            val manager=ReportManager(isolated,ScoreRepository(db,true))
            val plan=PdfReport.plan(data,RecordQuery(data.children.single().id),ReportOptions())
            var writingChecks=0
            try {
                manager.writePrepared(plan){if(++writingChecks==4)throw kotlinx.coroutines.CancellationException("synthetic cancellation")}
                fail("Writing must cancel")
            } catch(_:kotlinx.coroutines.CancellationException) { assertEquals(4,writingChecks) }
            assertTrue(File(isolated.cacheDir,"reports").listFiles().orEmpty().isEmpty())
        } finally { db.close();isolated.cleanup() }
    }

}
