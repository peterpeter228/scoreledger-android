package com.example.scoreledger.ui

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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scoreledger.R
import com.example.scoreledger.BuildConfig
import com.example.scoreledger.data.RecordQuery
import com.example.scoreledger.export.*

@Composable fun ReportControls(query:RecordQuery,subjectId:String?=null,examIds:Set<String>?=null) {
    val context=LocalContext.current.applicationContext
    val manager=LocalUiServices.current.reports
    val model:FileOperationModel=viewModel(key="report-generation")
    val operationContext=androidx.compose.ui.platform.LocalContext.current.applicationContext
    SideEffect{model.initialize(operationContext,"report-generation")}
    var anonymous by rememberSaveable { mutableStateOf(true) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var full by rememberSaveable { mutableStateOf(true) }
    var selectedSubject by rememberSaveable(query.childId,subjectId) { mutableStateOf(subjectId) }
    var scopeInfo by remember(query) { mutableStateOf<ReportScope?>(null) }
    var scopeError by remember(query) { mutableStateOf(false) }
    var segmentStart by rememberSaveable(query) { mutableStateOf("1") }
    var segmentEnd by rememberSaveable(query) { mutableStateOf("") }
    var split by rememberSaveable(query) { mutableStateOf(false) }
    val key=listOf(query,examIds,anonymous,notes,full,selectedSubject,split,segmentStart,segmentEnd).toString()
    // Synchronous invalidation fences an older completion before the next effect runs.
    SideEffect { model.invalidateReport(key) }
    val saveState=LocalReportSaveState.current
    val busy=model.busy || saveState.busy
    var preview by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit){if(started && !model.busy){model.interrupted();started=false}}
    LaunchedEffect(model.busy){if(!model.busy)started=false}
    LaunchedEffect(manager,query,examIds) {
        scopeError=false
        try{scopeInfo=manager.scope(query,examIds)}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(_:Exception){scopeError=true}
    }
    fun share(ready:ReportFile) { model.run(FileStage.READING){
        try{context.startActivity(manager.shareIntent(ready).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));model.message="已打开系统分享面板，是否发送由你决定"}
        catch(_:android.content.ActivityNotFoundException){model.message="无法打开分享面板，可使用保存PDF"}
    }}
    val ready=model.report.takeIf{model.reportKey==key}
    if(preview && ready!=null)NativeReportPreview(manager,ready,close={preview=false},save={saveState.launch(manager.reportId(ready))},share={share(ready)},actionsEnabled=!busy)
    Text(stringResource(R.string.report_export),style=MaterialTheme.typography.titleLarge)
    Text("配置 → 预览 → 保存或分享",style=MaterialTheme.typography.bodyMedium)
    val info=scopeInfo
    Text("孩子：${info?.childName ?: "读取中"} · ${info?.exams?.size ?: 0}次考试")
    Text("范围：${query.from.ifBlank{"不限开始"}} 至 ${query.through.ifBlank{"不限结束"}}；${query.term.ifBlank{"全部学期"}}")
    if(scopeError)Text("范围读取失败，请重新进入报告页面。")
    Text(stringResource(R.string.report_warning))
    ReportOption(stringResource(R.string.report_anonymous),anonymous,!busy){anonymous=it}
    ReportOption(stringResource(R.string.report_notes),notes,!busy){notes=it}
    ReportOption("包含单科趋势",full,!busy){full=it}
    if(full) {
        Text("趋势科目（请选择，不会自动按ID选择）")
        info?.subjects?.forEach { (id,name) ->
            Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(selectedSubject==id,enabled=!busy,role=Role.RadioButton){selectedSubject=id},verticalAlignment=Alignment.CenterVertically){RadioButton(selectedSubject==id,null);Text(name)}
        }
    }
    ReportOption("按考试序号分段（同日考试也可拆分）",split,!busy){split=it}
    if(split){
        Text("按日期从早到晚排序，当前共${info?.exams?.size ?: 0}场。两个边界均包含。")
        OutlinedTextField(segmentStart,{segmentStart=it},label={Text("起始考试序号")},enabled=!busy,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(segmentEnd,{segmentEnd=it},label={Text("结束考试序号")},enabled=!busy,modifier=Modifier.fillMaxWidth())
        val a=segmentStart.toIntOrNull();val b=segmentEnd.toIntOrNull()
        if(a!=null && b!=null && info!=null){Text("起：${info.exams.getOrNull(a-1)?.second ?: "无效"}\n止：${info.exams.getOrNull(b-1)?.second ?: "无效"}")}
    }
    val a=segmentStart.toIntOrNull();val b=segmentEnd.toIntOrNull()
    val validSegment=!split || (a!=null && b!=null && a>=1 && b>=a && b<=(info?.exams?.size ?: 0))
    Button(onClick={
        val capturedKey=key;val capturedQuery=query
        val ids=if(split)requireNotNull(info).exams.subList(requireNotNull(a)-1,requireNotNull(b)).map{it.first}.toSet() else examIds
        val options=ReportOptions(anonymous,notes,full,selectedSubject,ids)
        model.invalidateReport(key);started=true
        model.run(FileStage.GENERATING){try{
            val generated=manager.generate(capturedQuery,options)
            if(model.reportKey==capturedKey){model.report=generated;preview=true;model.message="报告已生成。预览、保存和分享使用同一快照。"}
        }finally{started=false}}
    },enabled=!busy && validSegment && !info?.exams.isNullOrEmpty() && (!full || info?.subjects?.any{it.first==selectedSubject}==true),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_generate))}
    ready?.let {
        Text("${it.pageCount}页 · ${it.scoreRows}条成绩；生成时快照，不随记录修改。")
        OutlinedButton(onClick={preview=true},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_preview))}
        OutlinedButton(onClick={saveState.launch(manager.reportId(it))},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_save))}
        OutlinedButton(onClick={share(it)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.report_share))}
    }
    if(busy)Text(stringResource(R.string.report_busy))
    if(model.message.isNotEmpty())Text(model.message)
    if(saveState.message.isNotEmpty())Text(saveState.message)
}

@Composable fun ReportOption(label:String,checked:Boolean,enabled:Boolean=true,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=checked,enabled=enabled,role=Role.Checkbox,onValueChange=change),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked=checked,onCheckedChange=null,enabled=enabled)
        Text(label,Modifier.weight(1f).padding(vertical=8.dp))
    }
}
