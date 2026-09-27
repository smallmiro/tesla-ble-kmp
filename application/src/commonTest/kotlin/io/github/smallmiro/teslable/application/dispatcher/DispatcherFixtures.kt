// Task 6 브리프: 하네스(DispatcherHarness)와 헬퍼 함수를 한 파일에 모아 Task 7~11이 재사용한다(단일 top-level 선언 규칙 예외).
@file:Suppress("MatchingDeclarationName")

package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.testing.FakeTransport
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.RecordingLogger
import io.github.smallmiro.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.testTimeSource
import okio.ByteString.Companion.toByteString
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Go `getTestSetup`의 결과물. `dispatcher`는 `backgroundScope`에서 수신한다(테스트가 끝나면 자동 취소). */
internal class DispatcherHarness(
    val fake: FakeVehicle,
    val transport: FakeTransport,
    val dispatcher: Dispatcher,
    val logger: RecordingLogger,
)

/** Go `getTestSetup` 앞부분(생성 + Start). 핸드셰이크는 `manualHandshake` 또는 Task 7 `HandshakeFlow`로. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.dispatcherHarness(
    privateKey: EcdhPrivateKey? = TestCrypto.clientKey(),
    fake: FakeVehicle = FakeVehicle(timeSource = testTimeSource),
    start: Boolean = true,
    retryInterval: Duration = 1.milliseconds,
): DispatcherHarness {
    val transport = fake.transport(retryInterval)
    val logger = RecordingLogger()
    val dispatcher = Dispatcher(transport, privateKey, TestCrypto.primitives, TestCrypto.random, backgroundScope, testTimeSource, logger)
    if (start) dispatcher.start()
    return DispatcherHarness(fake, transport, dispatcher, logger)
}

/** dispatcher_test.go `testCommand()`: INFOTAINMENT, payload "hello". */
internal fun testCommand(
    domain: Domain = Domain.DOMAIN_INFOTAINMENT,
    payload: String = "hello",
    flags: Int = 0,
): RoutableMessage =
    RoutableMessage(
        to_destination = Destination(domain = domain),
        protobuf_message_as_bytes = payload.encodeToByteArray().toByteString(),
        flags = flags,
    )

/** dispatcher_test.go `replyWithPayload` + `populateReplyMetadata`: 요청(디스패처가 조립한 뒤의 것)에 대한 평문 응답. */
internal fun replyTo(
    request: RoutableMessage,
    payload: ByteArray,
): RoutableMessage =
    RoutableMessage(
        to_destination = request.from_destination,
        from_destination = Destination(domain = request.to_destination?.domain),
        request_uuid = request.uuid,
        uuid = FakeVehicle.TEST_UUID.toByteString(),
        protobuf_message_as_bytes = payload.toByteString(),
    )

internal fun encode(message: RoutableMessage): ByteArray = RoutableMessage.ADAPTER.encode(message)

/** Task 7 이전의 수동 핸드셰이크: 세션정보 요청 → FakeVehicle 응답이 수신 루프에서 processHello된 뒤 채널로 온다. */
internal suspend fun DispatcherHarness.manualHandshake(domain: Domain) {
    val pending = assertIs<VehicleResult.Success<PendingRequest>>(dispatcher.requestSessionInfo(domain)).value
    pending.use { assertNotNull(it.receive().session_info) }
    assertTrue(assertNotNull(dispatcher.session(domain)).isReady, "session for $domain must be ready")
}
