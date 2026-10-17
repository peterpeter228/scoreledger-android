package com.example.scoreledger.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.scoreledger.BuildConfig
import com.example.scoreledger.data.RecordQuery
import com.example.scoreledger.data.ScoreRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class ReportFile internal constructor(internal val file:File,val pageCount:Int,val scoreRows:Int)
class ReportManager(private val context:Context,private val repository:ScoreRepository) {
    private val directory=File(context.cacheDir,"reports")
    suspend fun generate(query:RecordQuery,options:ReportOptions):ReportFile=withContext(Dispatchers.IO) {
        cleanup()
        check(directory.exists() || directory.mkdirs()) { "无法创建报告临时目录" }
        val plan=PdfReport.plan(repository.snapshot(),query,options.copy(full=options.full && BuildConfig.INTERNAL_BETA))
        val id=UUID.randomUUID().toString();val partial=File(directory,"$id.partial");val target=File(directory,"scoreledger-report-$id.pdf")
        try {
            partial.outputStream().use { PdfReport.write(plan,it) }
            check(partial.length()>0 && partial.renameTo(target)) { "报告未生成完成" }
            ReportFile(target,plan.pages.size,plan.scoreRows)
        } finally { partial.delete() }
    }
    /** Only the opaque generated ID is saved across SAF recreation, never an arbitrary path. */
    fun reportId(report:ReportFile):String = report.file.name.removePrefix("scoreledger-report-").removeSuffix(".pdf").also(::validateId)
    private fun validateId(id:String) { require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id) && UUID.fromString(id).toString()==id) }
    suspend fun savePending(id:String,uri:Uri)=withContext(Dispatchers.IO) {
        validateId(id)
        val file=File(directory,"scoreledger-report-$id.pdf")
        require(file.canonicalFile.parentFile==directory.canonicalFile && file.isFile && file.length()>0) { "报告已清理，请重新生成；目标文件尚未写入" }
        // Validate the existing file before opening/truncating the user's selected destination.
        requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { out -> file.inputStream().use{it.copyTo(out)} }
    }
    suspend fun save(report:ReportFile,uri:Uri)=withContext(Dispatchers.IO) {
        require(report.file.isFile) { "报告已清理，请重新生成" }
        requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { out -> report.file.inputStream().use { it.copyTo(out) } }
    }
    suspend fun previewIntent(report:ReportFile):Intent = withContext(Dispatchers.IO) {
        require(report.file.isFile) { "报告已清理，请重新生成" }
        report.file.setLastModified(System.currentTimeMillis())
        val uri=FileProvider.getUriForFile(context,BuildConfig.APPLICATION_ID+".files",report.file)
        Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,"application/pdf");clipData=ClipData.newUri(context.contentResolver,"成绩报告",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    suspend fun shareIntent(report:ReportFile):Intent = withContext(Dispatchers.IO) {
        require(report.file.isFile) { "报告已清理，请重新生成" }
        // Refresh retention when a user shares an older prepared report; never delete immediately after sharing.
        report.file.setLastModified(System.currentTimeMillis())
        val uri=FileProvider.getUriForFile(context,BuildConfig.APPLICATION_ID+".files",report.file)
        val send=Intent(Intent.ACTION_SEND).apply {
            type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,"成绩报告",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Intent.createChooser(send,"分享报告").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=send.clipData }
    }
    suspend fun renderPage(report:ReportFile,index:Int):RenderedReportPage=withContext(Dispatchers.IO) {
        require(report.file.isFile) { "报告已清理，请重新生成" }
        report.file.setLastModified(System.currentTimeMillis())
        android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(report.file,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
            require(index in 0 until renderer.pageCount)
            renderer.openPage(index).use { page ->
                require(page.width>0 && page.height>0)
                val scale=minOf(900f/page.width,1500f/page.height)
                val bitmap=android.graphics.Bitmap.createBitmap(maxOf(1,(page.width*scale).toInt()),maxOf(1,(page.height*scale).toInt()),android.graphics.Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    RenderedReportPage(bitmap,index,renderer.pageCount)
                } catch(error:Throwable) { bitmap.recycle();throw error }
            }
        }
    }
    suspend fun maintenance()=withContext(Dispatchers.IO){cleanup()}
    private fun cleanup() {
        val cutoff=System.currentTimeMillis()-24L*60*60*1000
        directory.listFiles()?.filter{it.lastModified()<cutoff}?.forEach{check(it.delete()){ "无法清理过期报告" }}
    }
}

data class RenderedReportPage(val bitmap:android.graphics.Bitmap,val index:Int,val pageCount:Int)
