package com.example.scoreledger.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.example.scoreledger.export.ReportManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** SAF ownership must outlive virtualized report controls and their scroll position. */
class ReportSaveState(val busy:Boolean,val message:String,val launch:(String)->Unit)
val LocalReportSaveState=staticCompositionLocalOf<ReportSaveState>{error("Report save owner missing")}

@Composable fun rememberReportSaveState(manager:ReportManager):ReportSaveState {
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    var message by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val id=pendingId;pendingId=null
        when {
            uri==null -> message="已取消保存，没有报告保存成功"
            id==null -> message="报告状态已失效，请重新生成并保存"
            else -> {
                busy=true;message=""
                scope.launch {
                    try { manager.savePending(id,uri);message="PDF已保存" }
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){message="PDF保存未完成，请重新生成后重试；请删除可能不完整的目标文件。"}
                    finally {busy=false}
                }
            }
        }
    }
    return ReportSaveState(busy,message) { id ->
        if(!busy && pendingId==null) {
            pendingId=id;message=""
            launcher.launch("scoreledger-report-${java.time.LocalDate.now()}.pdf")
        }
    }
}
