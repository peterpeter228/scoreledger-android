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
import androidx.compose.ui.unit.dp
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*

@Composable fun HistoryScreen(model: ScoreViewModel,selected: String?,child: ChildEntity?,records: List<ExamWithScores>,drafts: List<DraftEntity>,busy: Boolean,open: (String)->Unit,manage: ()->Unit) {
    val sharedQuery by model.recordQuery.collectAsStateWithLifecycle()
    val query=sharedQuery.copy(childId=selected.orEmpty())
    val result=runCatching { query.apply(records) }
    val visible=result.getOrDefault(emptyList())
    val listState=key(selected) { rememberLazyListState() }
    LazyColumn(state=listState,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.app_title),style=MaterialTheme.typography.headlineMedium);Text(stringResource(R.string.ui_51),style=MaterialTheme.typography.bodyMedium) }
        if(child==null) item {
            Text(stringResource(R.string.ui_52))
            Button(onClick=manage){Text(stringResource(R.string.ui_7))}
        } else {
            item { Button(onClick={model.openNew()},enabled=!busy && !child.archived,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.ui_53))}
                if(child.archived) Text(stringResource(R.string.ui_54)) }
            items(drafts.filter{it.childId==selected},key={"draft-${it.id}"}) { d ->
                val title=runCatching { EntryDraft.decode(d).title }.getOrDefault("")
                OutlinedButton(onClick={model.openDraft(d)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("继续草稿：${title.ifBlank { "未命名考试" }}")}
            }
            item {
                RecordFilters(query,model::setQuery)
                ReportControls(query)
                if(result.isFailure) Text(stringResource(R.string.ui_59),color=MaterialTheme.colorScheme.error)
                Text("${visible.size}次考试",style=MaterialTheme.typography.titleMedium)
            }
            if(visible.isEmpty()) item { Text(if(records.none{it.exam.childId==selected}) "还没有记录。先记下最近一次考试。" else "没有符合条件的记录，可清除筛选。") }
            visible.groupBy { it.exam.examDate.take(7) }.forEach { (month,rows) ->
                item(key="month-$month"){Text(month,style=MaterialTheme.typography.titleLarge)}
                items(rows,key={it.exam.id}) { record -> Card(onClick={open(record.exam.id)},modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(record.exam.title,style=MaterialTheme.typography.titleMedium)
                    Text("${record.exam.examDate} · ${ExamKind.valueOf(record.exam.kind).label()} · ${record.exam.term}")
                    SummaryText(record)
                } } }
            }
        }
    }
}
@Composable fun SummaryText(record: ExamWithScores) {
    val summary=ScoreRules.summarize(record.scores.map { it.toValue() })
    Text(summary.ratePercent?.let { "已录科目得分率 ${it.toPlainString()}%" } ?: "暂无可计算成绩")
    Text("覆盖 ${summary.recordedCount}/${summary.selectedCount}科 · ${if(summary.isComplete) "完整" else "不完整"}")
    if(summary.recordedCount>0) Text("已录科目合计 ${ScoreRules.formatPoints(summary.totalScoreMinor)}/${ScoreRules.formatPoints(summary.totalMaxMinor)}")
}
@Composable fun DetailScreen(model: ScoreViewModel,record: ExamWithScores,busy: Boolean,back: ()->Unit) {
    var deletion by rememberSaveable {mutableStateOf(false)}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=back){Text(stringResource(R.string.ui_43))};Text(record.exam.title,style=MaterialTheme.typography.headlineSmall)
            Text("${record.exam.examDate} · ${ExamKind.valueOf(record.exam.kind).label()}")
            Text("学期：${record.exam.term.ifBlank { "未填写" }} · 年级快照：${record.exam.gradeSnapshot.ifBlank { "未填写" }}")
            SummaryText(record)
        }
        items(record.scores.sortedBy{it.subjectNameSnapshot},key={it.subjectId}) { score -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(score.subjectNameSnapshot,style=MaterialTheme.typography.titleMedium)
            Text(when(score.status){"ABSENT"->"缺考";"MISSING"->"未录入";else->"得分 ${ScoreRules.formatPoints(requireNotNull(score.scoreMinor))}"})
            Text("本次满分 ${ScoreRules.formatPoints(score.maxMinor)}")
        } } }
        if(record.exam.note.isNotEmpty()) item { Text(stringResource(R.string.ui_60));Text(record.exam.note) }
        item { Button(onClick={model.edit(record)},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.ui_61))}
            TextButton(onClick={deletion=true},enabled=!busy){Text(stringResource(R.string.ui_62))} }
    }
    if(deletion) AlertDialog(onDismissRequest={if(!busy)deletion=false},title={Text(stringResource(R.string.ui_63))},text={Text("将删除“${record.exam.title}”和${record.scores.size}条科目成绩，无法撤销。其他考试不受影响。")},
        confirmButton={TextButton(onClick={model.delete(record){deletion=false;back()}},enabled=!busy){Text(stringResource(R.string.ui_64))}},dismissButton={TextButton(onClick={deletion=false},enabled=!busy){Text(stringResource(R.string.ui_13))}})
}
