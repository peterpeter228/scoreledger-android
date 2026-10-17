package com.example.scoreledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.example.scoreledger.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*

@Composable fun HomeScreen(model: ScoreViewModel,selected: String?,child: ChildEntity?,records: List<ExamWithScores>,drafts: List<DraftEntity>,busy: Boolean,open: (String)->Unit,all: ()->Unit) {
    var nickname by rememberSaveable { mutableStateOf("") }
    var suggestions by rememberSaveable { mutableStateOf(listOf("语文","数学","英语")) }
    val recent=records.filter { it.exam.childId==selected }.sortedWith(compareByDescending<ExamWithScores>{it.exam.examDate}.thenByDescending{it.exam.createdAt})
    Column(Modifier.fillMaxSize()) {
        LazyColumn(modifier=Modifier.weight(1f).testTag("home-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item { Text("成绩记录",style=MaterialTheme.typography.headlineMedium) }
            if(child==null) {
                item { Text("用昵称开始，成绩只保存在这台设备。",style=MaterialTheme.typography.bodyLarge) }
                item { OutlinedTextField(nickname,{nickname=it},label={Text("孩子昵称")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("first-nickname")) }
                item { Text("建议科目，可随时调整",style=MaterialTheme.typography.titleMedium)
                    listOf("语文","数学","英语").forEach { name -> FilterChip(selected=name in suggestions,onClick={suggestions=if(name in suggestions)suggestions-name else suggestions+name},label={Text(name)},modifier=Modifier.testTag("first-subject-$name")) }
                }
            } else {
                items(drafts.filter{it.childId==selected},key={"draft-${it.id}"}) { d ->
                    val title=runCatching { EntryDraft.decode(d).title }.getOrDefault("")
                    OutlinedButton(onClick={model.openDraft(d)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("继续草稿：${title.ifBlank { "未命名考试" }}")}
                }
                val latest=recent.firstOrNull()
                if(latest==null) item { Text("还没有记录。先记下最近一次考试。") }
                else {
                    val missing=latest.scores.firstOrNull { it.status=="MISSING" }
                    if(missing!=null) item { FilledTonalButton(onClick={model.edit(latest,missing.subjectId)},enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("complete-missing")) { Text("${missing.subjectNameSnapshot}还没录 · 补录") } }
                    item { Text("最近一次",style=MaterialTheme.typography.titleMedium)
                        Card(onClick={open(latest.exam.id)},modifier=Modifier.fillMaxWidth().testTag("latest-record"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                Text(latest.exam.title,style=MaterialTheme.typography.titleLarge)
                                Text(latest.exam.examDate,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                CoverageText(latest)
                                latest.scores.take(3).forEach { score ->
                                    Column { Text(score.subjectNameSnapshot,style=MaterialTheme.typography.titleMedium);Text(scoreLabel(score)) };HorizontalDivider()
                                }
                                Text(if(latest.scores.size>3) "查看全部${latest.scores.size}科" else "查看详情",color=MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    item { Text("近期记录",style=MaterialTheme.typography.titleMedium);TextButton(onClick=all,modifier=Modifier.testTag("all-records")){Text("全部记录 / 搜索") } }
                    items(recent.drop(1).take(2),key={it.exam.id}) { record -> RecordCard(record) { open(record.exam.id) } }
                }
                if(child.archived) item { Text("此档案已归档，已有记录仍可查看与纠错。") }
            }
        }
        Surface { Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp)) {
            Button(onClick={if(child==null)model.startFirstRecord(nickname,suggestions) else model.openNew()},enabled=!busy && (child==null || !child.archived) && (child!=null || (nickname.isNotBlank()&&suggestions.isNotEmpty())),modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("new-record")) { Text(if(child==null) "开始记成绩" else "记一次成绩") }
        } }
    }
}
@Composable fun HistoryScreen(model: ScoreViewModel,selected: String?,child: ChildEntity?,records: List<ExamWithScores>,drafts: List<DraftEntity>,busy: Boolean,open: (String)->Unit,manage: ()->Unit) {
    val sharedQuery by model.recordQuery.collectAsStateWithLifecycle()
    val query=sharedQuery.copy(childId=selected.orEmpty())
    val result=runCatching { query.apply(records) }
    val visible=result.getOrDefault(emptyList())
    val listState=rememberLazyListState()
    LazyColumn(modifier=Modifier.testTag("history-list"),state=listState,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item { TextButton(onClick=manage){Text("返回记录")};Text("全部记录",style=MaterialTheme.typography.headlineMedium) }
        item { RecordFilters(query,model::setQuery);Text("${visible.size}次考试",style=MaterialTheme.typography.titleMedium) }
        if(result.isFailure) item { Text("筛选条件无效，请重新设置。",color=MaterialTheme.colorScheme.error) }
        if(visible.isEmpty()) item { Text("没有符合条件的记录。")
            TextButton(onClick={model.setQuery(RecordQuery(selected.orEmpty()))}) { Text("清除筛选") }
        }
        visible.groupBy { it.exam.examDate.take(7) }.forEach { (month,rows) ->
            item(key="month-$month"){Text(month,style=MaterialTheme.typography.titleLarge)}
            items(rows,key={it.exam.id}) { record -> RecordCard(record) { open(record.exam.id) } }
        }
    }
}
@Composable private fun RecordCard(record: ExamWithScores,open:()->Unit) {
    Card(onClick=open,modifier=Modifier.fillMaxWidth().testTag("record-${record.exam.id}"),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(record.exam.title,style=MaterialTheme.typography.titleMedium)
            Text("${record.exam.examDate} · ${ExamKind.valueOf(record.exam.kind).label()}",color=MaterialTheme.colorScheme.onSurfaceVariant)
            CoverageText(record)
        }
    }
}
@Composable private fun CoverageText(record:ExamWithScores) {
    val count=record.scores.count { it.status=="SCORED" }
    Text(if(count==record.scores.size) "${count}科已记全" else "已记${count}科，共${record.scores.size}科")
}
private fun scoreLabel(score:ScoreEntity):String = when(score.status) {
    "ABSENT"->"缺考 · 满分 ${ScoreRules.formatPoints(score.maxMinor)}"
    "MISSING"->"未录入 · 满分 ${ScoreRules.formatPoints(score.maxMinor)}"
    else->"${ScoreRules.formatPoints(requireNotNull(score.scoreMinor))} / ${ScoreRules.formatPoints(score.maxMinor)}"
}
@Composable fun SummaryText(record: ExamWithScores) {
    val summary=ScoreRules.summarize(record.scores.map { it.toValue() })
    CoverageText(record)
    if(summary.recordedCount>0) {
        Text("已录科目合计 ${ScoreRules.formatPoints(summary.totalScoreMinor)} / ${ScoreRules.formatPoints(summary.totalMaxMinor)}")
        Text("已录科目得分率 ${summary.ratePercent?.toPlainString()}%")
    }
}
@Composable fun DetailScreen(model: ScoreViewModel,record: ExamWithScores,busy: Boolean,back: ()->Unit,saved:Boolean=false,report:()->Unit={}) {
    var deletion by rememberSaveable {mutableStateOf(false)}
    var more by rememberSaveable {mutableStateOf(false)}
    LazyColumn(modifier=Modifier.testTag("detail-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=back){Text(stringResource(R.string.ui_43))}
            if(saved) Text("已保存到本机",color=MaterialTheme.colorScheme.primary,modifier=Modifier.testTag("saved-record-notice").semantics { liveRegion=LiveRegionMode.Polite })
            Text(record.exam.title,style=MaterialTheme.typography.headlineSmall)
            Text("${record.exam.examDate} · ${ExamKind.valueOf(record.exam.kind).label()}")
            Text("学期：${record.exam.term.ifBlank { "未填写" }} · 本次年级：${record.exam.gradeSnapshot.ifBlank { "未填写" }}")
            SummaryText(record)
        }
        items(record.scores.sortedBy{it.subjectNameSnapshot},key={it.subjectId}) { score -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(score.subjectNameSnapshot,style=MaterialTheme.typography.titleMedium)
            Text(when(score.status){"ABSENT"->"缺考";"MISSING"->"未录入";else->"得分 ${ScoreRules.formatPoints(requireNotNull(score.scoreMinor))}"})
            Text("本次满分 ${ScoreRules.formatPoints(score.maxMinor)}")
        } } }
        if(record.exam.note.isNotEmpty()) item { Text(stringResource(R.string.ui_60));Text(record.exam.note) }
        item { Button(onClick={model.edit(record)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.ui_61))}
            TextButton(onClick=report,modifier=Modifier.testTag("open-record-report")){Text("导出报告")}
            TextButton(onClick={more=!more},enabled=!busy){Text("更多操作")}
            if(more) TextButton(onClick={deletion=true},enabled=!busy){Text(stringResource(R.string.ui_62),color=MaterialTheme.colorScheme.error)} }
    }
    if(deletion) AlertDialog(onDismissRequest={if(!busy)deletion=false},title={Text(stringResource(R.string.ui_63))},text={Text("将删除“${record.exam.title}”和${record.scores.size}条科目成绩，删除后10秒内可撤销；关闭应用后不可恢复。其他考试不受影响。")},
        confirmButton={TextButton(onClick={model.delete(record){deletion=false;back()}},enabled=!busy){Text(stringResource(R.string.ui_64))}},dismissButton={TextButton(onClick={deletion=false},enabled=!busy){Text(stringResource(R.string.ui_13))}})
}
