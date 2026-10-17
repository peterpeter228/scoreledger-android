package com.example.scoreledger.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.example.scoreledger.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.scoreledger.core.ScoreRules
import com.example.scoreledger.data.*

@Composable fun ChildSelector(children: List<ChildEntity>,selected: String?,busy: Boolean,choose: (String)->Unit,manage: ()->Unit) {
    if(children.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal=20.dp)) {
        TextButton(onClick={expanded=true},enabled=!busy,modifier=Modifier.heightIn(min=48.dp)) {
            Text(children.firstOrNull { it.id==selected }?.let { it.nickname+if(it.archived) " · 已归档" else "" } ?: "选择孩子",style=MaterialTheme.typography.titleMedium)
        }
        DropdownMenu(expanded=expanded,onDismissRequest={expanded=false}) {
            children.forEach { child -> DropdownMenuItem(text={Text(child.nickname+if(child.archived) " · 已归档" else "")},onClick={choose(child.id);expanded=false},enabled=!busy) }
            DropdownMenuItem(text={Text("管理孩子档案")},onClick={expanded=false;manage()},enabled=!busy)
        }
    }
}
@Composable fun ChildrenScreen(model: ScoreViewModel,children: List<ChildEntity>,counts: List<ChildExamCount>,busy: Boolean,back: ()->Unit) {
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var grade by rememberSaveable { mutableStateOf("") }
    var deletion by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(modifier=Modifier.testTag("children-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=back){Text(stringResource(R.string.ui_5))};Text("孩子档案",style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.ui_6)) }
        item { Button(onClick={editingId=null;name="";grade="";form=true},enabled=!busy && children.size<20){Text(stringResource(R.string.ui_7))} }
        items(children,key={it.id}) { child -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(child.nickname,style=MaterialTheme.typography.titleMedium);Text(child.gradeLabel.ifBlank { "未填写年级" }+if(child.archived) " · 已归档" else "")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick={editingId=child.id;name=child.nickname;grade=child.gradeLabel;form=true},enabled=!busy){Text(stringResource(R.string.ui_8))}
                TextButton(onClick={model.updateChild(child,child.nickname,child.gradeLabel,!child.archived){}},enabled=!busy){Text(if(child.archived) "取消归档" else "归档")}
                TextButton(onClick={deletion=child.id},enabled=!busy){Text(stringResource(R.string.ui_9))}
            }
        } } }
    }
    if(form) AlertDialog(onDismissRequest={if(!busy)form=false},title={Text(if(editingId==null) "创建孩子档案" else "编辑档案")},text={Column {
        OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.ui_10))},enabled=!busy,singleLine=true)
        OutlinedTextField(grade,{grade=it},label={Text(stringResource(R.string.ui_11))},enabled=!busy,singleLine=true)
    }},confirmButton={TextButton(enabled=!busy,onClick={
        val old=children.firstOrNull {it.id==editingId}
        if(old==null)model.addChild(name,grade){form=false} else model.updateChild(old,name,grade,old.archived){form=false}
    }){Text(stringResource(R.string.ui_12))}},dismissButton={TextButton(onClick={form=false},enabled=!busy){Text(stringResource(R.string.ui_13))}})
    deletion?.let { id -> val child=children.firstOrNull{it.id==id}
        AlertDialog(onDismissRequest={if(!busy)deletion=null},title={Text(stringResource(R.string.ui_14))},text={Text("将永久删除“${child?.nickname.orEmpty()}”的档案、${counts.firstOrNull { it.childId==id }?.examCount ?: 0}次考试、全部成绩及草稿。无法撤销；归档可以保留历史。")},
            confirmButton={TextButton(onClick={model.deleteChild(id){deletion=null}},enabled=!busy){Text(stringResource(R.string.ui_15))}},
            dismissButton={TextButton(onClick={deletion=null},enabled=!busy){Text(stringResource(R.string.ui_13))}})
    }
}
@Composable fun SubjectsScreen(model: ScoreViewModel,subjects: List<SubjectEntity>,busy: Boolean,back: ()->Unit) {
    var form by rememberSaveable { mutableStateOf(false) };var id by rememberSaveable { mutableStateOf<String?>(null) }
    var name by rememberSaveable { mutableStateOf("") };var maximum by rememberSaveable { mutableStateOf("100") };var order by rememberSaveable { mutableStateOf("0") }
    LazyColumn(modifier=Modifier.testTag("subjects-list"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=back){Text(stringResource(R.string.ui_5))};Text("科目管理",style=MaterialTheme.typography.headlineSmall);Text(stringResource(R.string.ui_16)) }
        item { Text(stringResource(R.string.ui_17));Row(Modifier.horizontalScroll(rememberScrollState())) {
            listOf("小学" to listOf("语文","数学","英语"),"初中" to listOf("语文","数学","英语","物理","化学","生物","道德与法治","历史","地理"),"高中" to listOf("语文","数学","英语","物理","化学","生物","思想政治","历史","地理")).forEach { (label,names) -> TextButton(onClick={model.template(names)},enabled=!busy){Text(label)} }
        };Text(stringResource(R.string.ui_18)) }
        item { Button(onClick={id=null;name="";maximum="100";order=subjects.size.toString();form=true},enabled=!busy && subjects.size<100){Text(stringResource(R.string.ui_19))} }
        items(subjects,key={it.id}) { s -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(s.name+if(s.archived) " · 已归档" else "",style=MaterialTheme.typography.titleMedium)
            Text("默认满分 ${ScoreRules.formatPoints(s.defaultMaxMinor)} · 排序 ${s.sortOrder}")
            Row(Modifier.horizontalScroll(rememberScrollState())) { TextButton(onClick={id=s.id;name=s.name;maximum=ScoreRules.formatPoints(s.defaultMaxMinor);order=s.sortOrder.toString();form=true},enabled=!busy){Text(stringResource(R.string.ui_8))}
                TextButton(onClick={model.saveSubject(s,s.name,s.defaultMaxMinor,s.sortOrder,!s.archived){}},enabled=!busy){Text(if(s.archived) "取消归档" else "归档")}}
        } } }
    }
    if(form) AlertDialog(onDismissRequest={if(!busy)form=false},title={Text(stringResource(R.string.ui_20))},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.ui_21))},enabled=!busy)
        OutlinedTextField(maximum,{maximum=it},label={Text(stringResource(R.string.ui_22))},enabled=!busy)
        OutlinedTextField(order,{order=it},label={Text(stringResource(R.string.ui_23))},enabled=!busy)
    }},confirmButton={TextButton(enabled=!busy,onClick={try {
        val s=subjects.firstOrNull{it.id==id};model.saveSubject(s,name,ScoreRules.parsePoints(maximum),order.toInt(),s?.archived ?: false){form=false}
    } catch(e: Exception){model.showError(e.message ?: "请检查输入")}}){Text(stringResource(R.string.ui_12))}},dismissButton={TextButton(onClick={form=false},enabled=!busy){Text(stringResource(R.string.ui_13))}})
}
