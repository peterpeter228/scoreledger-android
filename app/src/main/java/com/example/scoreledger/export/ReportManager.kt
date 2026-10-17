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
    suspend fun save(report:ReportFile,uri:Uri)=withContext(Dispatchers.IO) {
        require(report.file.isFile) { "报告已清理，请重新生成" }
        requireNotNull(context.contentResolver.openOutputStream(uri,"wt")).use { out -> report.file.inputStream().use { it.copyTo(out) } }
    }
    fun previewIntent(report:ReportFile):Intent {
        require(report.file.isFile) { "报告已清理，请重新生成" }
        report.file.setLastModified(System.currentTimeMillis())
        val uri=FileProvider.getUriForFile(context,BuildConfig.APPLICATION_ID+".files",report.file)
        return Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri,"application/pdf");clipData=ClipData.newUri(context.contentResolver,"成绩报告",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
    fun shareIntent(report:ReportFile):Intent {
        require(report.file.isFile) { "报告已清理，请重新生成" }
        // Refresh retention when a user shares an older prepared report; never delete immediately after sharing.
        report.file.setLastModified(System.currentTimeMillis())
        val uri=FileProvider.getUriForFile(context,BuildConfig.APPLICATION_ID+".files",report.file)
        val send=Intent(Intent.ACTION_SEND).apply {
            type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri)
            clipData=ClipData.newUri(context.contentResolver,"成绩报告",uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send,"分享报告").apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);clipData=send.clipData }
    }
    suspend fun maintenance()=withContext(Dispatchers.IO){cleanup()}
    private fun cleanup() {
        val cutoff=System.currentTimeMillis()-24L*60*60*1000
        directory.listFiles()?.filter{it.lastModified()<cutoff}?.forEach{check(it.delete()){ "无法清理过期报告" }}
    }
}
