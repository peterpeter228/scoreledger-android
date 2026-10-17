package com.example.scoreledger.backup

import android.content.Context
import android.net.Uri
import com.example.scoreledger.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class BackupManager(private val context:Context,private val repository:ScoreRepository) {
    private val preferences=context.getSharedPreferences("backup_status",Context.MODE_PRIVATE)
    private val rollbacks=File(context.noBackupFilesDir,"rollback")
    val lastSuccessfulBackupAt get()=preferences.getLong("last_success",0)
    suspend fun maintenance()=withContext(Dispatchers.IO){pruneRollbacks()}
    suspend fun export(uri:Uri) = withContext(Dispatchers.IO) {
        val temp=File.createTempFile("backup-",".partial",context.cacheDir)
        try {
            BackupCodec.write(repository.snapshot(),temp.outputStream())
            requireNotNull(context.contentResolver.openOutputStream(uri,"wt")) { "无法打开文件" }.use { output -> temp.inputStream().use { it.copyTo(output) } }
            // A reopened stream is optional for providers; if supported, validate the bytes actually saved.
            val verification=try { context.contentResolver.openInputStream(uri) } catch(_:java.io.FileNotFoundException){null} catch(_:SecurityException){null}
            verification?.use { BackupCodec.read(it) }
            check(preferences.edit().putLong("last_success",System.currentTimeMillis()).commit()) { "文件已写入，但无法记录备份时间" }
        } finally { temp.delete() }
    }
    suspend fun preview(uri:Uri):PendingRestore=withContext(Dispatchers.IO) {
        val data=requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupCodec.read(it) }
        PendingRestore(data,repository.restorePreview())
    }
    suspend fun restore(pending:PendingRestore)=withContext(Dispatchers.IO) { repository.replace(pending) { writeRollback(it) };runCatching { pruneRollbacks() } }
    suspend fun baseline()=withContext(Dispatchers.IO){repository.restorePreview()}
    suspend fun clearData(baseline:RestoreBaseline)=withContext(Dispatchers.IO){repository.clearData(baseline)}
    suspend fun clearInternalCopies()=withContext(Dispatchers.IO) {
        rollbacks.listFiles()?.forEach { check(it.delete()) { "部分内部快照未能删除" } }
        File(context.cacheDir,"reports").listFiles()?.forEach { check(it.delete()) { "部分临时报告未能删除" } }
    }
    private fun writeRollback(data:DataSnapshot) {
        check(rollbacks.exists() || rollbacks.mkdirs())
        val id=UUID.randomUUID().toString();val partial=File(rollbacks,"$id.partial");val completed=File(rollbacks,"${System.currentTimeMillis()}-$id.json")
        try {
            // Codec closes its stream; sync occurs through a non-closing wrapper before final close.
            FileOutputStream(partial).use { file ->
                val wrapper=object:java.io.FilterOutputStream(file){override fun close(){flush()}}
                BackupCodec.write(data,wrapper);file.fd.sync()
            }
            check(partial.renameTo(completed)) { "内部回滚快照未完成" }
            pruneRollbacks()
        } finally { partial.delete() }
    }
    private fun pruneRollbacks() {
        val now=System.currentTimeMillis()
        rollbacks.listFiles()?.filter{it.extension=="json"}?.sortedByDescending{it.lastModified()}?.forEachIndexed { i,file -> if(i>=3 || now-file.lastModified()>7L*24*60*60*1000)check(file.delete()) { "无法清理过期内部快照" } }
        rollbacks.listFiles()?.filter{it.extension=="partial" && now-it.lastModified()>24L*60*60*1000}?.forEach{it.delete()}
    }
}
