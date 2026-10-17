package com.example.scoreledger.backup

import android.content.Context
import android.net.Uri
import com.example.scoreledger.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class BackupWriteResult(val verified:Boolean)
data class RollbackCopy(val id:String,val createdAt:Long)
class BackupManager(context:Context,private val repository:ScoreRepository) {
    private val context=context.applicationContext
    private val preferences=context.getSharedPreferences("backup_status",Context.MODE_PRIVATE)
    private val rollbacks=File(context.noBackupFilesDir,"rollback")
    val lastSuccessfulBackupAt get()=preferences.getLong("last_success",0)
    suspend fun maintenance()=withContext(Dispatchers.IO){
        pruneRollbacks()
        val cutoff=System.currentTimeMillis()-24L*60*60*1000
        context.cacheDir.listFiles()?.filter{it.name.startsWith("backup-") && it.extension=="partial" && it.lastModified()<cutoff}?.forEach{it.delete()}
    }
    suspend fun export(uri:Uri):BackupWriteResult = exportSnapshot(uri,null)
    suspend fun exportRollback(id:String,uri:Uri):BackupWriteResult=exportSnapshot(uri,id)
    private suspend fun exportSnapshot(uri:Uri,id:String?):BackupWriteResult = withContext(Dispatchers.IO) {
        val temp=File.createTempFile("backup-",".partial",context.cacheDir)
        try {
            val data=if(id==null)repository.snapshot() else rollbackFile(id).inputStream().use(BackupCodec::read)
            BackupCodec.write(data,temp.outputStream())
            requireNotNull(context.contentResolver.openOutputStream(uri,"wt")) { "无法打开文件" }.use { output -> temp.inputStream().use { it.copyTo(output) } }
            // A reopened stream is optional for providers; if supported, validate the bytes actually saved.
            val verification=try { context.contentResolver.openInputStream(uri) } catch(_:java.io.FileNotFoundException){null} catch(_:SecurityException){null}
            verification?.use { actual ->
                val expected=temp.inputStream().use(::digest)
                check(java.security.MessageDigest.isEqual(expected,digest(actual))) { "目标文件与导出内容不一致" }
            }
            if(id==null)check(preferences.edit().putLong("last_success",System.currentTimeMillis()).commit()) { "文件已写入，但无法记录备份时间" }
            BackupWriteResult(verification!=null)
        } finally { temp.delete() }
    }
    suspend fun preview(uri:Uri):PendingRestore=withContext(Dispatchers.IO) {
        val data=requireNotNull(context.contentResolver.openInputStream(uri)).use { BackupCodec.read(it) }
        PendingRestore(data,repository.restorePreview())
    }
    suspend fun listRollbacks():List<RollbackCopy> = withContext(Dispatchers.IO) {
        pruneRollbacks()
        rollbacks.listFiles().orEmpty().filter{it.extension=="json"}.sortedByDescending{it.lastModified()}.map{RollbackCopy(it.name,it.lastModified())}
    }
    private fun rollbackFile(id:String):File {
        require(Regex("[0-9]+-[0-9a-f-]{36}\\.json").matches(id))
        return File(rollbacks,id).also{require(it.canonicalFile.parentFile==rollbacks.canonicalFile && it.isFile){"副本已过期或清理，请使用外部备份"}}
    }
    suspend fun previewRollback(id:String):PendingRestore=withContext(Dispatchers.IO) {
        PendingRestore(rollbackFile(id).inputStream().use(BackupCodec::read),repository.restorePreview())
    }
    suspend fun restore(pending:PendingRestore)=withContext(Dispatchers.IO) { repository.replace(pending) { writeRollback(it) } }
    suspend fun baseline()=withContext(Dispatchers.IO){repository.restorePreview()}
    suspend fun clearData(baseline:RestoreBaseline)=withContext(Dispatchers.IO){repository.clearData(baseline)}
    suspend fun clearInternalCopies()=withContext(Dispatchers.IO) {
        rollbacks.listFiles()?.forEach { check(it.delete()) { "部分内部快照未能删除" } }
        File(context.cacheDir,"reports").listFiles()?.forEach { check(it.delete()) { "部分临时报告未能删除" } }
    }
    private fun digest(input:java.io.InputStream):ByteArray {
        val sha=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(8192);var count=0L
        while(true) { val n=input.read(buffer);if(n<0)break;count+=n;require(count<=BackupCodec.MAX_BYTES);sha.update(buffer,0,n) }
        return sha.digest()
    }
    private fun writeRollback(data:DataSnapshot) {
        check(rollbacks.exists() || rollbacks.mkdirs())
        val id=UUID.randomUUID().toString();val partial=File(rollbacks,"$id.partial");val completed=File(rollbacks,"${System.currentTimeMillis()}-$id.json")
        try {
            // Codec closes its stream; sync occurs through a non-closing wrapper before final close.
            FileOutputStream(partial).use { file ->
                val wrapper=object:java.io.FilterOutputStream(file){override fun close(){flush()};override fun write(b:ByteArray,off:Int,len:Int){out.write(b,off,len)}}
                BackupCodec.write(data,wrapper);file.fd.sync()
            }
            check(partial.inputStream().use(BackupCodec::read)==data) { "内部回滚快照校验失败，未替换数据" }
            check(partial.renameTo(completed)) { "内部回滚快照未完成" }
            pruneRollbacks(completed)
            check(completed.isFile && completed.length()>0) { "内部回滚快照不可用" }
            check(rollbacks.isDirectory) { "内部快照目录不可用" }
            val descriptor=android.system.Os.open(rollbacks.absolutePath,android.system.OsConstants.O_RDONLY,0)
            try { android.system.Os.fsync(descriptor) } finally { android.system.Os.close(descriptor) }
        } finally { partial.delete() }
    }
    private fun pruneRollbacks(protectedFile:File?=null) = pruneRollbackFiles(rollbacks,System.currentTimeMillis(),protectedFile)
}
/** Clock corrections/future mtimes must never evict the new rollback before replacement. */
internal fun pruneRollbackFiles(directory:File,now:Long,protectedFile:File?=null) {
    val otherSlots=if(protectedFile==null)3 else 2
    directory.listFiles()?.filter{it.extension=="json" && it!=protectedFile}?.sortedByDescending{it.lastModified()}?.forEachIndexed { i,file ->
        if(i>=otherSlots || now-file.lastModified()>7L*24*60*60*1000)check(file.delete()) { "无法清理过期内部快照" }
    }
    directory.listFiles()?.filter{it.extension=="partial" && now-it.lastModified()>24L*60*60*1000}?.forEach{it.delete()}
}
