package dev.mirolab.teslable.sample.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val clockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatClock(at: Instant): String = clockFormat.format(at)

/** 1초마다 갱신되는 현재 시각("n초 전" 표시용). */
@Composable
fun rememberNow(): State<Instant> =
    produceState(Instant.now()) {
        while (true) {
            delay(1_000)
            value = Instant.now()
        }
    }

/** 지난 시간을 짧게: 7초, 3분 12초, 1시간 5분. */
fun formatAgo(
    since: Instant,
    now: Instant,
): String {
    val s = (now.epochSecond - since.epochSecond).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}초 전"
        s < 3_600 -> "${s / 60}분 ${s % 60}초 전"
        else -> "${s / 3_600}시간 ${(s % 3_600) / 60}분 전"
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier)

@Composable
fun MonoSmall(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
