package dev.mirolab.teslable.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mirolab.teslable.sample.LogLine
import dev.mirolab.teslable.sample.MonitorEngine

/** Log 탭(SFR-016): 줄 수, 알 수 없는 이벤트 수, 마지막 줄들, 파일 위치, 메모와 Mark, 새 파일. */
@Composable
fun LogTab(engine: MonitorEngine) {
    val stats by engine.log.stats.collectAsState()
    var memo by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("쓴 줄 ${stats.totalLines} (" + stats.byKind.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" } + ")")
        Text("알 수 없는 이벤트(unrecognized) ${stats.unrecognized}  ·  큐가 가득 차 버린 줄 ${stats.dropped}")
        Text("파일: ${stats.currentFile ?: "아직 없음"}", style = MaterialTheme.typography.bodySmall)
        Text("가져오기: tools/samples/pull-monitor-logs.sh android", style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = memo,
            onValueChange = { memo = it.take(LogLine.MEMO_MAX) },
            label = { Text("메모 (최대 ${LogLine.MEMO_MAX}자, 예: before: open driver door)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = memo.isNotBlank(), onClick = {
                engine.mark(memo)
                memo = ""
            }) { Text("Mark") }
            OutlinedButton(onClick = engine::newLogFile) { Text("새 파일") }
        }

        SectionTitle("마지막 줄 (최신이 아래)")
        if (stats.lastLines.isEmpty()) Text("없음", style = MaterialTheme.typography.bodySmall)
        stats.lastLines.forEach { MonoSmall(it) }
    }
}
