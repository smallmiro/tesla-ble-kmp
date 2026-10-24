package dev.mirolab.teslable.sample

import android.app.Application
import android.content.Context
import android.os.Build
import dev.mirolab.teslable.DebugLogger
import dev.mirolab.teslable.PlatformContext
import dev.mirolab.teslable.TeslaBle
import dev.mirolab.teslable.TeslaBleConfig
import dev.mirolab.teslable.Vehicle
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.port.KeyAccessibility
import dev.mirolab.teslable.port.KeyPolicy
import dev.mirolab.teslable.port.LogLevel
import dev.mirolab.teslable.port.TeslaLogger
import dev.mirolab.teslable.port.TransportState
import dev.mirolab.teslable.port.VehicleKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** 조회 한 번의 결과(Query 탭). [outcome]은 `Success` / `Uncertain` / `Failure`. */
data class QueryResult(
    val call: String,
    val outcome: String,
    val at: Instant,
    val summary: String,
)

/** 모니터 키의 상태(Control 탭). */
data class KeyStatus(
    val text: String = "키 없음",
    /** 키가 있는데 잠금 중 사용이 안 될 때 사용자에게 알릴 문장. */
    val warning: String? = null,
)

/** 로그 `observe` 줄만 골라 [sink]에 넘기고, 나머지(와 observe 줄도)는 Logcat용 [delegate]로 그대로 넘긴다. BLE `TX:`/`RX:` 줄은 파일에 쓰지 않는다. */
private class ObserveLogger(
    private val delegate: TeslaLogger,
    private val sink: LogSink,
) : TeslaLogger {
    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        if (tag == OBSERVE_TAG) {
            val text = message()
            sink.write(LogLine.observe(sink.stamp(), text))
            delegate.log(level, tag) { text }
        } else {
            delegate.log(level, tag, message)
        }
    }

    private companion object {
        const val OBSERVE_TAG = "observe"
    }
}

/**
 * 모니터의 심장. 프로세스에 하나만 있다(`TeslaBle`은 프로세스당 하나, 12-lib-getting-started.md).
 * [MonitorService]가 이것을 포그라운드로 붙들고 알림을 띄운다. 연결 루프, 이벤트 구독, 주기 조회, 로그 쓰기를 모두 여기서 한다.
 */
class MonitorEngine(
    private val app: Application,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val logFolder: File = app.getExternalFilesDir("logs") ?: File(app.filesDir, "logs")
    val log = LogWriter(logFolder, sessionInfo(), CoroutineScope(SupervisorJob() + Dispatchers.IO)) // 파일 쓰기는 자기 코루틴에서

    val ble: TeslaBle = TeslaBle.create(PlatformContext(app), TeslaBleConfig(logger = ObserveLogger(DebugLogger(), log)))

    val savedVin = MutableStateFlow(prefs.getString(KEY_VIN, null))
    private val mutablePollSeconds = MutableStateFlow(prefs.getInt(KEY_POLL, DEFAULT_POLL_SECONDS))
    val pollSeconds: StateFlow<Int> = mutablePollSeconds

    val live = MutableStateFlow(LiveState())
    val queries = MutableStateFlow<Map<String, QueryResult>>(emptyMap())
    val sessionsText = MutableStateFlow("세션: 연결 안 됨")

    @Volatile private var key: VehicleKey? = null
    val keyStatus = MutableStateFlow(KeyStatus())

    private val queryNow = Channel<Unit>(Channel.CONFLATED)
    private var connectionJob: Job? = null

    private val loop =
        ConnectionLoop<Vehicle>(
            scope = scope,
            connect = ::attemptConnect,
            awaitLoss = ::awaitLoss,
            release = { it.disconnect() }, // 링크가 끊겨도 반드시 부른다(12-lib-getting-started.md)
            onTransition = ::onTransition,
        )
    val link: StateFlow<LinkState<Vehicle>> = loop.state

    private fun sessionInfo(): SessionInfo {
        @Suppress("DEPRECATION")
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return SessionInfo(
            platform = "android",
            app = "${info.versionName}(${info.longVersionCode})",
            os = Build.VERSION.RELEASE,
            phone = "${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }

    // --- 사용자 동작 ---------------------------------------------------------------------------------------------------

    /** 저장된 VIN이 있으면 연결을 시작한다. 이 프로세스에서 아직 아무것도 시작하지 않았을 때만(앱 시작 자동 연결, SFR-011). */
    fun autoConnectIfSaved() {
        if (loop.state.value is LinkState.Idle && parsedVin() != null) loop.start()
    }

    /** 연결 버튼. 기다리지 않고 바로 시도한다. VIN이 없거나 형식이 틀리면 `false`. */
    fun connectNow(): Boolean {
        if (parsedVin() == null) return false
        loop.start()
        return true
    }

    /** 연결 끊기 버튼. 자동 재연결도 멈춘다(SFR-012). */
    suspend fun disconnect() = loop.stop()

    /** 서비스가 내려갈 때: 아직 연결 중이거나 재연결 중이면 끊는다. */
    fun stopAsync() {
        val state = loop.state.value
        if (state is LinkState.Connecting || state is LinkState.Connected || state is LinkState.Backoff) scope.launch { loop.stop() }
    }

    /** VIN을 저장한다. 형식이 틀리면 `false`. 이미 연결돼 있으면 건드리지 않는다. */
    fun saveVin(text: String): Boolean {
        val vin = Vin.parseOrNull(text.trim().uppercase()) ?: return false
        prefs.edit().putString(KEY_VIN, vin.value).apply()
        savedVin.value = vin.value
        return true
    }

    /** 저장된 VIN을 지운다. 먼저 연결을 끊는다. */
    suspend fun clearVin() {
        loop.stop()
        prefs.edit().remove(KEY_VIN).apply()
        savedVin.value = null
    }

    fun parsedVin(): Vin? = savedVin.value?.let { Vin.parseOrNull(it) }

    fun setPollSeconds(seconds: Int) {
        val n = seconds.coerceAtLeast(MIN_POLL_SECONDS)
        prefs.edit().putInt(KEY_POLL, n).apply()
        mutablePollSeconds.value = n
        queryNow.trySend(Unit) // 새 주기로 바로 한 번 조회하고 다시 센다
    }

    fun queryNow() {
        queryNow.trySend(Unit)
    }

    /** Mark 버튼. 메모가 비어 있으면 무시한다. */
    fun mark(memo: String) {
        LogLine.mark(log.stamp(), memo)?.let { log.write(it) }
    }

    fun newLogFile() = log.newFile()

    // --- Control 탭이 쓰는 것 --------------------------------------------------------------------------------------------

    fun currentVehicle(): Vehicle? = (loop.state.value as? LinkState.Connected)?.vehicle

    fun currentKey(): VehicleKey? = key

    /**
     * 모니터 키를 읽거나 만든다. 접근성은 만들 때 정해진다(13-lib-pairing.md §Key accessibility). Android는 값을 무시하고
     * 언제나 `AFTER_FIRST_UNLOCK` 을 돌려준다. `Locked` 이면 키를 지우거나 다시 만들지 않는다.
     */
    suspend fun ensureKey(): VehicleResult<VehicleKey> {
        key?.let { return VehicleResult.Success(it) }
        val result = ble.keys.getOrCreate(MONITOR_KEY_ALIAS, KeyPolicy.PREFER_HARDWARE, KeyAccessibility.AFTER_FIRST_UNLOCK)
        when (result) {
            is VehicleResult.Success -> {
                key = result.value
                keyStatus.value = describe(result.value)
            }

            is VehicleResult.Failure -> {
                keyStatus.value =
                    KeyStatus(
                        if (result.error == VehicleError.KeyStoreError.Locked) {
                            "키 잠김: 폰을 잠금 해제하면 다시 읽습니다(키는 지우지 않음)"
                        } else {
                            "키 오류: ${result.error.message}"
                        },
                    )
            }

            is VehicleResult.Uncertain -> {
                keyStatus.value = KeyStatus("키 불확실: ${result.error.message}")
            }
        }
        return result
    }

    private fun describe(k: VehicleKey): KeyStatus {
        val text = "${k.alias}: ${k.protection}, 접근성 ${k.accessibility}"
        val warning =
            if (k.accessibility != KeyAccessibility.AFTER_FIRST_UNLOCK) {
                "이 키는 폰이 잠긴 동안 쓸 수 없습니다(WHEN_UNLOCKED). 키를 지우고 새로 만든 뒤 차량에 다시 등록해야 합니다."
            } else {
                null
            }
        return KeyStatus(text, warning)
    }

    // --- 연결 루프에 끼우는 함수 -------------------------------------------------------------------------------------------

    private suspend fun attemptConnect(): ConnectOutcome<Vehicle> {
        val vin = parsedVin() ?: return ConnectOutcome.Failed("저장된 VIN이 없거나 형식이 틀립니다")
        val keyResult = ensureKey()
        // Locked 면 기다렸다가 다시 읽는다(백오프). 그 밖의 키 오류는 키 없이 연결한다: 방송과 조회는 키가 필요 없다.
        if (keyResult is VehicleResult.Failure && keyResult.error == VehicleError.KeyStoreError.Locked) {
            return ConnectOutcome.Failed("키 저장소가 잠겨 있습니다(폰 잠금 해제 대기)")
        }
        return when (val r = ble.connect(vin, key)) {
            is VehicleResult.Success -> ConnectOutcome.Connected(r.value)
            is VehicleResult.Uncertain -> ConnectOutcome.Failed("불확실: ${r.error.message}")
            is VehicleResult.Failure -> ConnectOutcome.Failed(r.error.message)
        }
    }

    private suspend fun awaitLoss(vehicle: Vehicle): String? {
        val lost = vehicle.connection.first { it is TransportState.Disconnected } as TransportState.Disconnected
        val reason = lost.reason?.message
        log.write(LogLine.link(log.stamp(), "disconnected", reason = reason))
        return reason
    }

    private fun onTransition(state: LinkState<Vehicle>) {
        val ts = log.stamp()
        when (state) {
            LinkState.Idle -> {
                Unit
            }

            is LinkState.Connecting -> {
                stopConnectionJobs()
                log.write(LogLine.link(ts, "connecting", attempt = state.attempt))
            }

            is LinkState.Connected -> {
                log.write(LogLine.link(ts, "connected"))
                startConnectionJobs(state.vehicle)
            }

            is LinkState.Backoff -> {
                stopConnectionJobs()
                log.write(
                    LogLine.link(ts, "backoff", reason = state.reason, attempt = state.attempt, delayMs = state.delay.inWholeMilliseconds),
                )
            }

            LinkState.Stopped -> {
                stopConnectionJobs()
                log.write(LogLine.link(ts, "stopped"))
            }
        }
    }

    // --- 연결마다 새로 도는 것: 이벤트 구독과 주기 조회 --------------------------------------------------------------------------

    @Synchronized
    private fun startConnectionJobs(vehicle: Vehicle) {
        connectionJob?.cancel()
        queryNow.tryReceive()
        connectionJob =
            scope.launch {
                launch { collectEvents(vehicle) }
                launch { pollLoop(vehicle) }
            }
    }

    @Synchronized
    private fun stopConnectionJobs() {
        connectionJob?.cancel()
        connectionJob = null
    }

    /** 이벤트 흐름은 링크가 끊기거나 `disconnect()` 가 불리면 저절로 끝난다. 재연결 판단은 `connection` 으로 한다(루프가 한다). */
    private suspend fun collectEvents(vehicle: Vehicle) {
        vehicle.events.collect { event ->
            log.write(LogLine.event(log.stamp(), event)) // raw는 여기서 한 번만 읽고 VIN을 가려 쓴다
            live.update { LiveReducer.reduce(it, event, Instant.now()) }
        }
    }

    private suspend fun pollLoop(vehicle: Vehicle) {
        while (true) {
            runQueries(vehicle)
            withTimeoutOrNull(mutablePollSeconds.value.seconds) { queryNow.receive() }
        }
    }

    /** 세 조회를 차례로. `Uncertain`/`Failure` 도 그대로 기록하고 다음 주기에 다시 조회한다(조회는 다시 보내도 안전하다). */
    private suspend fun runQueries(vehicle: Vehicle) {
        record("vehicleStatus", vehicle.vehicleStatus()) { LogLine.statusSummary(it) }
        record("keySummary", vehicle.keySummary()) { "entries=${it.numberOfEntries} slots=${it.occupiedSlots}" }
        record("listKeys", vehicle.listKeys()) { list ->
            "${list.size} keys: " + list.joinToString { "slot${it.slot}/${it.role}/${it.formFactor}" }
        }
        sessionsText.value = "세션: " +
            vehicle.sessions.value.entries
                .joinToString { "${it.key}=${it.value}" }
                .ifEmpty { "없음" }
    }

    private fun <T> record(
        call: String,
        result: VehicleResult<T>,
        summarize: (T) -> String,
    ) {
        val (outcome, summary) =
            when (result) {
                is VehicleResult.Success -> "Success" to summarize(result.value)
                is VehicleResult.Uncertain -> "Uncertain" to result.error.message
                is VehicleResult.Failure -> "Failure" to result.error.message
            }
        queries.update { it + (call to QueryResult(call, outcome, Instant.now(), summary)) }
        log.write(LogLine.query(log.stamp(), call, outcome, summary))
    }

    private companion object {
        const val PREFS = "monitor"
        const val KEY_VIN = "vin"
        const val KEY_POLL = "pollSeconds"
        const val DEFAULT_POLL_SECONDS = 30
        const val MIN_POLL_SECONDS = 5
        const val MONITOR_KEY_ALIAS = "teslable-monitor"
    }
}

/** 프로세스 전체에서 [MonitorEngine] 하나를 공유한다. */
object MonitorRuntime {
    @Volatile private var engine: MonitorEngine? = null

    fun get(app: Application): MonitorEngine = engine ?: synchronized(this) { engine ?: MonitorEngine(app).also { engine = it } }
}
