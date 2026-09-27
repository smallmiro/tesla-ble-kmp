// Ported from vehicle-command@a4b43c1 internal/dispatcher/dispatcher_test.go (Apache-2.0)
// dummyConnector, handleSessionInfoRequests, initReply, testUUID
package io.github.smallmiro.teslable.testing

import com.tesla.generated.carserver.server.ActionStatus
import com.tesla.generated.carserver.server.Response
import com.tesla.generated.carserver.server.ResultReason
import com.tesla.generated.errors.GenericError_E
import com.tesla.generated.errors.NominalError
import com.tesla.generated.signatures.HMAC_Signature_Data
import com.tesla.generated.signatures.SignatureData
import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.MessageFault_E
import com.tesla.generated.universalmessage.MessageStatus
import com.tesla.generated.universalmessage.OperationStatus_E
import com.tesla.generated.universalmessage.RoutableMessage
import com.tesla.generated.vcsec.CommandStatus
import com.tesla.generated.vcsec.FromVCSECMessage
import com.tesla.generated.vcsec.SignedMessage_status
import com.tesla.generated.vcsec.WhitelistOperation_information_E
import com.tesla.generated.vcsec.WhitelistOperation_status
import io.github.smallmiro.teslable.InternalTeslableApi
import io.github.smallmiro.teslable.model.PublicKeyBytes
import io.github.smallmiro.teslable.model.VehicleError
import io.github.smallmiro.teslable.model.VehicleResult
import io.github.smallmiro.teslable.model.Vin
import io.github.smallmiro.teslable.port.CryptoPrimitives
import io.github.smallmiro.teslable.port.EcdhPrivateKey
import io.github.smallmiro.teslable.port.RandomSource
import io.github.smallmiro.teslable.protocol.RequestHash
import io.github.smallmiro.teslable.protocol.decodeOrNull
import okio.ByteString.Companion.toByteString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import com.tesla.generated.carserver.server.OperationStatus_E as CarServerOperationStatus
import com.tesla.generated.vcsec.OperationStatus_E as VcsecOperationStatus

/**
 * 결정적 가짜 차량(SDD §9.3). 도메인마다 [TestVerifier] 하나를 감싸 `verifier.go`의 GCM 경로로 명령을 검증하고, 응답을 대본대로
 * 돌려준다. Go `dummyConnector`는 세션정보 요청만 처리하지만 여기서는 인증 명령도 검증한다(NFR-003 시나리오). 시간은 [timeSource]로만
 * 흐르고 `runTest` 단일 스레드 전용이다(스레드 안전하지 않다). 응답은 [FakeTransport.send] 안에서 동기로 만들어 [FakeTransport.deliver]로 넣는다.
 */
@OptIn(InternalTeslableApi::class)
@Suppress("TooManyFunctions") // dummyConnector 대본 API + verifier.go 위임 메서드와 1:1 대응
public class FakeVehicle(
    /** 차량 VIN. 기본은 Go `dummyConnector.VIN()`. */
    public val vin: Vin = Vin(FakeTransport.DEFAULT_VIN),
    private val vehicleKey: EcdhPrivateKey = TestCrypto.vehicleKey(),
    private val crypto: CryptoPrimitives = TestCrypto.primitives,
    private val random: RandomSource = TestCrypto.random,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /** 대본 응답 하나. [payload]가 null이면 payload 없는 메시지(VCSEC "빈 메시지 = 성공"). */
    public class ScriptedReply(
        payload: ByteArray? = null,
        /** `signedMessageStatus.signed_message_fault`. */
        public val fault: MessageFault_E = MessageFault_E.MESSAGEFAULT_ERROR_NONE,
        /** `signedMessageStatus.operation_status`. */
        public val operationStatus: OperationStatus_E = OperationStatus_E.OPERATIONSTATUS_OK,
    ) {
        /** `protobuf_message_as_bytes` (사본). */
        public val payload: ByteArray? = payload?.copyOf()
    }

    private class DomainState {
        var verifier: TestVerifier? = null
        var clientPublic: PublicKeyBytes? = null
        var responseCounter = 0u
        val script = ArrayDeque<List<ScriptedReply>>()
        val handshakeFaults = ArrayDeque<MessageFault_E>()
        var corruptNextTag = false
        var attachSessionInfoOnce = false
        var lastReply: Pair<FakeTransport, ByteArray>? = null
        var asleep = false
    }

    private val domains = ALL_DOMAINS.associateWith { DomainState() }
    private val receivedMessages = mutableListOf<RoutableMessage>()
    private var dropRemaining = 0
    private var sessionInfoRequestCount = 0
    private var connectableFlag = true

    /** 지금까지 받은(디코딩된) 요청. Go `inbox`. */
    public val received: List<RoutableMessage> get() = receivedMessages.toList()

    /**
     * 실제로 처리한 세션정보 요청 수. Go `callbackCount`. [handle]이 잠든 도메인으로 들어온 요청은 [received]에는
     * 남지만 콜백을 부르지 않으므로(Go `dummyConnector.handleAsync`, dispatcher_test.go:229-233) 여기에는 세지 않는다.
     */
    public val sessionInfoRequests: Int get() = sessionInfoRequestCount

    /** 광고의 connectable 플래그. false면 [connect]가 슬롯 초과로 실패한다. */
    public val isConnectable: Boolean get() = connectableFlag

    /** 도메인의 검증자. 핸드셰이크나 인증 명령을 한 번 받아야 존재한다. */
    public fun verifier(domain: Domain): TestVerifier = checkNotNull(domains.getValue(domain).verifier) { "no verifier for $domain yet" }

    /** 도메인의 현재 epoch. */
    public fun epoch(domain: Domain): ByteArray = verifier(domain).epoch

    /** 이 차량에 연결된 새 [FakeTransport]. Go 테스트의 `newDummyConnector`. [retryInterval]은 시간 초과 단계를 결정적으로 고정하는 테스트가 바꾼다. */
    public fun transport(retryInterval: Duration = 1.milliseconds): FakeTransport {
        val transport = FakeTransport(vin, retryInterval = retryInterval)
        transport.onSend = { bytes -> handle(transport, bytes) }
        return transport
    }

    /** M3 `TransportFactory.connect`의 M2 모델: connectable이 아니면 `MaxConnectionsExceeded`(재시도 없음, Go `tryToConnect`). */
    public fun connect(): VehicleResult<FakeTransport> =
        if (connectableFlag) {
            VehicleResult.Success(transport())
        } else {
            VehicleResult.Failure(VehicleError.TransportError.MaxConnectionsExceeded)
        }

    /** 광고 connectable 플래그를 놓는다(슬롯 초과 시나리오). */
    public fun setConnectable(value: Boolean) {
        connectableFlag = value
    }

    /** 해당 도메인의 응답을 버리기 시작한다(Go `Sleep`; Infotainment 수면 = `sleep(setOf(DOMAIN_INFOTAINMENT))`). */
    public fun sleep(domainsToSleep: Set<Domain> = ALL_DOMAINS) {
        for (domain in domainsToSleep) domains.getValue(domain).asleep = true
    }

    /** 모든 도메인의 응답을 다시 보낸다(Go `Wake`). */
    public fun wake() {
        for (state in domains.values) state.asleep = false
    }

    /** 다음 [count]개 응답을 버린다(응답 유실 시나리오). */
    public fun dropNextReplies(count: Int) {
        dropRemaining += count
    }

    /** 다음 명령들에 대한 응답 대본. 요청 하나에 리스트 하나(빈 리스트 = 응답 없음). 대본이 없으면 기본 응답 하나. */
    public fun script(
        domain: Domain,
        vararg perRequest: List<ScriptedReply>,
    ) {
        domains.getValue(domain).script.addAll(perRequest)
    }

    /** 다음 세션정보 요청들에 fault로 답한다(`NONE`이면 정상 응답). */
    public fun scriptHandshake(
        domain: Domain,
        vararg faults: MessageFault_E,
    ) {
        domains.getValue(domain).handshakeFaults.addAll(faults)
    }

    /** 차량 재부팅: 새 epoch, counter 0, 시계 원점 리셋. */
    public fun rotateEpoch(domain: Domain) {
        verifier(domain).rotateEpoch()
    }

    /** 차량 시계를 [by]만큼 옮긴다. 양수면 앞으로(명령 만료 유발), 음수면 뒤로(시계 역행). */
    public fun shiftClock(
        domain: Domain,
        by: Duration,
    ) {
        verifier(domain).shiftTimeZero(-by)
    }

    /** 다음 성공 응답에 세션정보를 선제적으로 동봉한다(평문 + 태그, payload 대신). */
    public fun attachSessionInfoOnce(domain: Domain) {
        domains.getValue(domain).attachSessionInfoOnce = true
    }

    /** 다음 세션정보 태그의 첫 바이트를 뒤집는다(HMAC 불일치 시나리오). */
    public fun corruptNextSessionInfoTag(domain: Domain) {
        domains.getValue(domain).corruptNextTag = true
    }

    /** 마지막으로 보낸 응답 바이트를 같은 전송으로 다시 넣는다(재전송 응답 시나리오). */
    public fun replayLastResponse(domain: Domain) {
        val (transport, bytes) = checkNotNull(domains.getValue(domain).lastReply) { "no reply sent for $domain yet" }
        transport.deliver(bytes)
    }

    private suspend fun handle(
        transport: FakeTransport,
        bytes: ByteArray,
    ) {
        // Task 3 decodeOrNull: RoutableMessage가 아닌 프레임(M4 ToVCSECMessage)이나 손상된 바이트는 조용히 무시한다.
        val message = RoutableMessage.ADAPTER.decodeOrNull(bytes) ?: return
        receivedMessages += message
        val domain = message.to_destination?.domain ?: return
        val state = domains[domain] ?: return
        // Go dummyConnector.handleAsync(dispatcher_test.go:229-233): dropReplies(=asleep)가 서 있으면 inbox에는
        // 남지만 콜백을 부르지 않는다 — 세션정보 요청 카운트도, 대본 소비도, 검증자 상태도 바뀌지 않는다.
        if (state.asleep) return
        val replies =
            when {
                message.session_info_request != null -> handleSessionInfoRequest(state, domain, message)
                message.signature_data?.AES_GCM_Personalized_data != null -> handleAuthenticated(state, domain, message)
                message.protobuf_message_as_bytes != null -> scriptedReplies(state, domain, message, verifier = null)
                else -> emptyList()
            }
        for (reply in replies) deliver(transport, state, reply)
    }

    private suspend fun handleSessionInfoRequest(
        state: DomainState,
        domain: Domain,
        message: RoutableMessage,
    ): List<RoutableMessage> {
        sessionInfoRequestCount++
        val fault = state.handshakeFaults.removeFirstOrNull()
        if (fault != null && fault != MessageFault_E.MESSAGEFAULT_ERROR_NONE) return listOf(faultReply(message, fault))
        val request = checkNotNull(message.session_info_request)
        val clientPublic =
            try {
                PublicKeyBytes(request.public_key.toByteArray())
            } catch (ignored: IllegalArgumentException) {
                return listOf(faultReply(message, MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER))
            }
        val verifier = verifierFor(state, domain, clientPublic)
        val reply = verifier.setSessionInfo(message.uuid.toByteArray(), initReply(message))
        return listOf(maybeCorruptTag(state, reply))
    }

    private suspend fun handleAuthenticated(
        state: DomainState,
        domain: Domain,
        message: RoutableMessage,
    ): List<RoutableMessage> {
        val signerPublic =
            message.signature_data?.signer_identity?.public_key
                ?: return listOf(faultReply(message, MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER))
        val clientPublic =
            try {
                PublicKeyBytes(signerPublic.toByteArray())
            } catch (ignored: IllegalArgumentException) {
                return listOf(faultReply(message, MessageFault_E.MESSAGEFAULT_ERROR_BAD_PARAMETER))
            }
        val verifier = verifierFor(state, domain, clientPublic)
        return when (val result = verifier.verify(message)) {
            is TestVerifier.VerifyResult.Fault -> listOf(attachSessionInfo(state, faultReply(message, result.fault), result.sessionInfo))
            is TestVerifier.VerifyResult.Ok -> scriptedReplies(state, domain, message, verifier)
        }
    }

    /** 검증자는 도메인마다 하나를 유지한다. 클라이언트 공개키가 바뀌면(다른 클라이언트) 새로 만든다. Go dummyConnector는 요청마다 새로 만든다. */
    private suspend fun verifierFor(
        state: DomainState,
        domain: Domain,
        clientPublic: PublicKeyBytes,
    ): TestVerifier {
        val existing = state.verifier
        if (existing != null && state.clientPublic == clientPublic) return existing
        val created = TestVerifier.create(vehicleKey, vin.toByteArray(), domain, clientPublic, crypto, random, timeSource)
        state.verifier = created
        state.clientPublic = clientPublic
        state.responseCounter = 0u
        return created
    }

    private fun scriptedReplies(
        state: DomainState,
        domain: Domain,
        request: RoutableMessage,
        verifier: TestVerifier?,
    ): List<RoutableMessage> {
        val scripted = state.script.removeFirstOrNull() ?: listOf(defaultReply(domain))
        val requestHash = RequestHash.of(request)
        val encrypt = verifier != null && requestHash != null && (request.flags and FLAG_ENCRYPT_RESPONSE) != 0
        return scripted.map { entry ->
            var reply =
                initReply(request).copy(
                    protobuf_message_as_bytes = entry.payload?.toByteString(),
                    signedMessageStatus = statusOf(entry),
                )
            if (verifier != null && state.attachSessionInfoOnce) {
                state.attachSessionInfoOnce = false
                reply = attachSessionInfo(state, reply, verifier.signedSessionInfo(request.uuid.toByteArray()))
            }
            if (encrypt && reply.session_info == null) {
                checkNotNull(verifier).encryptResponse(reply, checkNotNull(requestHash), ++state.responseCounter)
            } else {
                reply
            }
        }
    }

    private fun statusOf(entry: ScriptedReply): MessageStatus? =
        if (entry.fault == MessageFault_E.MESSAGEFAULT_ERROR_NONE && entry.operationStatus == OperationStatus_E.OPERATIONSTATUS_OK) {
            null
        } else {
            MessageStatus(operation_status = entry.operationStatus, signed_message_fault = entry.fault)
        }

    private fun defaultReply(domain: Domain): ScriptedReply = if (domain == Domain.DOMAIN_INFOTAINMENT) infotainmentOk() else vcsecEmpty()

    private fun attachSessionInfo(
        state: DomainState,
        reply: RoutableMessage,
        signed: SignedSessionInfo?,
    ): RoutableMessage {
        if (signed == null) return reply
        val withInfo =
            reply.copy(
                protobuf_message_as_bytes = null,
                session_info_request = null,
                session_info = signed.encoded.toByteString(),
                signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = signed.tag.toByteString())),
            )
        return maybeCorruptTag(state, withInfo)
    }

    private fun maybeCorruptTag(
        state: DomainState,
        reply: RoutableMessage,
    ): RoutableMessage {
        if (!state.corruptNextTag) return reply
        state.corruptNextTag = false
        val tag = checkNotNull(reply.signature_data?.session_info_tag).tag.toByteArray()
        tag[0] = (tag[0].toInt() xor 1).toByte()
        return reply.copy(signature_data = SignatureData(session_info_tag = HMAC_Signature_Data(tag = tag.toByteString())))
    }

    private fun deliver(
        transport: FakeTransport,
        state: DomainState,
        reply: RoutableMessage,
    ) {
        // handle()이 이미 asleep 도메인을 걸러내므로 이 시점에는 항상 false다. 방어적으로 남겨 둔다.
        if (state.asleep) return
        if (dropRemaining > 0) {
            dropRemaining--
            return
        }
        val bytes = RoutableMessage.ADAPTER.encode(reply)
        state.lastReply = transport to bytes
        transport.deliver(bytes)
    }

    /** Go `initReply`: to = 요청의 from(routing address), from = 요청의 to(domain), request_uuid = 요청 uuid, uuid = testUUID. */
    private fun initReply(message: RoutableMessage): RoutableMessage =
        RoutableMessage(
            to_destination = message.from_destination,
            from_destination = message.to_destination,
            request_uuid = message.uuid,
            uuid = TEST_UUID.toByteString(),
        )

    private fun faultReply(
        message: RoutableMessage,
        fault: MessageFault_E,
    ): RoutableMessage = initReply(message).copy(signedMessageStatus = MessageStatus(signed_message_fault = fault))

    /** 대본 헬퍼와 상수. */
    public companion object {
        /** `1 shl Flags.FLAG_ENCRYPT_RESPONSE` = 2 (Go `vehicle.DefaultFlags`). */
        public const val FLAG_ENCRYPT_RESPONSE: Int = 2

        /** Go `testUUID()`: 0x00..0x0f. 응답의 `uuid`. */
        public val TEST_UUID: ByteArray = ByteArray(16) { it.toByte() }

        /** VCSEC + INFOTAINMENT. */
        public val ALL_DOMAINS: Set<Domain> = setOf(Domain.DOMAIN_VEHICLE_SECURITY, Domain.DOMAIN_INFOTAINMENT)

        /** payload 없는 VCSEC 응답(RKE/closure 최종 성공). */
        public fun vcsecEmpty(): ScriptedReply = ScriptedReply()

        /** `commandStatus{WAIT}` (Go `EnqueueVCSECBusy`). */
        public fun vcsecBusy(): ScriptedReply =
            vcsecPayload(FromVCSECMessage(commandStatus = CommandStatus(operationStatus = VcsecOperationStatus.OPERATIONSTATUS_WAIT)))

        /** `commandStatus{OK, signedMessageStatus{counter}}` (Go `EnqueueAuthenticationSuccessResponse`; whitelist 작업의 중간 응답). */
        public fun vcsecAuthSuccess(counter: Int = 1337): ScriptedReply =
            vcsecPayload(
                FromVCSECMessage(
                    commandStatus =
                        CommandStatus(
                            operationStatus = VcsecOperationStatus.OPERATIONSTATUS_OK,
                            signedMessageStatus = SignedMessage_status(counter = counter),
                        ),
                ),
            )

        /** `commandStatus{ERROR|OK, whitelistOperationStatus{info}}` (Go `EnqueueWhitelistOperationStatus`; NONE이면 OK로 성공). */
        public fun vcsecWhitelistStatus(info: WhitelistOperation_information_E): ScriptedReply =
            vcsecPayload(
                FromVCSECMessage(
                    commandStatus =
                        CommandStatus(
                            operationStatus =
                                if (info == WhitelistOperation_information_E.WHITELISTOPERATION_INFORMATION_NONE) {
                                    VcsecOperationStatus.OPERATIONSTATUS_OK
                                } else {
                                    VcsecOperationStatus.OPERATIONSTATUS_ERROR
                                },
                            whitelistOperationStatus = WhitelistOperation_status(whitelistOperationInformation = info),
                        ),
                ),
            )

        /** `nominalError{genericError}` (Go `TestNominalVSCECError`). */
        public fun vcsecNominalError(code: GenericError_E): ScriptedReply =
            vcsecPayload(FromVCSECMessage(nominalError = NominalError(genericError = code)))

        /** 파싱 불가 페이로드 `0xFF` (Go `TestGibberishVCSECResponse`). */
        public fun vcsecGibberish(): ScriptedReply = ScriptedReply(payload = byteArrayOf(0xFF.toByte()))

        /** 임의의 `FromVCSECMessage`. */
        public fun vcsecPayload(message: FromVCSECMessage): ScriptedReply = ScriptedReply(payload = message.encode())

        /** `Response{actionStatus{OK}}`. */
        public fun infotainmentOk(): ScriptedReply =
            infotainmentPayload(Response(actionStatus = ActionStatus(result = CarServerOperationStatus.OPERATIONSTATUS_OK)))

        /** `Response{actionStatus{ERROR, result_reason{plain_text}}}`. [reason]이 null이면 사유 없음. */
        public fun infotainmentError(reason: String?): ScriptedReply =
            infotainmentPayload(
                Response(
                    actionStatus =
                        ActionStatus(
                            result = CarServerOperationStatus.OPERATIONSTATUS_ERROR,
                            result_reason = reason?.let { ResultReason(plain_text = it) },
                        ),
                ),
            )

        /** 임의의 `CarServer.Response`. */
        public fun infotainmentPayload(response: Response): ScriptedReply = ScriptedReply(payload = response.encode())
    }
}
