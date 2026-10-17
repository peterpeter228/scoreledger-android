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
        val evidence=File(context.getExternalFilesDir(null),"pdf-evidence").apply{mkdirs()}
        val file=File(evidence,"synthetic-report.pdf")
        file.outputStream().use{PdfReport.write(plan,it)}
        PdfRenderer(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer ->
            assertEquals(plan.pages.size,renderer.pageCount)
            renderer.openPage(0).use{page ->
                assertEquals(595,page.width);assertEquals(842,page.height)
                val bitmap=Bitmap.createBitmap(1190,1684,Bitmap.Config.ARGB_8888)
                try {page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    File(evidence,"synthetic-report-first-page.png").outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
                    assertTrue(bitmap.getPixel(100,100)!=android.graphics.Color.TRANSPARENT)
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
}
