package io.github.smallmiro.teslable

import io.github.smallmiro.teslable.application.cache.SessionCacheSync
import io.github.smallmiro.teslable.application.dispatcher.Dispatcher
import io.github.smallmiro.teslable.application.logging.SafeLogger
import io.github.smallmiro.teslable.application.vehicle.CommandTimeouts
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.crypto.platformCryptoPrimitives
import io.github.smallmiro.teslable.crypto.platformRandomSource
import io.github.smallmiro.teslable.model.KeyId
import io.github.smallmiro.teslable.model.VehicleDomain
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.Transport
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * 공개 진입점(SDD §2.3). 스캔·연결·키 보관소를 제공한다. 앱 수명 동안 하나를 만들어 쓰고, 다 쓰면 [close](연결된 차량도 끊는다).
 * 연결마다 차량 스코프(`SupervisorJob + Dispatchers.Default + CoroutineName`)를 따로 만든다(SDD §5).
 */
@Suppress("LongParameterList") // 포트 주입(테스트) — 앱은 create()만 쓴다
public class TeslaBle internal constructor(
    private val transports: TransportFactory,
    /** 차량 키 보관소(FR-020, FR-021, FR-028). 키를 지우면 그 키의 세션 캐시는 다음 연결 때 `keyId` 불일치로 지워진다(설계 구체화 9). */
    public val keys: VehicleKeyStore,
    private val sessionCache: SessionCache,
    private val crypto: CryptoPrimitives,
    private val random: RandomSource,
    private val config: TeslaBleConfig,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val vehicleScope: (Vin) -> CoroutineScope,
    private val ownScope: CoroutineScope? = null,
) {
    private val logger = SafeLogger(config.logger)
    private val closed = MutableStateFlow(false)
    private val vehiclesMutex = Mutex()
    private val vehicles = LinkedHashSet<DefaultVehicle>()

    /** [vin] 차량의 광고를 찾는다(FR-001, FR-030). 권한·블루투스 문제는 `ScanEvent.Failed`. [close] 뒤에는 BLE 작업 없이 `Failed(Disconnected)` 하나. */
    public fun scan(vin: Vin): Flow<ScanEvent> =
        flow {
            if (closed.value) {
                emit(ScanEvent.Failed(VehicleError.TransportError.Disconnected))
            } else {
                emitAll(transports.scan(LocalName.of(vin, crypto)))
            }
        }

    /**
     * 연결(FR-002~FR-004)하고 캐시를 복원한다. [key]가 null이면 인증 없는 조회만 쓸 수 있다. [target]은 [scan]에서 받은 광고(없으면 스캔).
     * Go CLI 순서: 연결 → `NewVehicle`(캐시) → `Connect`(수신 시작). 핸드셰이크는 `Vehicle.startSession`.
     * [close]가 먼저 불렸거나 연결 도중 불리면 `Failure(NotConnected)`(잡은 전송은 놓는다). 호출자가 취소되면 `CancellationException`.
     */
    public suspend fun connect(
        vin: Vin,
        key: VehicleKey?,
        target: VehicleAdvertisement? = null,
        options: ConnectOptions = ConnectOptions(),
    ): VehicleResult<Vehicle> {
        if (closed.value) return VehicleResult.Failure(VehicleError.NotConnected)
        val transport =
            try {
                when (val result = transports.connect(vin, target, options.copy(timeout = options.timeout ?: config.connectTimeout))) {
                    is VehicleResult.Success -> result.value
                    is VehicleResult.Failure -> return result
                    is VehicleResult.Uncertain -> return result
                }
            } catch (e: CancellationException) {
                return notConnectedIfOnlyTheAppScopeWasCancelled(e)
            }
        if (closed.value) {
            withContext(NonCancellable) { transport.close() }
            return VehicleResult.Failure(VehicleError.NotConnected)
        }
        return open(vin, key, transport)
    }

    private suspend fun open(
        vin: Vin,
        key: VehicleKey?,
        transport: Transport,
    ): VehicleResult<Vehicle> {
        val scope = vehicleScope(vin)
        var session: VehicleSession? = null
        @Suppress("TooGenericExceptionCaught") // 취소 포함 모든 늦은 실패에서 전송과 스코프를 반드시 풀고 다시 던진다
        try {
            val dispatcher = Dispatcher(transport, key, crypto, random, scope, timeSource, logger)
            val sync = key?.let { SessionCacheSync(sessionCache, vin, KeyId.of(it.publicKey, crypto)) }
            val timeouts = CommandTimeouts(config.commandTimeout, config.commandLifetime, config.handshakeTimeout)
            val created = VehicleSession(dispatcher, timeouts, sync, scope).also { session = it }
            val restored = created.connect().mapNotNull { VehicleDomain.of(it) }.toSet()
            val vehicle = DefaultVehicle(vin, key != null, transport, created, restored, scope, config, onDisconnected = ::forget)
            // close()는 closed를 먼저 세운 뒤 같은 잠금 아래에서 목록을 읽는다 — 여기서 closed를 못 봤다면 close()가 이 차량을 끊는다
            val registered = vehiclesMutex.withLock { if (closed.value) false else vehicles.add(vehicle) }
            if (!registered) {
                release(session, transport, scope, cause = null)
                return VehicleResult.Failure(VehicleError.NotConnected)
            }
            logger.log(LogLevel.INFO, TAG) { "Connected to $vin (restored ${restored.size} session(s) from cache)" }
            return VehicleResult.Success(vehicle)
        } catch (e: CancellationException) {
            release(session, transport, scope, cause = e)
            return notConnectedIfOnlyTheAppScopeWasCancelled(e)
        } catch (t: Throwable) {
            release(session, transport, scope, cause = t)
            throw t
        }
    }

    /** 늦은 실패·close 경합에서 세션(없으면 전송)과 차량 스코프를 푼다. 정리 실패는 [cause]에 붙이고, 스코프 취소는 반드시 한다. */
    private suspend fun release(
        session: VehicleSession?,
        transport: Transport,
        scope: CoroutineScope,
        cause: Throwable?,
    ) {
        @Suppress("TooGenericExceptionCaught") // 정리 실패가 원래 실패를 가리지 않게 붙여 둔다
        try {
            withContext(NonCancellable) { session?.disconnect() ?: transport.close() }
        } catch (cleanup: Throwable) {
            if (cause == null) throw cleanup
            cause.addSuppressed(cleanup)
        } finally {
            scope.cancel()
        }
    }

    /** 호출자가 아직 살아 있으면 취소는 [close]의 앱 스코프 취소에서 왔다 — 값으로 바꾼다(ADR-0010: 호출자 취소만 전파). */
    private suspend fun notConnectedIfOnlyTheAppScopeWasCancelled(e: CancellationException): VehicleResult<Vehicle> {
        if (!currentCoroutineContext().isActive) throw e
        logger.log(LogLevel.WARN, TAG) { "Connect interrupted because TeslaBle was closed" }
        return VehicleResult.Failure(VehicleError.NotConnected)
    }

    private suspend fun forget(vehicle: DefaultVehicle) {
        vehiclesMutex.withLock { vehicles.remove(vehicle) }
    }

    /**
     * 이 인스턴스를 닫는다(멱등). 연결된 모든 차량을 `disconnect()`해(캐시 저장 → 세션 키 제거 → 링크 해제) 플랫폼 링크가 남지 않게 한 뒤
     * 백그라운드 작업을 멈춘다. 끊기는 취소되지 않으며 실패는 로그만 남긴다. 진행 중인 [connect]는 `Failure(NotConnected)`, 이후 [scan]은
     * `ScanEvent.Failed(Disconnected)`.
     */
    public suspend fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        withContext(NonCancellable) {
            val live = vehiclesMutex.withLock { vehicles.toList().also { vehicles.clear() } }
            for (vehicle in live) {
                @Suppress("TooGenericExceptionCaught") // 한 차량의 실패가 다른 차량의 정리를 막지 않게
                try {
                    vehicle.disconnect()
                } catch (e: Exception) {
                    logger.log(LogLevel.ERROR, TAG) { "Failed to disconnect ${vehicle.vin} while closing: ${e::class.simpleName}" }
                }
            }
            ownScope?.cancel()
        }
    }

    /** 생성. */
    public companion object {
        private const val TAG = "teslable"

        /** 플랫폼 기본 어댑터로 만든다. */
        public fun create(
            platform: PlatformContext,
            config: TeslaBleConfig = TeslaBleConfig(),
        ): TeslaBle {
            val logger = SafeLogger(config.logger)
            val handler =
                CoroutineExceptionHandler {
                    _,
                    e,
                    ->
                    logger.log(LogLevel.ERROR, TAG) { "Unhandled error: ${e::class.simpleName}" }
                }
            val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("teslable") + handler)
            val components = platformComponents(platform, config, appScope, logger)
            return TeslaBle(
                transports = components.transports,
                keys = components.keys,
                sessionCache = components.sessionCache,
                crypto = platformCryptoPrimitives(),
                random = platformRandomSource(),
                config = config,
                vehicleScope = { vin ->
                    // $vin은 Vin.toString()(마스킹)이다(FR-112).
                    CoroutineScope(
                        SupervisorJob(appScope.coroutineContext[Job]) + Dispatchers.Default + CoroutineName("teslable-$vin") + handler,
                    )
                },
                ownScope = appScope,
            )
        }
    }
}
