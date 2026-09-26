package io.github.smallmiro.teslable.protocol

import com.tesla.generated.signatures.AES_GCM_Personalized_Signature_Data
import com.tesla.generated.signatures.HMAC_Personalized_Signature_Data
import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.signatures.SignatureType
import com.tesla.generated.universalmessage.Destination
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RequestHashTest {
    private val tag = ByteArray(19) { it.toByte() }

    @Test
    fun truncatesHmacTagTo16BytesForVcsec() { // peer_test.go TestRequestID
        val id = RequestHash.of(SignatureType.SIGNATURE_TYPE_HMAC_PERSONALIZED, tag, Domain.DOMAIN_VEHICLE_SECURITY)
        assertEquals(17, id.size)
        assertEquals(SignatureType.SIGNATURE_TYPE_HMAC_PERSONALIZED.value.toByte(), id[0])
        assertContentEquals(tag.copyOf(16), id.copyOfRange(1, 17))
    }

    @Test
    fun keepsFullHmacTagForInfotainment() {
        val id = RequestHash.of(SignatureType.SIGNATURE_TYPE_HMAC_PERSONALIZED, tag, Domain.DOMAIN_INFOTAINMENT)
        assertEquals(20, id.size)
        assertContentEquals(tag, id.copyOfRange(1, 20))
    }

    @Test
    fun keepsFullGcmTagForAnyDomain() {
        val id = RequestHash.of(SignatureType.SIGNATURE_TYPE_AES_GCM_PERSONALIZED, tag, Domain.DOMAIN_VEHICLE_SECURITY)
        assertEquals(20, id.size)
        assertEquals(0x05.toByte(), id[0])
    }

    @Test
    fun extractsFromRoutableMessage() {
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                signature_data = SignatureData(HMAC_Personalized_data = HMAC_Personalized_Signature_Data(tag = tag.toByteString())),
            )
        assertEquals(17, RequestHash.of(message)!!.size)
        assertNull(RequestHash.of(RoutableMessage()))
    }

    @Test
    fun rejectsShortHmacTagForVcsec() {
        assertFailsWith<IllegalArgumentException> {
            RequestHash.of(SignatureType.SIGNATURE_TYPE_HMAC_PERSONALIZED, ByteArray(8), Domain.DOMAIN_VEHICLE_SECURITY)
        }
    }

    @Test
    fun extractsGcmHashFromRoutableMessage() {
        val gcmTag = ByteArray(16) { it.toByte() }
        val message =
            RoutableMessage(
                to_destination = Destination(domain = Domain.DOMAIN_VEHICLE_SECURITY),
                signature_data =
                    SignatureData(
                        AES_GCM_Personalized_data = AES_GCM_Personalized_Signature_Data(tag = gcmTag.toByteString()),
                    ),
            )
        val id = RequestHash.of(message)!!
        assertEquals(17, id.size)
        assertEquals(0x05.toByte(), id[0])
        assertContentEquals(gcmTag, id.copyOfRange(1, 17))
    }

    @Test
    fun defaultsToBroadcastWhenDestinationMissing() {
        val message =
            RoutableMessage(
                signature_data = SignatureData(HMAC_Personalized_data = HMAC_Personalized_Signature_Data(tag = tag.toByteString())),
            )
        val id = RequestHash.of(message)!!
        assertEquals(20, id.size)
        assertContentEquals(tag, id.copyOfRange(1, 20))
    }

    @Test
    fun returnsNullForUnsupportedSignatureType() {
        val message = RoutableMessage(signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = tag.toByteString())))
        assertNull(RequestHash.of(message))
    }
}
