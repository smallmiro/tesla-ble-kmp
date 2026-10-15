package io.github.smallmiro.teslable.sample

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.smallmiro.teslable.DebugLogger
import io.github.smallmiro.teslable.PlatformContext
import io.github.smallmiro.teslable.TeslaBle
import io.github.smallmiro.teslable.TeslaBleConfig
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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
                SampleScreen()
            }
        }
    }
}

@Composable
private fun SampleScreen() {
    val context = LocalContext.current
    val state =
        remember {
            SampleState(TeslaBle.create(PlatformContext(context.applicationContext), TeslaBleConfig(logger = DebugLogger())))
        }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Teslable Sample", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = state.vinText.value,
            onValueChange = { state.vinText.value = it },
            label = { Text("VIN") },
        )
        Text(state.connection.value)
        Button(onClick = { scope.launch { state.createKey() } }) { Text("키 만들기/불러오기") }
        Text(state.keyInfo.value, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { scope.launch { state.scan() } }) { Text("스캔") }
        Button(onClick = { scope.launch { state.connect() } }) { Text("연결") }
        Button(onClick = { scope.launch { state.vehicleStatus() } }) { Text("VehicleStatus") }
        Button(onClick = { scope.launch { state.listKeys() } }) { Text("키 목록") }
        Button(onClick = { scope.launch { state.disconnect() } }) { Text("연결 끊기") }
        LazyColumn {
            items(state.output.toList()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
