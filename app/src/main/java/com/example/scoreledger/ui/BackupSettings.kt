package com.example.scoreledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable fun BackupSettings() {
    val manager=LocalUiServices.current.backup
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var last by remember { mutableLongStateOf(manager.lastSuccessfulBackupAt) }
    var pending by remember { mutableStateOf<PendingRestore?>(null) }
    var baseline by remember { mutableStateOf<RestoreBaseline?>(null) }
    var exportWarning by remember { mutableStateOf(false) }
    var cleanup by remember { mutableStateOf(false) }
    LaunchedEffect(manager) { runCatching { manager.maintenance() } }
    fun run(action:suspend ()->Unit) { if(busy)return;busy=true;message="";scope.launch { try { action() } catch(e:CancellationException){throw e} catch(_:Exception){pending=null;baseline=null;cleanup=false;message="操作未完成，原记录不会被部分替换。若正在导出，请删除可能不完整的目标文件后重试；恢复请重新选择文件预览；清除操作请重新点击并确认最新数量。"} finally { busy=false } } }
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if(uri==null)message="已取消备份，未更新成功时间" else run { manager.export(uri);last=manager.lastSuccessfulBackupAt;message="备份已保存，请妥善保管明文文件" } }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri==null)message="已取消选择，数据未改变" else run { pending=manager.preview(uri) } }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_title),style=MaterialTheme.typography.titleLarge)
        Text(if(last==0L) "尚无成功备份" else "最近成功备份：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(last))}")
        Button(onClick={exportWarning=true},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.backup_export))}
        OutlinedButton(onClick={restore.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.backup_restore))}
        Text(stringResource(R.string.backup_privacy))
        Text(stringResource(R.string.backup_help))
        OutlinedButton(onClick={run{baseline=manager.baseline()}},enabled=!busy){Text(stringResource(R.string.backup_clear))}
        TextButton(onClick={cleanup=true},enabled=!busy){Text(stringResource(R.string.backup_cleanup))}
        if(busy) Text(stringResource(R.string.backup_busy))
        if(message.isNotEmpty()) Text(message,Modifier.semantics { liveRegion=LiveRegionMode.Assertive })
    }
    if(exportWarning) AlertDialog(onDismissRequest={exportWarning=false},title={Text(stringResource(R.string.backup_export))},text={Text(stringResource(R.string.backup_warning))},confirmButton={TextButton(onClick={exportWarning=false;export.launch("scoreledger-backup-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").format(java.time.LocalDateTime.now())}.json")}){Text(stringResource(R.string.backup_choose))}},dismissButton={TextButton(onClick={exportWarning=false}){Text(stringResource(R.string.ui_13))}})
    pending?.let { preview -> AlertDialog(onDismissRequest={if(!busy)pending=null},title={Text(stringResource(R.string.backup_confirm))},text={Text("将导入${preview.childCount}个孩子、${preview.examCount}次考试、${preview.scoreCount}条成绩。\n${preview.dateRange}\n当前${preview.oldExamCount}次考试将被完整替换，${preview.oldDraftCount}份草稿将丢弃。不是合并。\n先完成内部回滚快照再写入；快照不含草稿，也不能用于卸载后恢复。请先导出当前数据。")},confirmButton={TextButton(enabled=!busy,onClick={run { manager.restore(preview);pending=null;message="恢复完成，已替换数据；旧草稿已丢弃" }}){Text(stringResource(R.string.backup_replace))}},dismissButton={TextButton(enabled=!busy,onClick={pending=null}){Text(stringResource(R.string.ui_13))}}) }
    baseline?.let { before -> AlertDialog(onDismissRequest={if(!busy)baseline=null},title={Text(stringResource(R.string.backup_clear))},text={Text("删除全部${before.data.children.size}个孩子、${before.data.exams.size}次考试、${before.data.scores.size}条成绩、科目与${before.drafts.size}份草稿。不会删除外部已导出文件，也不保证擦除闪存痕迹。请先备份。")},confirmButton={TextButton(enabled=!busy,onClick={run { manager.clearData(before);baseline=null;message="本机记录已清理；外部备份和内部回滚快照未删除" }}){Text(stringResource(R.string.ui_64))}},dismissButton={TextButton(enabled=!busy,onClick={baseline=null}){Text(stringResource(R.string.ui_13))}}) }
    if(cleanup) AlertDialog(onDismissRequest={cleanup=false},title={Text(stringResource(R.string.backup_cleanup))},text={Text("删除内部回滚快照和临时PDF（正在分享的文件也可能失效），不会删除考试记录或已保存到其他位置的文件。")},confirmButton={TextButton(enabled=!busy,onClick={cleanup=false;run { manager.clearInternalCopies();message="内部快照和临时报告已清理" }}){Text(stringResource(R.string.ui_64))}},dismissButton={TextButton(onClick={cleanup=false}){Text(stringResource(R.string.ui_13))}})
}
