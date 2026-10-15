package io.github.smallmiro.teslable.application.dispatcher

import com.tesla.generated.universalmessage.Domain
import io.github.smallmiro.teslable.application.vehicle.VehicleSession
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.port.AuthMethod
import io.github.smallmiro.teslable.testing.FakeVehicle
import io.github.smallmiro.teslable.testing.TestCrypto
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
        // dispatcher_test.go:126 — Go 테스트는 도메인마다 다른 차량 키를 쓴다(M2 인계: 같은 키면 도메인 간 세션정보 혼동이 HMAC을 통과할 수 있다)
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
