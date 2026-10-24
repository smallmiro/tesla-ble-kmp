package dev.mirolab.teslable.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mirolab.teslable.sample.KeyCommand
import dev.mirolab.teslable.sample.KeyControlInputs
import dev.mirolab.teslable.sample.KeyControlState
import dev.mirolab.teslable.sample.KeyControlSteps
import dev.mirolab.teslable.sample.KeyGroup
import dev.mirolab.teslable.sample.KeyState
import dev.mirolab.teslable.sample.LinkState
import dev.mirolab.teslable.sample.LinkText
import dev.mirolab.teslable.sample.MonitorEngine
import dev.mirolab.teslable.sample.StepStatus
import dev.mirolab.teslable.sample.StepView
import kotlinx.coroutines.launch

private const val GRID_COLUMNS = 2

/**
 * 키·제어 탭(SFR-018): 키 → 연결 → 등록 → 제어 네 단계와 버튼 격자. 연결과 모니터 키는 모니터가 가진 것을 쓴다.
 * 단계 규칙은 [KeyControlSteps], 호출 순서는 [KeyControlState]에 있고 이 화면은 그린다.
 */
@Composable
fun KeyControlTab(
    state: KeyControlState,
    engine: MonitorEngine,
    onConnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val link by engine.link.collectAsState()
    val keyStatus by engine.keyStatus.collectAsState()
    val savedVin by engine.savedVin.collectAsState()
    val vinSaved = remember(savedVin) { engine.parsedVin() != null }
    val connected = link is LinkState.Connected
    val steps =
        KeyControlSteps.of(
            KeyControlInputs(
                keyReady = keyStatus.state == KeyState.READY,
                keyLocked = keyStatus.state == KeyState.LOCKED,
                vinSaved = vinSaved,
                connected = connected,
                enrollment = state.enrollment.value,
                busy = state.busy.value,
            ),
        )

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(LinkText.of(link), style = MaterialTheme.typography.titleMedium)

        StepCard("1. 키", steps.key) {
            Button(enabled = steps.key.buttonEnabled, onClick = { scope.launch { state.exclusive { ensureKey() } } }) { Text("키 만들기/불러오기") }
        }
        StepCard("2. 연결", steps.connect) {
            Button(enabled = steps.connect.buttonEnabled, onClick = onConnect) { Text("연결") }
        }
        StepCard("3. 등록", steps.enroll) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = steps.enroll.buttonEnabled,
                    onClick = { scope.launch { state.exclusive { enroll() } } },
                ) { Text("키 등록 요청") }
                OutlinedButton(enabled = steps.checkEnabled, onClick = { scope.launch { state.exclusive { checkEnrollment() } } }) {
                    Text("등록 상태 확인")
                }
            }
        }
        StepCard("4. 제어", steps.control) {
            ControlGrid(enabled = steps.control.buttonEnabled, onPress = { cmd ->
                if (cmd.confirm != null) state.pendingConfirm.value = cmd else scope.launch { state.exclusive { command(cmd) } }
            })
        }

        StatusCard(state, refreshEnabled = KeyControlSteps.refreshEnabled(connected, state.busy.value), onRefresh = {
            scope.launch { state.exclusive { refreshStatus() } }
        })

        ResultList(state)
    }

    state.pendingConfirm.value?.let { cmd ->
        AlertDialog(
            onDismissRequest = { state.pendingConfirm.value = null },
            title = { Text("${cmd.label}?") },
            text = { Text(cmd.confirm.orEmpty()) },
            confirmButton = {
                TextButton(onClick = {
                    state.pendingConfirm.value = null
                    scope.launch { state.exclusive { command(cmd) } }
                }) { Text(cmd.label) }
            },
            dismissButton = { TextButton(onClick = { state.pendingConfirm.value = null }) { Text("취소") } },
        )
    }
}

@Composable
private fun StepCard(
    title: String,
    step: StepView,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = stepColor(step.status)), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(stepLabel(step.status), style = MaterialTheme.typography.labelLarge)
            }
            Text(step.hint, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun stepColor(status: StepStatus): Color =
    when (status) {
        StepStatus.DONE -> MaterialTheme.colorScheme.primaryContainer
        StepStatus.TODO -> MaterialTheme.colorScheme.secondaryContainer
        StepStatus.BLOCKED -> MaterialTheme.colorScheme.surfaceVariant
    }

private fun stepLabel(status: StepStatus): String =
    when (status) {
        StepStatus.DONE -> "완료"
        StepStatus.TODO -> "할 일"
        StepStatus.BLOCKED -> "막힘"
    }

/** 버튼 격자: 기본 묶음은 바로, 다른 차종(Model X, Cybertruck)은 접어 둔다. */
@Composable
private fun ControlGrid(
    enabled: Boolean,
    onPress: (KeyCommand) -> Unit,
) {
    var showOthers by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyGroup.entries.filter { !it.collapsed }.forEach { group -> GroupButtons(group, enabled, onPress) }
        TextButton(onClick = { showOthers = !showOthers }) { Text(if (showOthers) "다른 차종 접기" else "다른 차종 (Model X, Cybertruck)") }
        if (showOthers) KeyGroup.entries.filter { it.collapsed }.forEach { group -> GroupButtons(group, enabled, onPress) }
    }
}

@Composable
private fun GroupButtons(
    group: KeyGroup,
    enabled: Boolean,
    onPress: (KeyCommand) -> Unit,
) {
    SectionTitle(group.title)
    KeyCommand.entries.filter { it.group == group }.chunked(GRID_COLUMNS).forEach { row ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { cmd -> Button(enabled = enabled, onClick = { onPress(cmd) }, modifier = Modifier.weight(1f)) { Text(cmd.label) } }
            repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** 결과 줄(최신이 위). 목록을 읽는 범위를 이 함수로 한정해, 줄이 늘 때 화면 전체가 아니라 이 목록만 다시 그린다. */
@Composable
private fun ResultList(state: KeyControlState) {
    SectionTitle("결과 (최신이 위)")
    if (state.results.isEmpty()) Text("아직 없습니다", style = MaterialTheme.typography.bodySmall)
    state.results.forEach { MonoSmall("${formatClock(it.at)}  ${it.text}") }
}

/** 마지막 `vehicleStatus()`: 잠금, 트렁크, 프렁크, 수면과 읽은 시각. */
@Composable
private fun StatusCard(
    state: KeyControlState,
    refreshEnabled: Boolean,
    onRefresh: () -> Unit,
) {
    val now by rememberNow()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionTitle("차량 상태")
            val snap = state.status.value
            if (snap == null) {
                Text("아직 읽지 않았습니다", style = MaterialTheme.typography.bodySmall)
            } else {
                val s = snap.status
                Text("잠금 ${s.lockState}  ·  수면 ${s.sleepStatus}")
                Text("트렁크 ${s.closures.rearTrunk}  ·  프렁크 ${s.closures.frontTrunk}")
                Text("읽은 시각 ${formatClock(snap.at)} (${formatAgo(snap.at, now)})", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(enabled = refreshEnabled, onClick = onRefresh) { Text("상태 새로고침") }
        }
    }
}
