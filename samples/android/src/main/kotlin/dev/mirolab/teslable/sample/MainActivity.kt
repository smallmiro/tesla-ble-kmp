package dev.mirolab.teslable.sample

import android.Manifest
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import dev.mirolab.teslable.DebugLogger
import dev.mirolab.teslable.PlatformContext
import dev.mirolab.teslable.TeslaBle
import dev.mirolab.teslable.TeslaBleConfig
import kotlinx.coroutines.launch

/** 설정 변경(회전 등)에도 `TeslaBle` 하나를 유지하고, 앱이 끝나면 닫는다. */
class SampleViewModel(
    app: Application,
) : AndroidViewModel(app) {
    val state = SampleState(TeslaBle.create(PlatformContext(app), TeslaBleConfig(logger = DebugLogger())))

    override fun onCleared() = state.close()
}

class MainActivity : ComponentActivity() {
    private val viewModel: SampleViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val launcher =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
                // 거부해도 화면은 뜨고, 스캔은 `bluetooth permission not granted`를 보여 준다(FR-006).
                LaunchedEffect(Unit) {
                    launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                }
                SampleScreen(viewModel.state)
            }
        }
    }
}

@Composable
private fun SampleScreen(state: SampleState) {
    val scope = rememberCoroutineScope()
    val enabled = !state.busy.value

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Teslable Sample", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.vinText.value,
            onValueChange = { state.vinText.value = it },
            label = { Text("VIN") },
        )
        Text(state.connection.value)
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { createKey() } } }) { Text("키 만들기/불러오기") }
        Text(state.keyInfo.value, style = MaterialTheme.typography.bodySmall)
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { scan() } } }) { Text("스캔") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { connect() } } }) { Text("연결") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { vehicleStatus() } } }) { Text("VehicleStatus") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { listKeys() } } }) { Text("키 목록") }
        Button(enabled = enabled, onClick = { scope.launch { state.exclusive { disconnect() } } }) { Text("연결 끊기") }
        LazyColumn {
            items(state.output.toList()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
