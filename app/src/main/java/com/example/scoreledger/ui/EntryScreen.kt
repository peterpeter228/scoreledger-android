package com.example.scoreledger.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.example.scoreledger.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import java.time.LocalDate

fun ExamKind.label() = when(this) { ExamKind.UNIT->"单元";ExamKind.MONTHLY->"月考";ExamKind.MIDTERM->"期中";ExamKind.FINAL->"期末";ExamKind.OTHER->"其他" }
fun ScoreStatus.label() = when(this) {ScoreStatus.SCORED->"有分数";ScoreStatus.ABSENT->"缺考";ScoreStatus.MISSING->"未录入"}
@Composable fun KindPicker(value: ExamKind?,all: Boolean=false,enabled: Boolean=true,onChange: (ExamKind?)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        if(all) FilterChip(selected=value==null,onClick={onChange(null)},label={Text(stringResource(R.string.ui_24))},enabled=enabled)
        ExamKind.entries.forEach { kind -> FilterChip(selected=value==kind,onClick={onChange(kind)},label={Text(kind.label())},enabled=enabled) }
    }
}
@Composable fun EntryScreen(model: ScoreViewModel,draft: EntryDraft,subjects: List<SubjectEntity>,busy: Boolean,childName: String) {
    var leaving by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var reloadConfirm by rememberSaveable { mutableStateOf(false) }
    val conflict by model.draftConflict.collectAsStateWithLifecycle()
    val persisted by model.persistedPayload.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    BackHandler { if(!busy) leaving=true }
    fun rowChange(row: DraftRow) { model.change(draft.copy(rows=draft.rows.map { if(it.subjectId==row.subjectId) row else it })) }
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick={leaving=true},enabled=!busy){Text(stringResource(R.string.ui_5))}
            Text(if(draft.editing) "编辑成绩" else "记一次成绩",style=MaterialTheme.typography.headlineSmall)
            Text("孩子：$childName · ${draft.rows.size}/30科")
            if(conflict) {
                Text(stringResource(R.string.draft_conflict),color=MaterialTheme.colorScheme.error)
                TextButton(onClick={model.retryDraft()},enabled=!busy){Text(stringResource(R.string.draft_retry))}
                TextButton(onClick={reloadConfirm=true},enabled=!busy){Text(stringResource(R.string.draft_reload))}
            }
            Text(if(conflict) "草稿有冲突，本页输入尚未保存；请重试或重新载入。" else if(persisted==draft.encode()) "草稿已保存到本机" else "正在保存草稿…",style=MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(draft.title,{model.change(draft.copy(title=it))},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true,label={Text(stringResource(R.string.ui_25))})
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(draft.date,{model.change(draft.copy(date=it))},modifier=Modifier.weight(1f),enabled=!busy,singleLine=true,label={Text(stringResource(R.string.ui_26))})
                TextButton(enabled=!busy,onClick={
                    val d=runCatching { LocalDate.parse(draft.date) }.getOrDefault(LocalDate.now())
                    DatePickerDialog(context,{_,year,month,day->model.change(draft.copy(date=LocalDate.of(year,month+1,day).toString()))},d.year,d.monthValue-1,d.dayOfMonth).show()
                }){Text(stringResource(R.string.ui_27))}
            }
            KindPicker(draft.kind,enabled=!busy){model.change(draft.copy(kind=requireNotNull(it)))}
            OutlinedTextField(draft.term,{model.change(draft.copy(term=it))},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true,label={Text(stringResource(R.string.ui_28))})
        }
        items(draft.rows,key={it.subjectId}) { row -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(row.name,style=MaterialTheme.typography.titleMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                ScoreStatus.entries.forEach { status -> FilterChip(selected=row.status==status,onClick={rowChange(row.copy(status=status))},label={Text(status.label())},enabled=!busy) }
            }
            // Stacked fields remain usable with narrow screens and doubled font size.
            OutlinedTextField(row.score,{rowChange(row.copy(score=it,status=if(it.isBlank()) ScoreStatus.MISSING else ScoreStatus.SCORED))},
                label={Text(stringResource(R.string.ui_29))},enabled=!busy && row.status!=ScoreStatus.ABSENT,modifier=Modifier.fillMaxWidth(),singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Next),keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Next)}))
            OutlinedTextField(row.maximum,{rowChange(row.copy(maximum=it))},label={Text(stringResource(R.string.ui_30))},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Next),keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Next)}))
            row.error()?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            TextButton(onClick={model.change(draft.copy(rows=draft.rows.filterNot { it.subjectId==row.subjectId }))},enabled=!busy){Text(stringResource(R.string.ui_31))}
        } } }
        item { OutlinedButton(onClick={adding=true},enabled=!busy && draft.rows.size<30,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.ui_32))}
            if(subjects.none { !it.archived }) Text(stringResource(R.string.ui_33)) }
        item { OutlinedTextField(draft.note,{model.change(draft.copy(note=it))},label={Text(stringResource(R.string.ui_34))},enabled=!busy,modifier=Modifier.fillMaxWidth(),minLines=2) }
        item { Text(stringResource(R.string.ui_35),style=MaterialTheme.typography.bodySmall)
            Button(onClick={focus.clearFocus();model.submit()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(if(busy) "正在保存…" else "保存这次记录")} }
    }
    if(reloadConfirm) AlertDialog(onDismissRequest={reloadConfirm=false},title={Text(stringResource(R.string.draft_reload_title))},
        text={Text(stringResource(R.string.draft_reload_warning))},
        confirmButton={TextButton(onClick={reloadConfirm=false;model.reloadDraft()},enabled=!busy){Text(stringResource(R.string.draft_reload_confirm))}},
        dismissButton={TextButton(onClick={reloadConfirm=false}){Text("取消")}})
    if(adding) AlertDialog(onDismissRequest={adding=false},title={Text("添加科目 · ${draft.rows.size}/30")},text={
        LazyColumn { items(subjects.filter { !it.archived && draft.rows.none { r->r.subjectId==it.id } },key={it.id}) { s ->
            TextButton(enabled=!busy && draft.rows.size<30,onClick={model.change(draft.copy(rows=draft.rows+DraftRow(s.id,s.name,maximum=ScoreRules.formatPoints(s.defaultMaxMinor))));adding=false},modifier=Modifier.fillMaxWidth()){Text(s.name)}
        } }
    },confirmButton={TextButton(onClick={adding=false}){Text(stringResource(R.string.ui_36))}})
    if(leaving) AlertDialog(onDismissRequest={if(!busy)leaving=false},title={Text(stringResource(R.string.ui_37))},text={Text(stringResource(R.string.ui_38))},
        confirmButton={TextButton(onClick={model.closeEditor(false)},enabled=!busy){Text(stringResource(R.string.ui_39))}},
        dismissButton={Column { TextButton(onClick={leaving=false},enabled=!busy){Text(stringResource(R.string.ui_40))};TextButton(onClick={model.closeEditor(true)},enabled=!busy){Text(stringResource(R.string.ui_41))}}})
}
