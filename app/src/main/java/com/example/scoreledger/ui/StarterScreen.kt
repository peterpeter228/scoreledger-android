package com.example.scoreledger.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.example.scoreledger.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scoreledger.BuildConfig
import com.example.scoreledger.data.RecordQuery

@Composable
fun StarterScreen(model: ScoreViewModel, services:UiServices?=null) {
    val context=androidx.compose.ui.platform.LocalContext.current
    val resolved=services ?: remember(model,context) { UiServices(
        com.example.scoreledger.backup.BackupManager(context,model.repository),
        com.example.scoreledger.export.ReportManager(context,model.repository)) }
    val reportSave=rememberReportSaveState(resolved.reports)
    CompositionLocalProvider(LocalUiServices provides resolved,LocalReportSaveState provides reportSave) {
        val backupContent=rememberBackupSettings()
        CompositionLocalProvider(LocalBackupContent provides backupContent) { StarterContent(model) }
    }
}
@Composable private fun StarterContent(model:ScoreViewModel) {
    val counts by model.childCounts.collectAsStateWithLifecycle()
    val children by model.children.collectAsStateWithLifecycle()
    val childrenLoaded by model.childrenLoaded.collectAsStateWithLifecycle()
    val loadedExamChild by model.loadedExamChildId.collectAsStateWithLifecycle()
    val subjects by model.subjects.collectAsStateWithLifecycle()
    val selected by model.selectedChild.collectAsStateWithLifecycle()
    val exams by model.exams.collectAsStateWithLifecycle()
    val drafts by model.drafts.collectAsStateWithLifecycle()
    val editor by model.editor.collectAsStateWithLifecycle()
    val busy by model.saving.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val savedRecord by model.savedRecordId.collectAsStateWithLifecycle()
    val undo by model.undoAvailable.collectAsStateWithLifecycle()
    val sharedQuery by model.recordQuery.collectAsStateWithLifecycle()
    var reportSubject by rememberSaveable { mutableStateOf<String?>(null) }
    var reportExam by rememberSaveable { mutableStateOf<String?>(null) }
    var reportReturnPage by rememberSaveable { mutableStateOf("") }
    val stateHolder=rememberSaveableStateHolder()
    var destination by rememberSaveable { mutableStateOf("记录") }
    var page by rememberSaveable { mutableStateOf("") }
    var detail by rememberSaveable(selected) { mutableStateOf<String?>(null) }
    val snack=remember { SnackbarHostState() }
    LaunchedEffect(savedRecord) { savedRecord?.let { detail=it;model.consumeSavedRecord() } }
    LaunchedEffect(error) { error?.let { snack.showSnackbar(it);model.clearMessage() } }
    BackHandler(enabled=editor == null && (page.isNotEmpty() || detail != null)) { if(detail!=null) detail=null else if(page=="报告") { page=reportReturnPage;detail=reportExam } else page="" }
    Scaffold(snackbarHost={SnackbarHost(snack)},bottomBar={ if(editor == null && page.isEmpty() && detail == null) NavigationBar {
        listOf("记录","趋势","设置").forEachIndexed { i,label -> NavigationBarItem(selected=destination==label,
            onClick={destination=label},icon={Icon(painterResource(listOf(R.drawable.ic_records,R.drawable.ic_trend,R.drawable.ic_settings)[i]),contentDescription=null)},label={Text(label)}) }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if(editor==null && undo) TextButton(onClick={model.undoDelete()},enabled=!busy,modifier=Modifier.testTag("undo-delete")) { Text("撤销删除记录") }
            if(editor != null) {
                EntryScreen(model,requireNotNull(editor),subjects,busy,children.firstOrNull { it.id == editor?.childId }?.nickname.orEmpty())
            } else when {
                page=="孩子" -> ChildrenScreen(model,children,counts,busy) { page="" }
                page=="科目" -> SubjectsScreen(model,subjects,busy) { page="" }
                !childrenLoaded || (selected!=null && loadedExamChild!=selected) -> Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("正在读取本机记录…")
                }
                detail != null -> {
                    val record=exams.firstOrNull { it.exam.id == detail && it.exam.childId == selected }
                    if(record != null) DetailScreen(model,record,busy,{detail=null}) { reportSubject=null;reportExam=record.exam.id;reportReturnPage=page;detail=null;page="报告" } else { Text(stringResource(R.string.ui_42));TextButton(onClick={detail=null}){Text(stringResource(R.string.ui_43))}}
                }
                page=="报告" -> LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp),modifier=Modifier.testTag("report-list")) {
                    item { TextButton(onClick={page=reportReturnPage;detail=reportExam}) { Text("返回") };Text("导出报告",style=MaterialTheme.typography.headlineSmall) }
                    item { ReportControls(if(reportExam==null) sharedQuery.copy(childId=selected.orEmpty()) else RecordQuery(selected.orEmpty()),reportSubject,reportExam?.let { setOf(it) },dataRevision=listOf(exams,children,subjects)) }
                }
                page=="历史" -> stateHolder.SaveableStateProvider("history-${selected}") {
                    HistoryScreen(model,selected,children.firstOrNull { it.id==selected },exams,drafts,busy,{detail=it},{page=""})
                }
                destination=="设置" -> LazyColumn(modifier=Modifier.testTag("settings-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    item { Text(stringResource(R.string.ui_44),style=MaterialTheme.typography.headlineMedium) }
                    item { Button(onClick={page="孩子"},modifier=Modifier.fillMaxWidth()){Text("孩子档案")}}
                    item { OutlinedButton(onClick={page="科目"},modifier=Modifier.fillMaxWidth()){Text("科目管理")}}
                    item { BackupSettings() }
                    item { Text(stringResource(R.string.ui_47)) }
                    item { Text("${BuildConfig.VERSION_NAME} · ${if(BuildConfig.INTERNAL_BETA) "免费内测，未开通付费" else "开发版，购买不可用"}") }
                    item { Text(stringResource(R.string.ui_48)) }
                }
                else -> {
                    ChildSelector(children,selected,busy,{model.selectChild(it)},{page="孩子"})
                    if(destination=="趋势") TrendScreen(model,selected,exams,subjects) { reportSubject=it;reportExam=null;reportReturnPage="";page="报告" }
                    else {
                        HomeScreen(model,selected,children.firstOrNull { it.id==selected },exams,drafts,busy,{detail=it},{page="历史"})
                    }
                }
            }
        }
    }
}
