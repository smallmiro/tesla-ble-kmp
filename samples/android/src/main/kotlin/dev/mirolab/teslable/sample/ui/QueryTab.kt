package dev.mirolab.teslable.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.mirolab.teslable.sample.LinkState
import dev.mirolab.teslable.sample.MonitorEngine

private val calls = listOf("vehicleStatus", "keySummary", "listKeys")

/** Query 탭(SFR-015): 주기 조회 결과(Success / Uncertain / Failure와 시각), 조회 주기, 지금 조회. */
@Composable
fun QueryTab(engine: MonitorEngine) {
    val link by engine.link.collectAsState()
    val results by engine.queries.collectAsState()
    val sessions by engine.sessionsText.collectAsState()
    val seconds by engine.pollSeconds.collectAsState()
    var secondsText by remember(seconds) { mutableStateOf(seconds.toString()) }
    val now by rememberNow()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("연결돼 있는 동안 ${seconds}초마다 vehicleStatus(), keySummary(), listKeys() 를 조회합니다. 깨우지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = secondsText,
                onValueChange = { secondsText = it.filter(Char::isDigit).take(4) },
                label = { Text("조회 주기(초)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { secondsText.toIntOrNull()?.let(engine::setPollSeconds) }) { Text("적용") }
        }
        Button(enabled = link is LinkState.Connected, onClick = engine::queryNow) { Text("지금 조회") }
        Text(sessions, style = MaterialTheme.typography.bodySmall)

        calls.forEach { call ->
            val r = results[call]
            SectionTitle(call)
            if (r == null) {
                Text("아직 조회하지 않았습니다", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${r.outcome}  ·  ${formatClock(r.at)} (${formatAgo(r.at, now)})")
                // Uncertain 은 "다시 보내지 말고 다시 조회"(16-lib-errors.md): 조회는 다음 주기나 "지금 조회"로 다시 한다.
                MonoSmall(r.summary)
            }
        }
    }
}
