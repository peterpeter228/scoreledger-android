package com.example.scoreledger.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.scoreledger.R
import com.example.scoreledger.export.*
import kotlinx.coroutines.CancellationException

/** One bounded page bitmap at a time; renderer/file/IO never runs on the UI thread. */
@Composable fun NativeReportPreview(manager:ReportManager,report:ReportFile,save:(()->Unit)?=null,share:(()->Unit)?=null,actionsEnabled:Boolean=true,close:()->Unit) {
    var index by rememberSaveable(manager.reportId(report)) { mutableIntStateOf(0) }
    var textMode by rememberSaveable { mutableStateOf(false) }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var pan by remember(index) { mutableStateOf(Offset.Zero) }
    val transform=rememberTransformableState { zoom,offset,_ -> scale=(scale*zoom).coerceIn(1f,4f);pan=if(scale==1f)Offset.Zero else pan+offset }
    var page by remember(report) { mutableStateOf<RenderedReportPage?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(report,index) {
        loading=true;error=false;page=null
        try { page=manager.renderPage(report,index) }
        catch(e:CancellationException){throw e}
        catch(_:Exception){error=true}
        finally{loading=false}
    }
    // Previous bitmaps lose their only Compose reference when changing pages and are reclaimed
    // normally. Do not recycle an image that the render thread may still be displaying.
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize().padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.report_preview),style=MaterialTheme.typography.titleLarge)
                Text("第${index+1}/${report.pageCount}页 · ${report.scoreRows}条成绩",Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                Row {
                    TextButton(onClick=close,modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.report_close_preview))}
                    TextButton(onClick={textMode=!textMode}){Text(if(textMode)"页面图像" else "文字核对")}

                }
                if(!textMode)TextButton(onClick={scale=1f;pan=Offset.Zero}){Text("双指或双击缩放 · 点此复位")}
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds(),contentAlignment=Alignment.Center) {
                    val rendered=page
                    when {
                        textMode -> SelectionContainer { Text(report.textPages.getOrNull(index) ?: "此报告没有文字快照，请重新生成后核对。",Modifier.fillMaxSize().verticalScroll(rememberScrollState())) }
                        loading -> Text(stringResource(R.string.report_preview_loading))
                        error -> Text(stringResource(R.string.report_preview_error))
                        rendered!=null -> Image(rendered.bitmap.asImageBitmap(),"报告第${rendered.index+1}页图像；原值可在记录与趋势明细查看",Modifier.fillMaxSize().transformable(transform).pointerInput(index){detectTapGestures(onDoubleTap={scale=if(scale>1f)1f else 2.5f;pan=Offset.Zero})}.graphicsLayer(scaleX=scale,scaleY=scale,translationX=pan.x,translationY=pan.y),contentScale=ContentScale.Fit)
                    }
                }
                if(save!=null || share!=null)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    if(save!=null)Button(onClick=save,enabled=actionsEnabled,modifier=Modifier.weight(1f)){Text("保存PDF")}
                    if(share!=null)OutlinedButton(onClick=share,enabled=actionsEnabled,modifier=Modifier.weight(1f)){Text("分享报告")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    OutlinedButton(onClick={index--},enabled=!loading && index>0,modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.report_previous_page))}
                    OutlinedButton(onClick={index++},enabled=!loading && index+1<report.pageCount,modifier=Modifier.heightIn(min=48.dp)){Text(stringResource(R.string.report_next_page))}
                }
            }
        }
    }
}
