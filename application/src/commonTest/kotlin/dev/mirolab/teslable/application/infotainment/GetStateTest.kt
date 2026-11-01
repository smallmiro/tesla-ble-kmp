package dev.mirolab.teslable.application.infotainment

import com.tesla.generated.carserver.server.Action
import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.GetChargeScheduleState
import com.tesla.generated.carserver.server.GetChargeState
import com.tesla.generated.carserver.server.GetClimateState
import com.tesla.generated.carserver.server.GetClosuresState
import com.tesla.generated.carserver.server.GetDriveState
import com.tesla.generated.carserver.server.GetLocationState
import com.tesla.generated.carserver.server.GetMediaDetailState
import com.tesla.generated.carserver.server.GetMediaState
import com.tesla.generated.carserver.server.GetParentalControlsState
import com.tesla.generated.carserver.server.GetPreconditioningScheduleState
import com.tesla.generated.carserver.server.GetSoftwareUpdateState
import com.tesla.generated.carserver.server.GetTirePressureState
import com.tesla.generated.carserver.server.GetVehicleData
import com.tesla.generated.carserver.server.OperationStatus_E
import com.tesla.generated.carserver.server.Ping
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.ResultReason
import com.tesla.generated.carserver.vehicle.ChargeState
import com.tesla.generated.carserver.vehicle.ClimateState
import com.tesla.generated.carserver.vehicle.DriveState
import com.tesla.generated.carserver.vehicle.VehicleData
import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.dispatcher.DispatcherHarness
import dev.mirolab.teslable.application.dispatcher.dispatcherHarness
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.StateCategory
import dev.mirolab.teslable.model.VehicleError
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.testing.FakeVehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GetStateTest {
    private val infotainment = Domain.DOMAIN_INFOTAINMENT

    /** 종류 하나의 요청. [fieldNumber]는 `car_server.proto` `GetVehicleData`의 번호(`03-protocol.md` §13). */
    private class Selector(
        val category: StateCategory,
        val fieldNumber: Int,
        val message: GetVehicleData,
    )

    /** Go `submessage()`의 대응표(state.go:39-52). Go 상수 순서. */
    private val selectors =
        listOf(
            Selector(StateCategory.CHARGE, 2, GetVehicleData(getChargeState = GetChargeState())),
            Selector(StateCategory.CLIMATE, 3, GetVehicleData(getClimateState = GetClimateState())),
            Selector(StateCategory.DRIVE, 4, GetVehicleData(getDriveState = GetDriveState())),
            Selector(StateCategory.LOCATION, 7, GetVehicleData(getLocationState = GetLocationState())),
            Selector(StateCategory.CLOSURES, 8, GetVehicleData(getClosuresState = GetClosuresState())),
            Selector(StateCategory.CHARGE_SCHEDULE, 10, GetVehicleData(getChargeScheduleState = GetChargeScheduleState())),
            Selector(
                StateCategory.PRECONDITIONING_SCHEDULE,
                11,
                GetVehicleData(getPreconditioningScheduleState = GetPreconditioningScheduleState()),
            ),
            Selector(StateCategory.TIRE_PRESSURE, 14, GetVehicleData(getTirePressureState = GetTirePressureState())),
            Selector(StateCategory.MEDIA, 15, GetVehicleData(getMediaState = GetMediaState())),
            Selector(StateCategory.MEDIA_DETAIL, 16, GetVehicleData(getMediaDetailState = GetMediaDetailState())),
            Selector(StateCategory.SOFTWARE_UPDATE, 17, GetVehicleData(getSoftwareUpdateState = GetSoftwareUpdateState())),
            Selector(StateCategory.PARENTAL_CONTROLS, 19, GetVehicleData(getParentalControlsState = GetParentalControlsState())),
        )

    /**
     * `Action{vehicleAction(2){getVehicleData(1){선택자{}}}}`의 protobuf 바이트. 차례로 0x12, 길이, 0x0a, 길이, 선택자 태그, 0x00이다
     * (`03-protocol.md` §8.2.1의 `120452020801`과 같은 구조). 태그 `(번호 << 3) | 2`는 번호 16부터 varint 2바이트라 길이도 1씩 늘어난다.
     */
    private fun actionBytes(fieldNumber: Int): ByteArray {
        val tag = (fieldNumber shl 3) or 2
        val selector =
            if (tag < 0x80) {
                byteArrayOf(tag.toByte(), 0)
            } else {
                byteArrayOf((tag and 0x7f or 0x80).toByte(), (tag shr 7).toByte(), 0)
            }
        return byteArrayOf(0x12, (selector.size + 2).toByte(), 0x0a, selector.size.toByte()) + selector
    }

    private suspend fun connected(h: DispatcherHarness): VehicleSession {
        val session = VehicleSession(h.dispatcher)
        assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
        return session
    }

    private fun replyWith(data: VehicleData): List<FakeVehicle.ScriptedReply> =
        listOf(FakeVehicle.infotainmentPayload(Response(vehicleData = data)))

    private suspend fun VehicleSession.state(category: StateCategory): VehicleData =
        assertIs<VehicleResult.Success<VehicleData>>(infotainment.getState(category)).value

    @Test
    fun chargeRoundTripsThroughTheEncryptedPath() =
        // state.go GetState + infotainment.go getCarServerResponse: AES-GCM 요청(authenticatedPayloads는 검증된 평문만 담는다) + 암호화 응답 요청
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val charge = ChargeState(battery_level = 72)
            h.fake.script(infotainment, replyWith(VehicleData(charge_state = charge)))

            val data = session.state(StateCategory.CHARGE)

            val sent = Action.ADAPTER.decode(h.fake.authenticatedPayloads(infotainment).single())
            assertNotNull(sent.vehicleAction?.getVehicleData?.getChargeState)
            val request = h.fake.received.last()
            assertEquals(FakeVehicle.FLAG_ENCRYPT_RESPONSE, request.flags)
            assertEquals(charge, data.charge_state)
        }

    @Test
    fun everyCategorySelectsExactlyOneSubmessage() =
        // state.go:39-52. 한 필드만 채운 메시지와 equals로 같으면 TESLEMETRY-EXT 선택자를 포함한 나머지 필드는 전부 null이다
        runTest {
            assertEquals(StateCategory.entries, selectors.map { it.category }) // 빠진 종류 없음, Go 상수 순서(SDD §7.3)
            val h = dispatcherHarness()
            val session = connected(h)

            for (selector in selectors) session.state(selector.category)

            val payloads = h.fake.authenticatedPayloads(infotainment)
            assertEquals(selectors.size, payloads.size) // 종류 하나에 요청 하나(FR-033)
            for ((selector, payload) in selectors.zip(payloads)) {
                val sent = Action.ADAPTER.decode(payload)
                assertEquals(selector.message, sent.vehicleAction?.getVehicleData, "${selector.category}")
                assertContentEquals(actionBytes(selector.fieldNumber), payload, "${selector.category}")
            }
        }

    @Test
    fun explicitZeroAndAbsentFieldsStayDistinct() =
        // vehicle.proto DriveState: speed는 oneof optional_speed라 0과 없음이 구분된다. 생성 타입을 그대로 돌려줘야 그 구분이 남는다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(
                infotainment,
                replyWith(VehicleData(drive_state = DriveState(speed = 0))),
                replyWith(VehicleData(drive_state = DriveState())),
            )

            val stopped = assertNotNull(session.state(StateCategory.DRIVE).drive_state)
            val absent = assertNotNull(session.state(StateCategory.DRIVE).drive_state)

            assertEquals(0, stopped.speed)
            assertNull(absent.speed)
            assertNull(absent.speed_float)
        }

    @Test
    fun replyWithoutVehicleDataIsAnEmptySuccess() =
        // Go GetState는 (nil, nil)을 돌려준다. M5 계획 결정 기록: Success(VehicleData()). 페이로드 없는 응답은 빈 Response다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(
                infotainment,
                listOf(FakeVehicle.infotainmentPayload(Response(ping = Ping(ping_id = 1)))),
                listOf(FakeVehicle.ScriptedReply()),
            )

            assertEquals(VehicleResult.Success(VehicleData()), session.infotainment.getState(StateCategory.CHARGE))
            assertEquals(VehicleResult.Success(VehicleData()), session.infotainment.getState(StateCategory.CHARGE))
        }

    @Test
    fun otherCategoryInTheReplyPassesThrough() =
        // Go는 응답의 내용을 요청한 종류와 맞춰 보지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val climate = ClimateState(inside_temp_celsius = 21.5f)
            h.fake.script(infotainment, replyWith(VehicleData(climate_state = climate)))

            val data = session.state(StateCategory.CHARGE)

            assertEquals(climate, data.climate_state)
            assertNull(data.charge_state)
        }

    @Test
    fun rejectionWinsOverData() =
        // infotainment.go:37-43: actionStatus가 ERROR면 응답을 돌려주기 전에 NominalError로 끝난다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            val rejected =
                Response(
                    actionStatus =
                        ActionStatus(
                            result = OperationStatus_E.OPERATIONSTATUS_ERROR,
                            result_reason = ResultReason(plain_text = "car is in drive"),
                        ),
                    vehicleData = VehicleData(charge_state = ChargeState(battery_level = 72)),
                )
            h.fake.script(infotainment, listOf(FakeVehicle.infotainmentPayload(rejected)))

            val result = session.infotainment.getState(StateCategory.CHARGE)

            assertEquals(VehicleResult.Failure(VehicleError.InfotainmentRejected("car is in drive")), result)
        }

    @Test
    fun undecodableReplyIsUncertainBadResponse() =
        // infotainment.go:33-35: CommandError{PossibleSuccess: true}. 실행됐을 수 있으므로 다시 보내지 않는다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(infotainment, listOf(FakeVehicle.ScriptedReply(payload = byteArrayOf(0xFF.toByte()))))

            val result = assertIs<VehicleResult.Uncertain>(session.infotainment.getState(StateCategory.CHARGE))

            assertTrue(assertIs<VehicleError.BadResponse>(result.error).mayHaveSucceeded)
            assertEquals(1, h.fake.authenticatedPayloads(infotainment).size)
        }

    @Test
    fun explicitTimeoutBoundsTheWait() =
        // 응답이 없으면 넘겨준 시간에 끝난다. 인자를 execute로 넘기지 않으면 기본 명령 시간(5초)까지 기다리게 된다
        runTest {
            val h = dispatcherHarness()
            val session = connected(h)
            h.fake.script(infotainment, emptyList())
            val start = testTimeSource.markNow()

            val result = session.infotainment.getState(StateCategory.CHARGE, timeout = 100.milliseconds)

            assertEquals(VehicleResult.Uncertain(VehicleError.Timeout(afterSend = true)), result)
            assertEquals(100.milliseconds, start.elapsedNow())
        }
}
