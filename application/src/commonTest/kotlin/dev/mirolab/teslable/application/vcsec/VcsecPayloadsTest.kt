package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.keys.Role
import com.tesla.generated.vcsec.ClosureMoveRequest
import com.tesla.generated.vcsec.ClosureMoveType_E
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.SignatureType
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistEntryInfo
import dev.mirolab.teslable.InternalTeslableApi
import dev.mirolab.teslable.model.KeyFormFactor
import dev.mirolab.teslable.model.KeyRole
import dev.mirolab.teslable.testing.TestCrypto
import dev.mirolab.teslable.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.tesla.generated.vcsec.KeyFormFactor as WireFormFactor

@OptIn(InternalTeslableApi::class)
class VcsecPayloadsTest {
    private val key = TestCrypto.clientPublicKey

    @Test
    fun rkeActionsMatchGoBytes() {
        // vcsec.go:174-195 executeRKEAction: UnsignedMessage{RKEAction} (필드 2, oneof — 0값도 싣는다)
        assertEquals("1000", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_UNLOCK).toHex())
        assertEquals("1001", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_LOCK).toHex())
        assertEquals("1014", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_REMOTE_DRIVE).toHex())
        assertEquals("101d", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_AUTO_SECURE_VEHICLE).toHex())
        assertEquals("101e", VcsecPayloads.rke(RKEAction_E.RKE_ACTION_WAKE_VEHICLE).toHex())
    }

    @Test
    fun closureMovesMatchGoBytes() {
        // vcsec.go:217-248 executeClosureAction: trunk→rearTrunk(5), frunk→frontTrunk(6), tonneau→tonneau(8)
        fun hex(r: ClosureMoveRequest) = VcsecPayloads.closure(r).toHex()
        assertEquals("22022801", hex(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE)))
        assertEquals("22022804", hex(ClosureMoveRequest(rearTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE)))
        assertEquals("22023001", hex(ClosureMoveRequest(frontTrunk = ClosureMoveType_E.CLOSURE_MOVE_TYPE_MOVE)))
        assertEquals("22024003", hex(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_OPEN)))
        assertEquals("22024004", hex(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_CLOSE)))
        assertEquals("22024002", hex(ClosureMoveRequest(tonneau = ClosureMoveType_E.CLOSURE_MOVE_TYPE_STOP)))
    }

    @Test
    fun removeKeyMatchesGoBytes() {
        // security.go:268-282: UnsignedMessage{WhitelistOperation(16){removePublicKeyFromWhitelist(2){PublicKeyRaw(1)}}}
        assertEquals("82014512430a41" + key.toByteArray().toHex(), VcsecPayloads.removeKey(key).toHex())
    }

    @Test
    fun addKeyCarriesKeyRoleAndFormFactorFieldByField() {
        // vcsec.go:151-172 addKeyPayload. Go는 oneof를 뒤에 놓을 수 있어 바이트가 아니라 필드로 비교한다(로드맵 공통 규칙)
        val decoded = UnsignedMessage.ADAPTER.decode(VcsecPayloads.addKey(key, Role.ROLE_OWNER, WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE))
        val op = checkNotNull(decoded.VCSEC_WhitelistOperation)
        val change = checkNotNull(op.addKeyToWhitelistAndAddPermissions)
        assertEquals(key.toByteArray().toHex(), checkNotNull(change.key).PublicKeyRaw.hex())
        assertEquals(Role.ROLE_OWNER, change.keyRole)
        assertEquals(0, change.secondsToBeActive)
        assertEquals(WireFormFactor.KEY_FORM_FACTOR_IOS_DEVICE, op.metadataForKey?.keyFormFactor)
    }

    @Test
    fun presentKeyEnvelopeIsAToVcsecMessageNotARoutableMessage() {
        // security.go:345-356: ToVCSECMessage{SignedMessage{protobufMessageAsBytes, SIGNATURE_TYPE_PRESENT_KEY}}
        val inner = VcsecPayloads.addKey(key, Role.ROLE_DRIVER, WireFormFactor.KEY_FORM_FACTOR_ANDROID_DEVICE)
        val envelope = ToVCSECMessage.ADAPTER.decode(VcsecPayloads.presentKeyEnvelope(inner))
        val signed = checkNotNull(envelope.signedMessage)
        assertEquals(SignatureType.SIGNATURE_TYPE_PRESENT_KEY, signed.signatureType)
        assertEquals(inner.toHex(), signed.protobufMessageAsBytes.hex())
    }

    @Test
    fun modelRolesAndFormFactorsMapToWire() {
        assertEquals(Role.ROLE_OWNER, VcsecInfoMapping.wireRole(KeyRole.OWNER))
        assertEquals(Role.ROLE_FM, VcsecInfoMapping.wireRole(KeyRole.FLEET_MANAGER))
        assertNull(VcsecInfoMapping.wireRole(KeyRole.UNKNOWN))
        assertEquals(WireFormFactor.KEY_FORM_FACTOR_CLOUD_KEY, VcsecInfoMapping.wireFormFactor(KeyFormFactor.CLOUD_KEY))
        assertEquals(WireFormFactor.KEY_FORM_FACTOR_UNKNOWN, VcsecInfoMapping.wireFormFactor(KeyFormFactor.UNKNOWN))
        for (role in KeyRole.entries - KeyRole.UNKNOWN) {
            assertEquals(role.name.replace("FLEET_MANAGER", "FM"), checkNotNull(VcsecInfoMapping.wireRole(role)).name.removePrefix("ROLE_"))
        }
    }

    @Test
    fun snapshotOnlyRolesAndFormFactorsStayUnknownInTheModel() {
        // ADR-0002 개정(2026-10-18): 스냅샷의 TESLEMETRY-EXT 값(Role 9 RIDER, KeyFormFactor 14 NFC_CARD_P71)은
        // 공개 모델 값이 없으므로 교체 전(unknownFields 경로)과 같이 UNKNOWN이다.
        // metadataForKey(4){ keyFormFactor(1) = 14 }, slot(6) = 3, keyRole(7) = 9
        val raw = byteArrayOf(0x22, 0x02, 0x08, 0x0e, 0x30, 0x03, 0x38, 0x09)
        val entry = VcsecInfoMapping.entry(WhitelistEntryInfo.ADAPTER.decode(raw))
        assertEquals(3, entry.slot)
        assertEquals(KeyRole.UNKNOWN, entry.role)
        assertEquals(KeyFormFactor.UNKNOWN, entry.formFactor)
    }
}
