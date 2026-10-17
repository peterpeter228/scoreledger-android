package com.example.scoreledger

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID
import com.example.scoreledger.backup.BackupManager
import com.example.scoreledger.export.ReportManager
import com.example.scoreledger.data.ScoreRepository
import com.example.scoreledger.ui.UiServices

/** Synthetic test files/settings are separate from production database, reports and rollbacks. */
class IsolatedUiContext(base:Context):ContextWrapper(base) {
    private val id=UUID.randomUUID().toString()
    private val root=File(base.cacheDir,"isolated-ui-$id").apply{mkdirs()}
    override fun getCacheDir()=File(root,"cache").apply{mkdirs()}
    override fun getNoBackupFilesDir()=File(root,"no-backup").apply{mkdirs()}
    override fun getApplicationContext():Context=this
    override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("test-$id-$name",mode)
    fun services(repository:ScoreRepository)=UiServices(BackupManager(this,repository),ReportManager(this,repository))
    fun cleanup(){root.deleteRecursively();deleteSharedPreferences("test-$id-backup_status")}
}
