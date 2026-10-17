package com.example.scoreledger.ui

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.scoreledger.backup.*
import com.example.scoreledger.data.*
import com.example.scoreledger.export.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

enum class FileStage { IDLE, PICKING, READING, GENERATING, WRITING, RESTORING }
/** Activity-scoped owner. No Activity, launcher, or mutable destination is captured in jobs. */
class FileOperationModel:ViewModel() {
    private var journal:android.content.SharedPreferences?=null
    private var initialized=false
    fun initialize(context:android.content.Context,key:String) {
        if(initialized)return
        initialized=true
        journal=context.applicationContext.getSharedPreferences("file_operation_$key",android.content.Context.MODE_PRIVATE)
        if(journal?.contains("active")==true){interrupted();journal?.edit()?.remove("active")?.commit()}
    }
    var stage by mutableStateOf(FileStage.IDLE); private set
    var message by mutableStateOf("")
    var requestId by mutableStateOf<String?>(null); private set
    var pending by mutableStateOf<PendingRestore?>(null)
    var baseline by mutableStateOf<RestoreBaseline?>(null)
    var copies by mutableStateOf<List<RollbackCopy>>(emptyList())
    var report by mutableStateOf<ReportFile?>(null)
    var reportKey by mutableStateOf("")
    val busy get()=stage!=FileStage.IDLE
    fun pick():String? { if(busy)return null;return UUID.randomUUID().toString().also{requestId=it;stage=FileStage.PICKING;message=""} }
    fun pickerFinished(id:String?) : Boolean {
        if(stage!=FileStage.PICKING || id!=requestId)return false
        stage=FileStage.IDLE;requestId=null;return true
    }
    fun pickerFailed(id:String?) { if(pickerFinished(id))message="无法打开系统文件选择器，请检查可用文件应用后重试。" }
    fun interrupted() { if(!busy)message="上次操作被中断，结果未确认。请检查目标文件；恢复前请重新预览，不会自动重做。" }
    fun invalidateReport(key:String) { if(reportKey!=key){reportKey=key;report=null} }
    fun run(next:FileStage,block:suspend ()->Unit) {
        if(busy)return
        val id=UUID.randomUUID().toString()
        if(journal?.edit()?.putString("active","$id:${next.name}")?.commit()==false){message="无法记录操作状态，请检查本机空间后重试。";return}
        requestId=id;stage=next;message=""
        viewModelScope.launch {
            var settled=false
            try{block();settled=true}catch(e:CancellationException){message="操作已中断，结果未确认。请核对目标文件，重新操作前先预览。";throw e}
            catch(e:Exception){pending=null;baseline=null;message=fileFailureMessage(e);settled=true}
            finally{if(requestId==id){stage=FileStage.IDLE;requestId=null;if(settled)journal?.edit()?.remove("active")?.commit()}}
        }
    }
}
internal fun fileFailureMessage(error:Exception):String = when(error) {
    is BackupException -> requireNotNull(error.message)
    is ReportPageLimitException -> requireNotNull(error.message)
    is SecurityException -> "文件访问被拒绝，请重新选择有权限的位置；原记录保持不变。"
    is java.io.FileNotFoundException -> "文件或提供方不可用，请重新选择文件或保存位置。"
    is java.io.IOException -> "读写未完成，请检查空间与文件提供方。目标可能不完整，请删除后重试。"
    else -> "操作未完成，数据不会被部分替换。请重新预览或生成后重试；目标文件可能不完整。"
}
