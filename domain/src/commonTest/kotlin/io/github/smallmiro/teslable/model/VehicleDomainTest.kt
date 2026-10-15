package io.github.smallmiro.teslable.model

import com.tesla.generated.universalmessage.Domain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VehicleDomainTest {
    @Test
    fun mapsToAndFromWireDomains() {
        assertEquals(Domain.DOMAIN_VEHICLE_SECURITY, VehicleDomain.VCSEC.wire)
        assertEquals(Domain.DOMAIN_INFOTAINMENT, VehicleDomain.INFOTAINMENT.wire)
        assertEquals(VehicleDomain.VCSEC, VehicleDomain.of(Domain.DOMAIN_VEHICLE_SECURITY))
        assertEquals(VehicleDomain.INFOTAINMENT, VehicleDomain.of(Domain.DOMAIN_INFOTAINMENT))
        assertNull(VehicleDomain.of(Domain.DOMAIN_BROADCAST))
        assertEquals(setOf(VehicleDomain.VCSEC, VehicleDomain.INFOTAINMENT), VehicleDomain.ALL)
    }
}
