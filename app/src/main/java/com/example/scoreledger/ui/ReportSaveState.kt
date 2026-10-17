package com.example.scoreledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scoreledger.export.ReportManager

class ReportSaveState(val busy:Boolean,val message:String,val launch:(String)->Unit)
val LocalReportSaveState=staticCompositionLocalOf<ReportSaveState>{error("Report save owner missing")}

@Composable fun rememberReportSaveState(manager:ReportManager):ReportSaveState {
    val model:FileOperationModel=viewModel(key="report-save")
    val operationContext=androidx.compose.ui.platform.LocalContext.current.applicationContext
    SideEffect{model.initialize(operationContext,"report-save")}
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var token by rememberSaveable { mutableStateOf<String?>(null) }
    var writing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit){if(writing && !model.busy){model.interrupted();writing=false}}
    LaunchedEffect(model.busy){if(!model.busy)writing=false}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id=pendingId;pendingId=null
        val owned=model.pickerFinished(token);token=null
        // Saved opaque ID can survive process recreation; a running write cannot.
        if(!owned && model.busy)return@rememberLauncherForActivityResult
        when {
            uri==null -> model.message="已取消保存，没有报告保存成功"
            id==null -> model.message="报告状态已失效，请重新生成并保存"
            else -> {writing=true;model.run(FileStage.WRITING){
                try { val verified=manager.savePending(id,uri);model.message=if(verified)"PDF已保存（已回读校验）" else "PDF已写入；提供方不支持回读，尚未校验，请打开核对。" }
                finally{writing=false}
            }}
        }
    }
    return ReportSaveState(model.busy || pendingId!=null,model.message) { id ->
        if(!model.busy && pendingId==null) {
            token=model.pick();pendingId=id
            try{launcher.launch("scoreledger-report-${java.time.LocalDate.now()}.pdf")}
            catch(_:Exception){model.pickerFailed(token);token=null;pendingId=null}
        }
    }
}
