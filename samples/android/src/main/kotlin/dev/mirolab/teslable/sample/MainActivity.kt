package dev.mirolab.teslable.sample

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.AndroidViewModel
import dev.mirolab.teslable.sample.ui.ControlTab
import dev.mirolab.teslable.sample.ui.LiveTab
import dev.mirolab.teslable.sample.ui.LogTab
import dev.mirolab.teslable.sample.ui.QueryTab

/** 설정 변경(회전 등)에도 Control 탭 상태를 유지한다. 라이브러리와 연결은 [MonitorEngine]이 프로세스 단위로 가진다. */
class MainViewModel(
    app: Application,
) : AndroidViewModel(app) {
    val engine: MonitorEngine = MonitorRuntime.get(app)
    val control = SampleState(engine)
}

private val tabs = listOf("Live", "Query", "Log", "Control")

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var firstLaunch = false

    /** 권한 결과가 오면 서비스를 시작한다. 거부해도 화면은 뜨고, 연결은 `PermissionDenied` 이유로 재시도한다(17-lib-platform-notes.md). */
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startMonitor() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firstLaunch = savedInstanceState == null
        setContent {
            MaterialTheme {
                MonitorApp(viewModel, onConnect = ::connect)
            }
        }
        requestPermissionsThenStart()
    }

    /** SFR-013: 화면이 보이는 동안 꺼지지 않게 한다. */
    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    private fun connect() {
        // 서비스가 내려가 있을 수 있으니(알림의 "중지") 먼저 올리고 바로 연결한다.
        if (hasBluetoothConnect()) {
            MonitorService.start(this, autoConnect = false)
            viewModel.engine.connectNow()
        } else {
            requestPermissionsThenStart()
        }
    }

    private fun requestPermissionsThenStart() {
        val wanted =
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startMonitor() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startMonitor() {
        // connectedDevice 포그라운드 서비스는 BLUETOOTH_CONNECT 가 허용돼 있어야 시작할 수 있다.
        if (hasBluetoothConnect()) {
            MonitorService.start(this, autoConnect = firstLaunch)
            firstLaunch = false
        }
    }

    private fun hasBluetoothConnect() = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

@Composable
private fun MonitorApp(
    viewModel: MainViewModel,
    onConnect: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            tabs.forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title) }) }
        }
        when (tab) {
            0 -> LiveTab(viewModel.engine, onConnect)
            1 -> QueryTab(viewModel.engine)
            2 -> LogTab(viewModel.engine)
            else -> ControlTab(viewModel.control, viewModel.engine, onConnect)
        }
    }
}
