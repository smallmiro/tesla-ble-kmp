package dev.mirolab.teslable.sample.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mirolab.teslable.sample.LinkText
import dev.mirolab.teslable.sample.MonitorEngine
import dev.mirolab.teslable.sample.SampleState
import kotlinx.coroutines.launch

/**
 * Control 탭: 예전 단일 화면(SFR-010). 연결과 모니터 키는 모니터가 가진 것을 쓴다. 모니터 키(`teslable-monitor`)를 차량에 등록하려면
 * 연결한 뒤 "키 등록 요청"을 누른다.
 */
@Composable
fun ControlTab(
    state: SampleState,
    engine: MonitorEngine,
    onConnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val enabled = !state.busy.value
    val link by engine.link.collectAsState()
    val keyStatus by engine.keyStatus.collectAsState()

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Teslable Sample — Control", style = MaterialTheme.typography.titleLarge)
        Text(LinkText.of(link))
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { createKey() } } }) { Text("키 만들기/불러오기 (teslable-monitor)") }
        Text(keyStatus.text, style = MaterialTheme.typography.bodySmall)
        keyStatus.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(state.keyInfo.value, style = MaterialTheme.typography.bodySmall)
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { scan() } } }) { Text("스캔") }
        Button(enabled = enabled, onClick = onConnect) { Text("연결") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { vehicleStatus() } } }) { Text("VehicleStatus") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { listKeys() } } }) { Text("키 목록") }
        state.keyEntries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.entryLabel(entry), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(enabled = enabled && entry.publicKey != null, onClick = { state.pendingRemoval.value = entry }) { Text("삭제") }
            }
        }
        Text("페어링", style = MaterialTheme.typography.titleSmall)
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { pair() } } }) { Text("키 등록 요청") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { confirmEnrollment() } } }) { Text("등록 확인") }
        Text("세션", style = MaterialTheme.typography.titleSmall)
        Button(
            enabled = enabled,
            onClick = { scope.launch { state.exclusive { startSession(vcsecOnly = true) } } },
        ) { Text("핸드셰이크: VCSEC만") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { startSession(vcsecOnly = false) } } }) { Text("핸드셰이크: 전체") }
        Button(enabled = enabled, onClick = { state.showSessions() }) { Text("세션 상태 보기") }
        Text("제어", style = MaterialTheme.typography.titleSmall)
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { lock() } } }) { Text("잠금") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { unlock() } } }) { Text("해제") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { openTrunk() } } }) { Text("트렁크 열기") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { closeTrunk() } } }) { Text("트렁크 닫기") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { openFrunk() } } }) { Text("프렁크 열기") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { wakeUp() } } }) { Text("깨우기") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { disconnect() } } }) { Text("연결 끊기") }
        state.pendingRemoval.value?.let { entry ->
            AlertDialog(
                onDismissRequest = { state.pendingRemoval.value = null },
                title = { Text("키를 삭제할까요?") },
                text = {
                    Text(
                        (if (state.isOwn(entry)) "이 기기의 키를 지웁니다. 지우면 이 기기로는 더 이상 차량을 제어할 수 없습니다.\n" else "") +
                            state.entryLabel(entry),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        state.pendingRemoval.value = null
                        scope.launch { state.exclusive { removeKey(entry) } }
                    }) { Text("삭제") }
                },
                dismissButton = { TextButton(onClick = { state.pendingRemoval.value = null }) { Text("취소") } },
            )
        }
        state.output.toList().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
