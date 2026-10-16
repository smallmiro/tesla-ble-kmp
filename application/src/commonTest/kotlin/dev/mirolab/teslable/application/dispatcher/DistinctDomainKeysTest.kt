package dev.mirolab.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import dev.mirolab.teslable.application.vehicle.VehicleSession
import dev.mirolab.teslable.model.VehicleResult
import dev.mirolab.teslable.port.AuthMethod
import dev.mirolab.teslable.testing.FakeVehicle
import dev.mirolab.teslable.testing.TestCrypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DistinctDomainKeysTest {
    @Test
    fun handshakesBothDomainsWhenEachDomainHasItsOwnVehicleKey() =
        // dispatcher_test.go:126 — Go 테스트 구성(도메인마다 다른 차량 키)을 포팅한다. 도메인 간 세션정보 혼동 거부를 증명하는 테스트는 아니다.
        runTest {
            val fake = FakeVehicle(timeSource = testTimeSource, infotainmentKey = TestCrypto.goKnownVerifierKey())
            val h = dispatcherHarness(fake = fake)
            val session = VehicleSession(h.dispatcher)
            assertIs<VehicleResult.Success<Unit>>(session.startSession(timeout = 1.seconds))
            assertNotEquals(
                fake.verifier(Domain.DOMAIN_VEHICLE_SECURITY).sessionInfo().publicKey,
                fake.verifier(Domain.DOMAIN_INFOTAINMENT).sessionInfo().publicKey,
            )
            assertIs<VehicleResult.Success<*>>(session.send.send(Domain.DOMAIN_INFOTAINMENT, "x".encodeToByteArray(), AuthMethod.GCM))
        }
}
