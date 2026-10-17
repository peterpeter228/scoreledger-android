package com.example.scoreledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scoreledger.backup.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.scoreledger.R
import com.example.scoreledger.ScoreLedgerApplication
import com.example.scoreledger.data.PendingRestore
import com.example.scoreledger.data.RestoreBaseline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val LocalBackupContent=staticCompositionLocalOf<@Composable ()->Unit>{error("Backup owner missing")}
@Composable fun BackupSettings() { LocalBackupContent.current() }

/** State and SAF registration live above the virtualized settings item. */
@Composable fun rememberBackupSettings(): @Composable ()->Unit {
    val manager=LocalUiServices.current.backup
    val model:FileOperationModel=viewModel(key="backup-operations")
    val operationContext=androidx.compose.ui.platform.LocalContext.current.applicationContext
    SideEffect{model.initialize(operationContext,"backup")}
    val busy=model.busy
    var message by model::message
    var pending by model::pending
    var baseline by model::baseline
    var last by remember { mutableLongStateOf(manager.lastSuccessfulBackupAt) }
    var exportWarning by remember { mutableStateOf(false) }
    var cleanup by remember { mutableStateOf(false) }
    var exportCopy by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerToken by rememberSaveable { mutableStateOf<String?>(null) }
    var operationStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit){if(operationStarted && !model.busy){model.interrupted();operationStarted=false}}
    LaunchedEffect(model.busy){if(!model.busy)operationStarted=false}
    LaunchedEffect(manager) { runCatching { manager.maintenance();model.copies=manager.listRollbacks() } }
    fun run(stage:FileStage=FileStage.READING,action:suspend ()->Unit) {
        if(model.busy)return
        operationStarted=true
        model.run(stage){try{action()}finally{operationStarted=false;last=manager.lastSuccessfulBackupAt}}
    }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val token=pickerToken;pickerToken=null
        val copy=exportCopy;exportCopy=null
        if(token==null || (!model.pickerFinished(token) && model.busy))return@rememberLauncherForActivityResult
        if(uri==null)message="已取消备份，未更新成功时间" else run(FileStage.WRITING) {
            val result=if(copy==null)manager.export(uri) else manager.exportRollback(copy,uri)
            message=if(result.verified)"备份已保存并回读校验，请妥善保管明文文件" else "备份已写入；提供方不支持回读，尚未校验。请保留另一份备份并核对文件。"
        }
    }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val token=pickerToken;pickerToken=null
        if(token==null || (!model.pickerFinished(token) && model.busy))return@rememberLauncherForActivityResult
        if(uri==null)message="已取消选择，数据未改变" else run { baseline=null;pending=manager.preview(uri) }
    }
    fun launchExport(copy:String?=null) {
        pickerToken=model.pick() ?: return;exportCopy=copy
        try{export.launch("scoreledger-backup-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.LocalDateTime.now())}.json")}
        catch(_:Exception){model.pickerFailed(pickerToken);pickerToken=null;exportCopy=null}
    }
    return {
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_title),style=MaterialTheme.typography.titleLarge)
        Text(if(last==0L) "尚无成功备份" else "最近成功备份：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(last))}")
        Button(onClick={exportWarning=true},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.backup_export))}
        OutlinedButton(onClick={pickerToken=model.pick();try{restore.launch(arrayOf("application/json","text/plain","application/octet-stream"))}catch(_:Exception){model.pickerFailed(pickerToken);pickerToken=null}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.backup_restore))}
        Text(stringResource(R.string.backup_privacy))
        Text(stringResource(R.string.backup_help))
        OutlinedButton(onClick={run{pending=null;baseline=manager.baseline()}},enabled=!busy){Text(stringResource(R.string.backup_clear))}
        TextButton(onClick={cleanup=true},enabled=!busy){Text(stringResource(R.string.backup_cleanup))}
        Text("恢复前副本",style=MaterialTheme.typography.titleMedium)
        Text("仅保留最近3份、最长7天；不含草稿。取回会先保护当前记录。卸载会删除，请及时导出。")
        if(model.copies.isEmpty())Text("暂无恢复前副本")
        model.copies.forEach { copy ->
            Text(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(copy.createdAt)))
            Row {
                TextButton(onClick={run{baseline=null;pending=manager.previewRollback(copy.id)}},enabled=!busy){Text("预览并取回")}
                TextButton(onClick={launchExport(copy.id)},enabled=!busy){Text("导出副本")}
            }
        }
        if(busy) Text(stringResource(R.string.backup_busy))
        if(message.isNotEmpty()) Text(message,Modifier.semantics { liveRegion=LiveRegionMode.Assertive })
    }
    if(exportWarning) AlertDialog(onDismissRequest={exportWarning=false},title={Text(stringResource(R.string.backup_export))},text={Text(stringResource(R.string.backup_warning))},confirmButton={TextButton(onClick={exportWarning=false;launchExport()}){Text(stringResource(R.string.backup_choose))}},dismissButton={TextButton(onClick={exportWarning=false}){Text(stringResource(R.string.ui_13))}})
    pending?.let { preview -> AlertDialog(onDismissRequest={if(!busy)pending=null},title={Text(stringResource(R.string.backup_confirm))},text={Text("将导入${preview.childCount}个孩子、${preview.examCount}次考试、${preview.scoreCount}条成绩。\n${preview.dateRange}\n当前${preview.oldExamCount}次考试将被完整替换，${preview.oldDraftCount}份草稿将丢弃。不是合并。\n先完成内部回滚快照再写入；快照不含草稿，最多保留3份/7天，可在“恢复前副本”取回或导出；卸载会丢失。请先导出当前数据。")},confirmButton={TextButton(enabled=!busy,onClick={run(FileStage.RESTORING) { manager.restore(preview);model.clearPreviews();model.copies=manager.listRollbacks();message="恢复完成，已替换数据；旧草稿已丢弃" }}){Text(stringResource(R.string.backup_replace))}},dismissButton={TextButton(enabled=!busy,onClick={pending=null}){Text(stringResource(R.string.ui_13))}}) }
    baseline?.let { before -> AlertDialog(onDismissRequest={if(!busy)baseline=null},title={Text(stringResource(R.string.backup_clear))},text={Text("删除全部${before.data.children.size}个孩子、${before.data.exams.size}次考试、${before.data.scores.size}条成绩、科目与${before.drafts.size}份草稿。不会删除外部已导出文件，也不保证擦除闪存痕迹。请先备份。")},confirmButton={TextButton(enabled=!busy,onClick={run { manager.clearData(before);model.clearPreviews();model.copies=manager.listRollbacks();message="本机记录已清理；外部备份和内部回滚快照未删除" }}){Text(stringResource(R.string.ui_64))}},dismissButton={TextButton(enabled=!busy,onClick={baseline=null}){Text(stringResource(R.string.ui_13))}}) }
    if(cleanup) AlertDialog(onDismissRequest={cleanup=false},title={Text(stringResource(R.string.backup_cleanup))},text={Text("删除全部恢复前副本后将失去内部回退机会，并删除临时PDF（正在分享的文件也可能失效），不会删除考试记录或已保存到其他位置的文件。")},confirmButton={TextButton(enabled=!busy,onClick={cleanup=false;run { manager.clearInternalCopies();model.copies=emptyList();message="内部快照和临时报告已清理" }}){Text(stringResource(R.string.ui_64))}},dismissButton={TextButton(onClick={cleanup=false}){Text(stringResource(R.string.ui_13))}})
}
}
