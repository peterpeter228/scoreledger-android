package com.example.scoreledger.ui

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import com.example.scoreledger.R
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import kotlinx.coroutines.launch
import java.time.LocalDate

fun ExamKind.label() = when(this) { ExamKind.UNIT->"单元";ExamKind.MONTHLY->"月考";ExamKind.MIDTERM->"期中";ExamKind.FINAL->"期末";ExamKind.OTHER->"其他" }
fun ScoreStatus.label() = when(this) {ScoreStatus.SCORED->"有分数";ScoreStatus.ABSENT->"缺考";ScoreStatus.MISSING->"未录入"}
@Composable fun KindPicker(value: ExamKind?,all: Boolean=false,enabled: Boolean=true,onChange: (ExamKind?)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(all) FilterChip(selected=value==null,onClick={onChange(null)},label={Text("全部")},enabled=enabled)
        ExamKind.entries.forEach { kind -> FilterChip(selected=value==kind,onClick={onChange(kind)},label={Text(kind.label())},enabled=enabled) }
    }
}
@Composable fun EntryScreen(model: ScoreViewModel,draft: EntryDraft,subjects: List<SubjectEntity>,busy: Boolean,childName: String) {
    var leaving by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var metadata by rememberSaveable(draft.id) { mutableStateOf(false) }
    var reloadConfirm by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var customSubject by rememberSaveable { mutableStateOf("") }
    val conflict by model.draftConflict.collectAsStateWithLifecycle()
    val saveState by model.draftSaveState.collectAsStateWithLifecycle()
    val requestedFocus by model.focusSubjectId.collectAsStateWithLifecycle()
    val undo by model.rowUndoAvailable.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val scope=rememberCoroutineScope()
    val listState=rememberLazyListState()
    val focusers=remember(draft.rows.map { it.subjectId }) { draft.rows.associate { it.subjectId to FocusRequester() } }
    suspend fun focusScore(id:String) {
        val index=draft.rows.indexOfFirst { it.subjectId==id }
        if(index>=0) { listState.animateScrollToItem(index+2);withFrameNanos { };focusers[id]?.requestFocus() }
    }
    LaunchedEffect(requestedFocus,draft.id) { requestedFocus?.let { focusScore(it) } }
    BackHandler { if(!busy) leaving=true }
    fun applyChange(value:EntryDraft) { model.change(value,draft) }
    fun rowChange(row: DraftRow) { applyChange(draft.copy(rows=draft.rows.map { if(it.subjectId==row.subjectId) row else it })) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(state=listState,modifier=Modifier.weight(1f).testTag("entry-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {
                TextButton(onClick={leaving=true},enabled=!busy){Text("返回")}
                Text(if(draft.editing) "编辑成绩" else "记一次成绩",style=MaterialTheme.typography.headlineSmall)
                Text(childName,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Text(draft.title,style=MaterialTheme.typography.titleLarge)
                Text(draft.date,color=MaterialTheme.colorScheme.primary)
                TextButton(onClick={metadata=!metadata}) { Text(if(metadata) "收起信息" else "修改考试信息") }
                if(metadata) {
                    OutlinedTextField(draft.title,{applyChange(draft.copy(title=it))},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true,label={Text("考试名称")})
                    OutlinedTextField(draft.date,{applyChange(draft.copy(date=it))},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true,label={Text("日期 YYYY-MM-DD")})
                    TextButton(enabled=!busy,onClick={
                        val d=runCatching { LocalDate.parse(draft.date) }.getOrDefault(LocalDate.now())
                        DatePickerDialog(context,{_,year,month,day->applyChange(draft.copy(date=LocalDate.of(year,month+1,day).toString()))},d.year,d.monthValue-1,d.dayOfMonth).apply { datePicker.maxDate=System.currentTimeMillis() }.show()
                    }){Text("选择日期")}
                    KindPicker(draft.kind,enabled=!busy){applyChange(draft.copy(kind=requireNotNull(it)))}
                    OutlinedTextField(draft.term,{applyChange(draft.copy(term=it))},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true,label={Text("学期（可选）")})
                    OutlinedTextField(draft.gradeSnapshot.orEmpty(),{applyChange(draft.copy(gradeSnapshot=it))},modifier=Modifier.fillMaxWidth(),enabled=!busy,singleLine=true,label={Text("本次年级（可选）")})
                    OutlinedTextField(draft.note,{applyChange(draft.copy(note=it))},label={Text("备注（可选）")},enabled=!busy,modifier=Modifier.fillMaxWidth(),minLines=2)
                }
                if(!draft.editing && draft.rows.isNotEmpty()) Text("科目与满分可逐项调整",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(draft.rows,key={it.subjectId}) { row ->
                var options by rememberSaveable(row.subjectId) { mutableStateOf(false) }
                val next=draft.rows.dropWhile { it.subjectId!=row.subjectId }.drop(1).firstOrNull { it.status!=ScoreStatus.ABSENT }
                Column(Modifier.fillMaxWidth().testTag("subject-${row.subjectId}"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    val density=LocalDensity.current
                    val measurer=rememberTextMeasurer()
                    val nameStyle=MaterialTheme.typography.titleMedium
                    val maximumStyle=MaterialTheme.typography.bodyMedium
                    val nameWidth=with(density) { measurer.measure(row.name,nameStyle).size.width.toDp()+4.dp }.coerceAtLeast(48.dp)
                    val maximumLabel="/ ${row.maximum}"
                    val maximumWidth=with(density) { measurer.measure(maximumLabel,maximumStyle).size.width.toDp()+4.dp }
                    val scoreMinimum=100.dp*density.fontScale
                    val rowError=row.error()
                    val scoreInput:@Composable (Modifier,Boolean)->Unit={ modifier,compact ->
                        OutlinedTextField(row.score,{rowChange(row.copy(score=it,status=if(it.isBlank()) ScoreStatus.MISSING else ScoreStatus.SCORED))},
                            label=if(compact) null else ({Text(if(row.status==ScoreStatus.ABSENT) "缺考" else "得分")}),
                            placeholder={Text(if(row.status==ScoreStatus.ABSENT) "缺考" else "未录入")},
                            enabled=!busy && row.status!=ScoreStatus.ABSENT,
                            modifier=modifier.testTag("score-${row.subjectId}").focusRequester(requireNotNull(focusers[row.subjectId])).semantics { contentDescription="${row.name}得分" },singleLine=true,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=if(next==null) ImeAction.Done else ImeAction.Next),
                            keyboardActions=KeyboardActions(onNext={next?.let { scope.launch { focusScore(it.subjectId) } }},onDone={focus.clearFocus()}),
                            isError=rowError!=null)
                    }
                    val optionsButton:@Composable ()->Unit={
                        IconButton(onClick={options=!options},enabled=!busy,modifier=Modifier.size(48.dp).testTag("subject-options-${row.subjectId}")) {
                            Icon(painterResource(R.drawable.ic_more),contentDescription="${row.name}：${if(options) "收起选项" else "改满分或标记缺考"}")
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // Measure the actual name and maximum; never truncate a long subject or squeeze numbers.
                        val compact=maxWidth>=320.dp && density.fontScale<=1.3f && row.name.length<=6 &&
                            nameWidth+scoreMinimum+maximumWidth+48.dp+24.dp<=maxWidth
                        if(compact) Row(Modifier.fillMaxWidth().heightIn(min=72.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(row.name,style=nameStyle,modifier=Modifier.width(nameWidth))
                            scoreInput(Modifier.weight(1f),true)
                            Text(maximumLabel,style=maximumStyle,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.width(maximumWidth))
                            optionsButton()
                        } else Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(row.name,style=nameStyle)
                            scoreInput(Modifier.fillMaxWidth(),false)
                            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                                Text("满分 ${row.maximum}",color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f))
                                optionsButton()
                            }
                        }
                    }
                    rowError?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
                    if(options) {
                        OutlinedTextField(row.maximum,{rowChange(row.copy(maximum=it))},label={Text("本次满分")},enabled=!busy,modifier=Modifier.fillMaxWidth(),singleLine=true,
                            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,imeAction=ImeAction.Done),keyboardActions=KeyboardActions(onDone={focus.clearFocus()}))
                        Column { ScoreStatus.entries.forEach { status -> FilterChip(selected=row.status==status,onClick={rowChange(row.copy(status=status,score=if(status==ScoreStatus.SCORED) row.score else ""))},label={Text(status.label())},enabled=!busy) } }
                        TextButton(onClick={model.removeRow(row.subjectId)},enabled=!busy){Text("移除此科")}
                    }
                    HorizontalDivider()
                }
            }
            item {
                OutlinedButton(onClick={picked=emptySet();adding=true},enabled=!busy && draft.rows.size<30,modifier=Modifier.fillMaxWidth()){Text("添加科目")}
                if(undo) TextButton(onClick={model.undoRemoveRow()},enabled=!busy) { Text("撤销移除科目") }
                if(draft.rows.isEmpty()) {
                    Text("添加本次考试的科目，再输入成绩。")
                    if(subjects.none { !it.archived }) TextButton(onClick={model.addSuggestedSubjects(listOf("语文","数学","英语"))},enabled=!busy) { Text("使用语文、数学、英语建议") }
                }
            }
        }
        Surface(shadowElevation=4.dp) { Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp)) {
            Text(when(saveState) { DraftSaveState.Saved->"草稿已保存到本机";DraftSaveState.Saving->"正在保存草稿…";DraftSaveState.Error->"草稿保存失败，输入仍在本页";DraftSaveState.Conflict->"草稿有冲突，输入尚未保存" },style=MaterialTheme.typography.bodyMedium,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            if(saveState==DraftSaveState.Error || conflict) {
                TextButton(onClick={model.retryDraft()},enabled=!busy){Text("重试保存草稿")}
                if(conflict) TextButton(onClick={reloadConfirm=true},enabled=!busy){Text("重新载入草稿")}
            }
            Button(onClick={focus.clearFocus();model.submit()},enabled=!busy,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("save-record")){Text(if(busy) "正在保存…" else "保存这次记录")}
        } }
    }
    if(reloadConfirm) AlertDialog(onDismissRequest={reloadConfirm=false},title={Text("重新载入草稿？")},text={Text("本页尚未保存的修改将被本机已保存草稿替换。")},
        confirmButton={TextButton(onClick={reloadConfirm=false;model.reloadDraft()},enabled=!busy){Text("重新载入")}},dismissButton={TextButton(onClick={reloadConfirm=false}){Text("取消")}})
    if(adding) AlertDialog(onDismissRequest={adding=false},title={Text("选择本次科目")},text={
        LazyColumn(modifier=Modifier.testTag("subject-picker-list")) {
            item {
                OutlinedTextField(customSubject,{customSubject=it},label={Text("新科目名称（可选）")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("custom-subject-name"))
                TextButton(enabled=!busy && customSubject.isNotBlank() && draft.rows.size<30,onClick={model.addSuggestedSubjects(listOf(customSubject));customSubject="";adding=false}) { Text("添加新科目") }
            }
            items(subjects.filter { !it.archived && draft.rows.none { r->r.subjectId==it.id } },key={it.id}) { s ->
            FilterChip(selected=s.id in picked,enabled=!busy && (s.id in picked || picked.size+draft.rows.size<30),onClick={picked=if(s.id in picked) picked-s.id else picked+s.id},label={Text(s.name)},modifier=Modifier.fillMaxWidth())
        } }
    },confirmButton={TextButton(enabled=picked.isNotEmpty()&&!busy,onClick={applyChange(draft.copy(rows=draft.rows+subjects.filter { it.id in picked }.map { DraftRow(it.id,it.name,maximum=ScoreRules.formatPoints(it.defaultMaxMinor)) }));adding=false}){Text("添加所选科目")}},dismissButton={TextButton(onClick={adding=false}){Text("取消")}})
    if(leaving) AlertDialog(onDismissRequest={if(!busy)leaving=false},title={Text("离开录入？")},text={Text("可以保留草稿，下次继续填写。")},
        confirmButton={TextButton(onClick={model.closeEditor(false)},enabled=!busy){Text("保留草稿并返回")}},
        dismissButton={Column { TextButton(onClick={leaving=false},enabled=!busy){Text("继续填写")};TextButton(onClick={model.closeEditor(true)},enabled=!busy){Text("丢弃草稿")}}})
}
