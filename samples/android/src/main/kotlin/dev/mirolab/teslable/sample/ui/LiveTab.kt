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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mirolab.teslable.sample.LinkState
import dev.mirolab.teslable.sample.LinkText
import dev.mirolab.teslable.sample.LiveItemKey
import dev.mirolab.teslable.sample.MonitorEngine
import dev.mirolab.teslable.sample.VinMask
import kotlinx.coroutines.launch

/** Live 탭(SFR-011, SFR-014): 연결 배너, VIN 저장·지우기, 연결·끊기, 항목별 마지막 변경 시각, 최근 이벤트. */
@Composable
fun LiveTab(
    engine: MonitorEngine,
    onConnect: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val link by engine.link.collectAsState()
    val live by engine.live.collectAsState()
    val savedVin by engine.savedVin.collectAsState()
    val now by rememberNow()
    var vinText by rememberSaveable { mutableStateOf("") }
    var vinError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = bannerColor(link))) {
            Text(LinkText.of(link), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
        }

        if (savedVin == null) {
            OutlinedTextField(
                value = vinText,
                onValueChange = {
                    vinText = it
                    vinError = false
                },
                label = { Text("VIN (17자, 한 번만 입력하면 저장됩니다)") },
                isError = vinError,
                supportingText = { if (vinError) Text("VIN 형식이 맞지 않습니다") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { vinError = !engine.saveVin(vinText) }) { Text("VIN 저장") }
        } else {
            // 화면에는 가린 VIN만 보인다(스크린샷으로 새지 않게).
            Text("저장된 VIN: ${VinMask.maskText(savedVin.orEmpty())}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = savedVin != null, onClick = onConnect) { Text("연결") }
            OutlinedButton(onClick = { scope.launch { engine.disconnect() } }) { Text("연결 끊기") }
            OutlinedButton(enabled = savedVin != null, onClick = { scope.launch { engine.clearVin() } }) { Text("VIN 지우기") }
        }
        if (link is LinkState.Stopped) Text("자동 재연결이 멈춰 있습니다. 연결 버튼을 누르면 다시 시작합니다.", style = MaterialTheme.typography.bodySmall)

        SectionTitle("차량 상태 (브로드캐스트)")
        val lastAt = live.lastStatusAt
        Text(
            if (lastAt == null) "아직 받은 상태 방송이 없습니다" else "마지막 방송 ${formatClock(lastAt)} (${formatAgo(lastAt, now)})",
            style = MaterialTheme.typography.bodySmall,
        )
        LiveItemKey.entries.forEach { key ->
            val item = live.items[key]
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(key.label)
                Text(
                    if (item == null) {
                        "—"
                    } else {
                        "${item.value}  ·  " + (if (item.changed) "바뀜 " else "처음 관측 ") + formatClock(item.since)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Text(
            "참고: 실내 버튼으로 트렁크를 열고 닫으면 방송이 오지 않을 수 있습니다. 트렁크는 Query 탭의 vehicleStatus() 로 확인하세요.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionTitle("최근 이벤트 (바뀐 것, 인증 요청, 알 수 없는 방송)")
        if (live.recent.isEmpty()) Text("없음", style = MaterialTheme.typography.bodySmall)
        live.recent.forEach { MonoSmall("${formatClock(it.at)}  ${it.text}") }
    }
}

@Composable
private fun bannerColor(state: LinkState<*>) =
    when (state) {
        is LinkState.Connected -> MaterialTheme.colorScheme.primaryContainer
        is LinkState.Connecting, is LinkState.Backoff -> MaterialTheme.colorScheme.tertiaryContainer
        LinkState.Idle, LinkState.Stopped -> MaterialTheme.colorScheme.surfaceVariant
    }
