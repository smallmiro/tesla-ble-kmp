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
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.ConnectOptions
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.port.ScanEvent
import io.github.smallmiro.teslable.port.SessionCache
import io.github.smallmiro.teslable.port.TransportFactory
import io.github.smallmiro.teslable.port.VehicleAdvertisement
import io.github.smallmiro.teslable.port.VehicleKey
import io.github.smallmiro.teslable.port.VehicleKeyStore
import io.github.smallmiro.teslable.transport.LocalName
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlin.time.TimeSource

/**
 * 공개 진입점(SDD §2.3). 스캔·연결·키 보관소를 제공한다. 앱 수명 동안 하나를 만들어 쓰고, 다 쓰면 [close].
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

    /** [vin] 차량의 광고를 찾는다(FR-001, FR-030). 권한·블루투스 문제는 `ScanEvent.Failed`. */
    public fun scan(vin: Vin): Flow<ScanEvent> = transports.scan(LocalName.of(vin, crypto))

    /**
     * 연결(FR-002~FR-004)하고 캐시를 복원한다. [key]가 null이면 인증 없는 조회만 쓸 수 있다. [target]은 [scan]에서 받은 광고(없으면 스캔).
     * Go CLI 순서: 연결 → `NewVehicle`(캐시) → `Connect`(수신 시작). 핸드셰이크는 `Vehicle.startSession`.
     */
    public suspend fun connect(
        vin: Vin,
        key: VehicleKey?,
        target: VehicleAdvertisement? = null,
        options: ConnectOptions = ConnectOptions(),
    ): VehicleResult<Vehicle> {
        val transport =
            when (val result = transports.connect(vin, target, options.copy(timeout = options.timeout ?: config.connectTimeout))) {
                is VehicleResult.Success -> result.value
                is VehicleResult.Failure -> return result
                is VehicleResult.Uncertain -> return result
            }
        val scope = vehicleScope(vin)
        val dispatcher = Dispatcher(transport, key, crypto, random, scope, timeSource, logger)
        val sync = key?.let { SessionCacheSync(sessionCache, vin, KeyId.of(it.publicKey, crypto)) }
        val timeouts = CommandTimeouts(config.commandTimeout, config.commandLifetime, config.handshakeTimeout)
        val session = VehicleSession(dispatcher, timeouts, sync, scope)
        val restored = session.connect().mapNotNull { VehicleDomain.of(it) }.toSet()
        logger.log(LogLevel.INFO, TAG) { "Connected to $vin (restored ${restored.size} session(s) from cache)" }
        return VehicleResult.Success(DefaultVehicle(vin, key != null, transport, session, restored, scope, config))
    }

    /** 이 인스턴스가 만든 백그라운드 작업을 멈춘다. 연결된 차량은 먼저 `disconnect()`한다. */
    public fun close() {
        ownScope?.cancel()
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
