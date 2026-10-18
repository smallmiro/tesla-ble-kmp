// Ported from vehicle-command@a4b43c1 pkg/vehicle/vcsec.go (Apache-2.0)
// addKeyPayload, executeRKEAction, executeClosureAction payloads; security.go RemoveKey, SendAddKeyRequestWithRole envelope
package dev.mirolab.teslable.application.vcsec

import com.tesla.generated.keys.Role
import com.tesla.generated.vcsec.ClosureMoveRequest
import com.tesla.generated.vcsec.KeyFormFactor
import com.tesla.generated.vcsec.KeyMetadata
import com.tesla.generated.vcsec.PermissionChange
import com.tesla.generated.vcsec.PublicKey
import com.tesla.generated.vcsec.RKEAction_E
import com.tesla.generated.vcsec.SignatureType
import com.tesla.generated.vcsec.SignedMessage
import com.tesla.generated.vcsec.ToVCSECMessage
import com.tesla.generated.vcsec.UnsignedMessage
import com.tesla.generated.vcsec.WhitelistOperation
import dev.mirolab.teslable.model.PublicKeyBytes
import okio.ByteString.Companion.toByteString

/** VCSEC 요청 페이로드(`UnsignedMessage` 바이트)와 add-key-request 봉투. 필드 하나짜리는 Go와 바이트가 같다(`VcsecPayloadsTest`). */
internal object VcsecPayloads {
    /** Go `executeRKEAction`: `UnsignedMessage{RKEAction}`. */
    fun rke(action: RKEAction_E): ByteArray = UnsignedMessage(RKEAction = action).encode()

    /** Go `executeClosureAction`: `UnsignedMessage{closureMoveRequest}`. */
    fun closure(request: ClosureMoveRequest): ByteArray = UnsignedMessage(closureMoveRequest = request).encode()

    /** Go `addKeyPayload`: `addKeyToWhitelistAndAddPermissions{key, keyRole}` + `metadataForKey{keyFormFactor}`. */
    fun addKey(
        publicKey: PublicKeyBytes,
        role: Role,
        formFactor: KeyFormFactor,
    ): ByteArray =
        UnsignedMessage(
            VCSEC_WhitelistOperation =
                WhitelistOperation(
                    addKeyToWhitelistAndAddPermissions = PermissionChange(key = wireKey(publicKey), keyRole = role),
                    metadataForKey = KeyMetadata(keyFormFactor = formFactor),
                ),
        ).encode()

    /** Go `RemoveKey`: `removePublicKeyFromWhitelist{PublicKeyRaw}`. */
    fun removeKey(publicKey: PublicKeyBytes): ByteArray =
        UnsignedMessage(VCSEC_WhitelistOperation = WhitelistOperation(removePublicKeyFromWhitelist = wireKey(publicKey))).encode()

    /** Go `SendAddKeyRequestWithRole`의 봉투: RoutableMessage가 아닌 `ToVCSECMessage{SignedMessage{PRESENT_KEY}}`(인증 없음). */
    fun presentKeyEnvelope(unsignedPayload: ByteArray): ByteArray =
        ToVCSECMessage(
            signedMessage =
                SignedMessage(
                    protobufMessageAsBytes = unsignedPayload.toByteString(),
                    signatureType = SignatureType.SIGNATURE_TYPE_PRESENT_KEY,
                ),
        ).encode()

    private fun wireKey(publicKey: PublicKeyBytes): PublicKey = PublicKey(PublicKeyRaw = publicKey.toByteArray().toByteString())
}
