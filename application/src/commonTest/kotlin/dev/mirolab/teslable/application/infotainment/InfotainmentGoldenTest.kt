package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.common.Void
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.application.dispatcher.Dispatcher
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.SendWithRetry
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.cache.CachedSession
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.model.Vin
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.FixedRandom
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.testing.fixtures.InfotainmentVectors
import dev.mirolab.teslable.testing.fixtures.ProtocolVectors
import dev.mirolab.teslable.util.hexToBytes
import dev.mirolab.teslable.util.toHex
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Infotainment 골든 벡터(M5 계획 Task A6, NFR-002). **둘 다 합성 벡터다**: climate-on은 Go `pkg/protocol/protocol.md` §8.2.1 "Turn HVAC on"
 * 예제(03-protocol.md §8.2.1, 공개 테스트 키·고정 nonce)이고, state charge는 손으로 인코딩한 값이다. 실차 캡처 골든은 사용자 실차 항목(#118)으로
 * 남아 있다 — 차량 키·epoch가 연결마다 달라 VIN만 바꿔서는 암호 캡처를 재생할 수 없다.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalTeslableApi::class)
class InfotainmentGoldenTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    @Test
    fun climateOnReproducesTheProtocolDocEncryptedMessage() =
        // 명령 빌더 → SendWithRetry → Dispatcher 조립 → Signer.encrypt 전체를 지나 protocol.md의 결과 메시지와 같아야 한다.
        // 주입: VIN, 공개 테스트 키(K = 1b2f…), 핸드셰이크 session_info(counter 6, epoch 4c46…, clock_time 2650)를 age 0으로 복원
        // (다음 counter 7, expires_at 2650 + commandLifetime 5초 = 2655), 디스패처 난수 = routing_address → uuid → nonce 순서.
        // 차량 쪽 검증자의 epoch도 같은 값으로 고정해 이 바이트가 검증을 통과하는지까지 본다
        runTest {
            val fake =
                FakeVehicle(
                    vin = Vin(ProtocolVectors.VIN),
                    random = FixedRandom(ProtocolVectors.EPOCH.hexToBytes(), fallback = TestCrypto.random),
                    timeSource = testTimeSource,
                )
            val transport = fake.transport()
            val random =
                FixedRandom(
                    ProtocolVectors.HVAC_ROUTING_ADDRESS.hexToBytes(),
                    ProtocolVectors.HVAC_UUID.hexToBytes(),
                    ProtocolVectors.HVAC_NONCE.hexToBytes(),
                    fallback = TestCrypto.random,
                )
            val dispatcher = Dispatcher(transport, TestCrypto.clientKey(), TestCrypto.primitives, random, backgroundScope, testTimeSource)
            dispatcher.start()
            val restored = CachedSession(infotainment, ProtocolVectors.SESSION_INFO.hexToBytes(), age = Duration.ZERO)
            assertEquals(setOf(infotainment), dispatcher.loadSessions(listOf(restored)))

            assertEquals(VehicleResult.Success(Unit), InfotainmentCommands(SendWithRetry(dispatcher)).climateOn())

            val frame = transport.sent.single()
            val sent = RoutableMessage.ADAPTER.decode(frame)
            assertEquals(ProtocolVectors.HVAC_CIPHERTEXT, assertNotNull(sent.protobuf_message_as_bytes).hex())
            assertEquals(ProtocolVectors.HVAC_TAG_FLAGS2, assertNotNull(sent.signature_data?.AES_GCM_Personalized_data).tag.hex())
            assertEquals(RoutableMessage.ADAPTER.decode(ProtocolVectors.HVAC_ON_TX.hexToBytes()), sent) // 필드마다 같다(순서만 다르다)
            assertEquals(185, ProtocolVectors.HVAC_ON_TX.length / 2)
            assertEquals(ProtocolVectors.HVAC_ON_TX.length / 2, frame.size) // 같은 필드, 같은 길이 — 기본값 필드나 모르는 필드가 끼지 않았다
            assertEquals(listOf(ProtocolVectors.HVAC_ON_PLAINTEXT), fake.authenticatedPayloads(infotainment).map { it.toHex() })
        }

    @Test
    fun stateChargeRequestPlaintextAndSyntheticResponseDecode() =
        // state.go GetState(StateCategoryCharge)의 요청 평문을 고정하고, 손으로 인코딩한 응답을 암호화 경로로 받아 VehicleData로 푼다
        runTest {
            val h = dispatcherHarness()
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(setOf(infotainment), timeout = 1.seconds))
            h.fake.script(infotainment, listOf(FakeVehicle.ScriptedReply(payload = InfotainmentVectors.STATE_CHARGE_RESPONSE.hexToBytes())))

            val data = assertIs<VehicleResult.Success<VehicleData>>(session.infotainment.getState(StateCategory.CHARGE)).value

            assertEquals(listOf(InfotainmentVectors.STATE_CHARGE_REQUEST), h.fake.authenticatedPayloads(infotainment).map { it.toHex() })
            val expected =
                ChargeState(
                    charging_state = ChargeState.ChargingState(Charging = Void()),
                    charge_limit_soc = 80,
                    battery_range = 210.5f,
                    battery_level = 72,
                    charger_power = 0,
                    charging_amps = 16,
                )
            assertEquals(VehicleData(charge_state = expected), data)
            val charge = assertNotNull(data.charge_state)
            assertEquals(0, charge.charger_power) // 차량이 보낸 0은 0이다
            assertNull(charge.usable_battery_level) // 보내지 않은 필드는 null로 남는다
        }
}
