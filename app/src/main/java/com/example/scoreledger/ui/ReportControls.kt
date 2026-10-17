package com.example.scoreledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.scoreledger.R
import com.example.scoreledger.BuildConfig
import com.example.scoreledger.ScoreLedgerApplication
import com.example.scoreledger.data.RecordQuery
import com.example.scoreledger.export.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun ReportControls(query:RecordQuery,subjectId:String?=null) {
    val context=LocalContext.current
    val manager=LocalUiServices.current.reports
    val scope=rememberCoroutineScope()
    var show by rememberSaveable { mutableStateOf(false) }
    var anonymous by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(BuildConfig.INTERNAL_BETA) }
    var busy by remember { mutableStateOf(false) }
    var report by remember(query,subjectId) { mutableStateOf<ReportFile?>(null) }
    var preview by remember { mutableStateOf<ReportFile?>(null) }
    var saveTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    fun operation(block:suspend ()->Unit) { if(busy)return;busy=true;message="";scope.launch { try{block()}catch(e:CancellationException){throw e}catch(_:Exception){message="报告操作未完成。请选择有记录的范围或重新生成；保存失败时请删除可能不完整的目标文件。"}finally{busy=false} } }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val target=saveTargetId;saveTargetId=null
        if(uri==null)message="已取消保存，没有报告保存成功" else if(target==null)message="报告状态已失效，请重新生成并保存" else operation { manager.savePending(target,uri);message="PDF已保存" }
    }
    LaunchedEffect(manager) { runCatching { manager.maintenance() } }
    preview?.let { NativeReportPreview(manager,it){preview=null} }
    TextButton(onClick={show=!show},enabled=!busy) { Text(stringResource(R.string.report_export)) }
    if(show) {
        Text(stringResource(R.string.report_warning))
        ReportOption(stringResource(R.string.report_anonymous),anonymous,!busy){anonymous=it;report=null}
        ReportOption(stringResource(R.string.report_notes),notes,!busy){notes=it;report=null}
        if(BuildConfig.INTERNAL_BETA) ReportOption(stringResource(R.string.report_full),full,!busy){full=it;report=null}
        Text(stringResource(R.string.report_scope))
        Button(onClick={operation { report=manager.generate(query,ReportOptions(anonymous,notes,full,subjectId));message="报告已生成，请预览后保存或分享" }},enabled=!busy && query.childId.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_generate))}
        report?.let { ready ->
            Text("${ready.pageCount}页 · ${ready.scoreRows}条成绩；生成时的同一筛选快照，后续修改不会更新此文件。")
            OutlinedButton(onClick={preview=ready},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_preview))}
            TextButton(onClick={operation { val intent=manager.previewIntent(ready);try{context.startActivity(intent)}catch(_:android.content.ActivityNotFoundException){message="没有外部PDF阅读器，可直接使用应用内预览"} }},enabled=!busy){Text(stringResource(R.string.report_external_preview))}
            OutlinedButton(onClick={saveTargetId=manager.reportId(ready);save.launch("scoreledger-report-${java.time.LocalDate.now()}.pdf")},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_save))}
            OutlinedButton(onClick={operation { val intent=manager.shareIntent(ready);try{context.startActivity(intent);message="已打开系统分享面板，是否发送由你决定"}catch(_:android.content.ActivityNotFoundException){message="无法打开分享面板，可使用保存PDF"} }},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_share))}
        }
        if(busy)Text(stringResource(R.string.report_busy))
        if(message.isNotEmpty())Text(message)
    }
}

@Composable fun ReportOption(label:String,checked:Boolean,enabled:Boolean=true,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=change),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=checked,onCheckedChange=null,enabled=enabled)
        Text(label,Modifier.weight(1f).padding(vertical=8.dp))
    }
}
