package com.example.scoreledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scoreledger.BuildConfig
import com.example.scoreledger.core.*
import com.example.scoreledger.data.ExamWithScores
import java.time.LocalDate
import java.util.UUID

/** Deliberately labelled a starter: unfinished PRD screens must not be presented as complete. */
@Composable
fun StarterScreen(model: ScoreViewModel) {
    val children by model.children.collectAsStateWithLifecycle()
    val allSubjects by model.subjects.collectAsStateWithLifecycle()
    val subjects = allSubjects.filter { !it.archived }.take(30)
    val exams by model.exams.collectAsStateWithLifecycle()
    val selected by model.selectedChild.collectAsStateWithLifecycle()
    val visibleExams = exams.filter { it.exam.childId == selected }
    val saving by model.saving.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var nickname by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val scoreInputs = remember { mutableStateMapOf<String, String>() }
    val maxInputs = remember { mutableStateMapOf<String, String>() }
    val absences = remember { mutableStateMapOf<String, Boolean>() }
    var deleteCandidate by remember { mutableStateOf<ExamWithScores?>(null) }
    val hasDraft = title.isNotBlank() || scoreInputs.values.any { it.isNotBlank() } ||
        maxInputs.values.any { it.isNotBlank() } || absences.values.any { it }
    Scaffold { padding ->
        LazyColumn(modifier=Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal=20.dp),
            verticalArrangement=Arrangement.spacedBy(12.dp), contentPadding=PaddingValues(vertical=20.dp)) {
            item {
                Text("分数记录",style=MaterialTheme.typography.headlineMedium)
                Text(if(BuildConfig.INTERNAL_BETA) "内测骨架 · 未开通付费" else "开发版 · 购买不可用",
                    style=MaterialTheme.typography.labelLarge, color=MaterialTheme.colorScheme.primary)
                Text("首次验收前请只输入测试数据。趋势、PDF、备份和完整页面仍待开发。录入中暂不可新建或切换孩子。",
                    style=MaterialTheme.typography.bodySmall)
            }
            item {
                OutlinedTextField(nickname,{nickname=it},label={Text("孩子昵称")},singleLine=true,modifier=Modifier.fillMaxWidth())
                TextButton(onClick={model.addChild(nickname)},enabled=!saving && !hasDraft && nickname.isNotBlank()) { Text("创建孩子档案") }
            }
            if(children.isNotEmpty()) {
                item {
                    Row(modifier=Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        children.forEach { child ->
                            FilterChip(selected=child.id==selected,onClick={model.selectChild(child.id)},label={Text(child.nickname)},enabled=!saving && !hasDraft)
                        }
                    }
                }
                item {
                    Text("记一次成绩",style=MaterialTheme.typography.titleLarge)
                    OutlinedTextField(title,{title=it},label={Text("考试名称")},enabled=!saving,singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(date,{date=it},label={Text("日期 YYYY-MM-DD")},enabled=!saving,singleLine=true,modifier=Modifier.fillMaxWidth())
                }
                items(subjects,key={it.id}) { subject ->
                    val isAbsent=absences[subject.id] ?: false
                    Card(modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(subject.name,style=MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(scoreInputs[subject.id] ?: "",{scoreInputs[subject.id]=it},
                                    modifier=Modifier.weight(1f),label={Text("得分")},singleLine=true,enabled=!isAbsent && !saving,
                                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                                OutlinedTextField(maxInputs[subject.id] ?: ScoreRules.formatPoints(subject.defaultMaxMinor),{maxInputs[subject.id]=it},
                                    modifier=Modifier.weight(1f),label={Text("满分")},singleLine=true,enabled=!saving,
                                    keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                            }
                            Row {
                                Checkbox(checked=isAbsent,enabled=!saving,onCheckedChange={absences[subject.id]=it})
                                Text("缺考（不按0分计算）",modifier=Modifier.padding(top=12.dp))
                            }
                            if(!isAbsent && scoreInputs[subject.id].isNullOrBlank()) Text("空白分数将保存为“未录入”",style=MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                item {
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                    Button(onClick={
                        try {
                            val rows=subjects.map { s ->
                                val maximum=ScoreRules.parsePoints(maxInputs[s.id] ?: ScoreRules.formatPoints(s.defaultMaxMinor))
                                val raw=scoreInputs[s.id].orEmpty()
                                val status=when {absences[s.id]==true -> ScoreStatus.ABSENT;raw.isBlank() -> ScoreStatus.MISSING;else -> ScoreStatus.SCORED}
                                ScoreValue(s.id,status,if(status==ScoreStatus.SCORED) ScoreRules.parsePoints(raw) else null,maximum)
                            }
                            model.save(draftId,title,date,rows) {
                                title="";scoreInputs.clear();maxInputs.clear();absences.clear();draftId=UUID.randomUUID().toString()
                            }
                        } catch(e: IllegalArgumentException) { model.showError(e.message ?: "请检查输入") }
                    },enabled=!saving && selected!=null && subjects.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {
                        Text(if(saving) "正在保存…" else "保存这次记录")
                    }
                }
                item { Text("最近记录",style=MaterialTheme.typography.titleLarge) }
                if(visibleExams.isEmpty()) item { Text("还没有记录。先记下最近的一次考试。") }
                items(visibleExams,key={it.exam.id}) { record ->
                    val values=record.scores.map { ScoreValue(it.subjectId,ScoreStatus.valueOf(it.status),it.scoreMinor,it.maxMinor) }
                    val summary=ScoreRules.summarize(values)
                    val rate = summary.ratePercent
                    Card(modifier=Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(record.exam.title,style=MaterialTheme.typography.titleMedium)
                            Text(record.exam.examDate)
                            Text(if(rate==null) "暂无可计算成绩" else "已录科目得分率 ${rate.toPlainString()}%（${summary.recordedCount}/${summary.selectedCount}科）")
                            record.scores.forEach { r ->
                                Text("${r.subjectNameSnapshot}：" + when(r.status) {
                                    "ABSENT" -> "缺考"; "MISSING" -> "未录入"
                                    else -> "${ScoreRules.formatPoints(requireNotNull(r.scoreMinor))}/${ScoreRules.formatPoints(r.maxMinor)}"
                                })
                            }
                            TextButton(onClick={deleteCandidate=record},enabled=!saving) { Text("删除记录") }
                        }
                    }
                }
            } else item { error?.let { Text(it,color=MaterialTheme.colorScheme.error) } }
        }
    }
    deleteCandidate?.let { record ->
        AlertDialog(onDismissRequest={deleteCandidate=null},title={Text("删除这次考试？")},
            text={Text("将删除这次考试及其所有科目成绩。此骨架尚无备份恢复功能。")},
            confirmButton={TextButton(onClick={model.delete(record.exam.id);deleteCandidate=null}){Text("确认删除")}},
            dismissButton={TextButton(onClick={deleteCandidate=null}){Text("取消")}})
    }
}
