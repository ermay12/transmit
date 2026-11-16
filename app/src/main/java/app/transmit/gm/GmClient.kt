package app.transmit.gm

import android.util.Log
import app.transmit.gm.proto.AckMessageRequest
import app.transmit.gm.proto.ActionType
import app.transmit.gm.proto.AlertType
import app.transmit.gm.proto.AttachmentInfo
import app.transmit.gm.proto.AuthMessage
import app.transmit.gm.proto.AuthenticationContainer
import app.transmit.gm.proto.BridgeAuth
import app.transmit.gm.proto.BrowserDetails
import app.transmit.gm.proto.BrowserType
import app.transmit.gm.proto.BugleRoute
import app.transmit.gm.proto.ConfigVersion
import app.transmit.gm.proto.ContactNumber
import app.transmit.gm.proto.Conversation
import app.transmit.gm.proto.ConversationActionStatus
import app.transmit.gm.proto.Cursor
import app.transmit.gm.proto.DeleteConversationData
import app.transmit.gm.proto.DeleteMessageRequest
import app.transmit.gm.proto.DeleteMessageResponse
import app.transmit.gm.proto.Device
import app.transmit.gm.proto.DeviceType
import app.transmit.gm.proto.DownloadAttachmentRequest
import app.transmit.gm.proto.ECDSAKeys
import app.transmit.gm.proto.EmptyArr
import app.transmit.gm.proto.ErrorResponse
import app.transmit.gm.proto.GetConversationRequest
import app.transmit.gm.proto.GetConversationResponse
import app.transmit.gm.proto.GetOrCreateConversationRequest
import app.transmit.gm.proto.GetOrCreateConversationResponse
import app.transmit.gm.proto.GetThumbnailResponse
import app.transmit.gm.proto.IncomingRPCMessage
import app.transmit.gm.proto.IsBugleDefaultResponse
import app.transmit.gm.proto.KeyData
import app.transmit.gm.proto.ListContactsResponse
import app.transmit.gm.proto.ListConversationsRequest
import app.transmit.gm.proto.ListConversationsResponse
import app.transmit.gm.proto.ListMessagesRequest
import app.transmit.gm.proto.ListMessagesResponse
import app.transmit.gm.proto.ListTopContactsResponse
import app.transmit.gm.proto.LongPollingPayload
import app.transmit.gm.proto.MediaContent
import app.transmit.gm.proto.MediaFormats
import app.transmit.gm.proto.MessageReadRequest
import app.transmit.gm.proto.MessageType
import app.transmit.gm.proto.NotifyDittoActivityRequest
import app.transmit.gm.proto.NotifyDittoActivityResponse
import app.transmit.gm.proto.OutgoingRPCData
import app.transmit.gm.proto.OutgoingRPCMessage
import app.transmit.gm.proto.OutgoingRPCResponse
import app.transmit.gm.proto.PairedData
import app.transmit.gm.proto.RPCMessageData
import app.transmit.gm.proto.RPCPairData
import app.transmit.gm.proto.ReceiveMessagesRequest
import app.transmit.gm.proto.RefreshPhoneRelayResponse
import app.transmit.gm.proto.RegisterPhoneRelayResponse
import app.transmit.gm.proto.RegisterRefreshRequest
import app.transmit.gm.proto.RegisterRefreshResponse
import app.transmit.gm.proto.RevokeRelayPairingRequest
import app.transmit.gm.proto.RevokeRelayPairingResponse
import app.transmit.gm.proto.SendMessageRequest
import app.transmit.gm.proto.SendMessageResponse
import app.transmit.gm.proto.SendReactionRequest
import app.transmit.gm.proto.SendReactionResponse
import app.transmit.gm.proto.Settings
import app.transmit.gm.proto.StartMediaUploadRequest
import app.transmit.gm.proto.TokenData
import app.transmit.gm.proto.TypingData
import app.transmit.gm.proto.UpdateConversationRequest
import app.transmit.gm.proto.UpdateConversationResponse
import app.transmit.gm.proto.UpdateEvents
import app.transmit.gm.proto.UploadMediaResponse
import app.transmit.gm.proto.URLData
import app.transmit.gm.proto.GaiaPairingErrorCode
import app.transmit.gm.proto.GetFullSizeImageRequest
import app.transmit.gm.proto.GetFullSizeImageResponse
import app.transmit.gm.proto.GaiaPairingRequestContainer
import app.transmit.gm.proto.GaiaPairingResponseContainer
import app.transmit.gm.proto.RevokeGaiaPairingRequest
import app.transmit.gm.proto.SignInGaiaRequest
import app.transmit.gm.proto.SignInGaiaResponse
import app.transmit.gm.proto.GetUpdatesRequest
import com.google.protobuf.ByteString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.google.protobuf.Message as ProtoMessage
import app.transmit.gm.proto.Message as GmMessage

/** Server endpoints and the browser identity Transmit presents (mirrors libgm's util package). */
internal object Gm {
    const val API_KEY = "AIzaSyCA4RsOZUFrm9whhtGosPlJLmVPnfSHKz8"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"
    const val SEC_UA = "\"Google Chrome\";v=\"152\", \"Chromium\";v=\"152\", \"Not-A.Brand\";v=\"24\""
    const val QR_URL_BASE = "https://support.google.com/messages/?p=web_computer#?c="
    const val QR_NETWORK = "Bugle"

    private const val IM = "https://instantmessaging-pa.googleapis.com"
    private const val IM_GOOGLE = "https://instantmessaging-pa.clients6.google.com"
    const val UPLOAD_URL = "$IM/upload"
    private const val PAIRING = "$IM/\$rpc/google.internal.communications.instantmessaging.v1.Pairing"
    const val REGISTER_PHONE_RELAY = "$PAIRING/RegisterPhoneRelay"
    const val REFRESH_PHONE_RELAY = "$PAIRING/RefreshPhoneRelay"
    const val REVOKE_RELAY_PAIRING = "$PAIRING/RevokeRelayPairing"
    // The web client (and libgm) send messaging calls to the clients6 host.
    private const val MESSAGING = "$IM_GOOGLE/\$rpc/google.internal.communications.instantmessaging.v1.Messaging"
    const val RECEIVE_MESSAGES = "$MESSAGING/ReceiveMessages"
    const val SEND_MESSAGE = "$MESSAGING/SendMessage"
    const val ACK_MESSAGES = "$MESSAGING/AckMessages"
    private const val REGISTRATION = "$IM_GOOGLE/\$rpc/google.internal.communications.instantmessaging.v1.Registration"
    const val REGISTER_REFRESH = "$REGISTRATION/RegisterRefresh"
    const val SIGN_IN_GAIA = "$REGISTRATION/SignInGaia"
    const val MESSAGES_ORIGIN = "https://messages.google.com"
    /** Network name for Google-account pairings; QR pairings use "". */
    const val GOOGLE_NETWORK = "GDitto"

    const val PROTOBUF = "application/x-protobuf"
    const val PBLITE = "application/json+protobuf"

    val configVersion: ConfigVersion = ConfigVersion.newBuilder().setYear(2026).setMonth(9).setDay(2).setV1(4).setV2(6).build()

    val browserDetails: BrowserDetails = BrowserDetails.newBuilder()
        .setUserAgent(USER_AGENT)
        .setBrowserType(BrowserType.OTHER)
        .setOS("Transmit")
        .setDeviceType(DeviceType.TABLET)
        .build()
}

/** The pairing's keys and server credentials. [GmClient] mutates it; the owner persists it on change. */
class GmAuth(@Volatile var crypto: RequestCrypto, val refreshKey: RefreshKey, val sessionId: ByteArray = randomBytes(16)) {
    @Volatile var browser: Device? = null
    @Volatile var mobile: Device? = null
    @Volatile var tachyonToken: ByteString? = null
    @Volatile var tachyonExpiryMs: Long = 0
    @Volatile var tachyonTtlMicros: Long = 0

    /** Google-account pairing: the phone's registration id, which every request is addressed to. */
    @Volatile var destRegId: String? = null
    @Volatile var pairingId: String? = null
    /** Google sign-in cookies (Google-account pairing only). */
    val cookies: MutableMap<String, String> = java.util.Collections.synchronizedMap(LinkedHashMap())

    val isPaired get() = browser != null && mobile != null && tachyonToken != null
    val isGoogleAccount get() = !destRegId.isNullOrEmpty()
    val network get() = if (isGoogleAccount) Gm.GOOGLE_NETWORK else ""

    fun toProto(): BridgeAuth = BridgeAuth.newBuilder().apply {
        aesKey = ByteString.copyFrom(crypto.aesKey)
        hmacKey = ByteString.copyFrom(crypto.hmacKey)
        refreshPrivateKey = ByteString.copyFrom(refreshKey.privateKeyPkcs8)
        refreshPublicKey = ByteString.copyFrom(refreshKey.publicKeyX509)
        this@GmAuth.browser?.let { browser = it }
        this@GmAuth.mobile?.let { mobile = it }
        tachyonToken?.let { tachyonAuthToken = it }
        tachyonExpiryMs = this@GmAuth.tachyonExpiryMs
        tachyonTtlMicros = this@GmAuth.tachyonTtlMicros
        sessionId = ByteString.copyFrom(this@GmAuth.sessionId)
        this@GmAuth.destRegId?.let { destRegId = it }
        this@GmAuth.pairingId?.let { pairingId = it }
        putAllCookies(synchronized(this@GmAuth.cookies) { HashMap(this@GmAuth.cookies) })
    }.build()

    companion object {
        fun fresh() = GmAuth(RequestCrypto.generate(), RefreshKey.generate())

        fun fromProto(p: BridgeAuth) = GmAuth(
            RequestCrypto(p.aesKey.toByteArray(), p.hmacKey.toByteArray()),
            RefreshKey.load(p.refreshPrivateKey.toByteArray(), p.refreshPublicKey.toByteArray()),
            if (p.sessionId.isEmpty) randomBytes(16) else p.sessionId.toByteArray(),
        ).apply {
            destRegId = p.destRegId.ifEmpty { null }
            pairingId = p.pairingId.ifEmpty { null }
            cookies.putAll(p.cookiesMap)
            if (p.hasBrowser()) browser = p.browser
            if (p.hasMobile()) mobile = p.mobile
            if (!p.tachyonAuthToken.isEmpty) tachyonToken = p.tachyonAuthToken
            tachyonExpiryMs = p.tachyonExpiryMs
            tachyonTtlMicros = p.tachyonTtlMicros
        }
    }
}

sealed interface GmEvent {
    /** The phone scanned the QR code; [GmAuth] now holds the pairing. */
    data class Paired(val phoneId: String) : GmEvent
    /** The phone removed this pairing. */
    data object Unpaired : GmEvent
    /** The logged-in connection is up and the phone was asked to send updates here. */
    data object Ready : GmEvent
    data class ConversationUpdate(val conversation: Conversation) : GmEvent
    /** [isOld]: replayed from before this connection, so not news. */
    data class MessageUpdate(val message: GmMessage, val isOld: Boolean) : GmEvent
    data class Typing(val data: TypingData) : GmEvent
    data class SettingsUpdate(val settings: Settings) : GmEvent
    data class Alert(val type: AlertType) : GmEvent
    data class PhoneResponding(val responding: Boolean) : GmEvent
    data class ConnectionTrouble(val error: Throwable) : GmEvent
    data object ConnectionRestored : GmEvent
    /** The credentials stopped working; only pairing again helps. */
    data class Fatal(val error: Throwable) : GmEvent
}

class GmHttpException(val code: Int, val error: ErrorResponse?) :
    GmException("HTTP $code${error?.let { ": ${it.type} ${it.message}" }.orEmpty()}") {
    /** Bad or revoked credentials, as opposed to a server hiccup. */
    val fatal get() = code == 401 || code == 403 || code == 404 || error?.type == 16L || error?.type == 5L
}

class PhoneNotRespondingException : GmException("Your phone isn't responding. Check that it's online.")

/** A step of Google-account pairing the user can see: the emoji to tap on the phone. */
class GooglePairing internal constructor(val emoji: String, internal val session: GaiaPairingSession)

/**
 * A Google Messages for Web client: Kotlin port of mautrix-gmessages' libgm. The phone stays the
 * messaging device; this talks to it through Google's relay, with payloads end-to-end encrypted
 * using keys exchanged in the pairing QR code.
 */
class GmClient(
    val auth: GmAuth,
    /** Called on the polling thread, in order; must not block. */
    private val onEvent: (GmEvent) -> Unit,
    private val onAuthChanged: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    // The server sends at least a heartbeat a minute; a silent minute means the stream is dead.
    private val pollHttp = http.newBuilder().readTimeout(70, TimeUnit.SECONDS).callTimeout(30, TimeUnit.MINUTES).build()

    @Volatile private var sessionId = UUID.randomUUID().toString()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<IncomingRpc>>()
    private val pendingAcks = LinkedHashSet<String>()
    private val skipCount = AtomicInteger(0)
    @Volatile private var pollJob: Job? = null
    /** Completed when the current long poll's response starts (the server is listening for us). */
    @Volatile private var pollConnected = CompletableDeferred<Unit>()
    @Volatile private var ackJob: Job? = null
    private val pingNow = Channel<Unit>(Channel.CONFLATED)
    private val refreshLock = Mutex()
    @Volatile private var conversationsFetched = false

    // Phone liveness, kept across reconnects so recovery backs off over a long outage.
    @Volatile private var pingTimeouts = 0
    @Volatile private var reportedDown = false
    @Volatile private var pingIntervalMs = PING_INTERVAL_MS
    @Volatile private var nextDataCheckMs = 0L
    private val recentUpdates = ArrayDeque<Pair<String, String>>()

    val isConnected get() = pollJob?.isActive == true

    // ---- lifecycle -----------------------------------------------------------------------------

    /** Starts the logged-in connection. Throws if the credentials are rejected. */
    suspend fun connect() {
        check(auth.isPaired) { "Not paired" }
        refreshAuthToken()
        startPolling(loggedIn = true)
    }

    fun reconnect() {
        if (auth.isPaired) startPolling(loggedIn = true)
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        ackJob?.cancel()
        ackJob = null
        val closed = GmException("Disconnected")
        waiters.values.forEach { it.completeExceptionally(closed) }
        waiters.clear()
    }

    fun close() {
        disconnect()
        scope.cancel()
    }

    private fun startPolling(loggedIn: Boolean) {
        pollJob?.cancel()
        pollConnected = CompletableDeferred()
        pollJob = scope.launch { longPoll(loggedIn) }
        if ((loggedIn || auth.browser != null) && ackJob?.isActive != true) {
            ackJob = scope.launch {
                while (isActive) {
                    delay(5_000)
                    sendAcks()
                }
            }
        }
    }

    // ---- pairing -------------------------------------------------------------------------------

    /** Registers this browser and starts listening for the phone; returns the QR code's URL. */
    suspend fun startPairing(): String {
        val req = AuthenticationContainer.newBuilder()
            .setAuthMessage(AuthMessage.newBuilder().setRequestID(uuid()).setNetwork(Gm.QR_NETWORK).setConfigVersion(Gm.configVersion))
            .setBrowserDetails(Gm.browserDetails)
            .setKeyData(KeyData.newBuilder().setEcdsaKeys(ECDSAKeys.newBuilder().setField1(2).setEncryptedKeys(ByteString.copyFrom(auth.refreshKey.publicKeyX509))))
            .build()
        val resp: RegisterPhoneRelayResponse = call(Gm.REGISTER_PHONE_RELAY, req, Gm.PROTOBUF, RegisterPhoneRelayResponse.newBuilder())
        updateToken(resp.authKeyData)
        startPolling(loggedIn = false)
        return qrUrl(resp.pairingKey)
    }

    /** A fresh QR code for the same pending pairing (they expire after a minute or so). */
    suspend fun refreshPairingQr(): String {
        val req = AuthenticationContainer.newBuilder()
            .setAuthMessage(
                AuthMessage.newBuilder().setRequestID(uuid()).setNetwork(Gm.QR_NETWORK)
                    .setTachyonAuthToken(auth.tachyonToken ?: ByteString.EMPTY).setConfigVersion(Gm.configVersion),
            )
            .build()
        val resp: RefreshPhoneRelayResponse = call(Gm.REFRESH_PHONE_RELAY, req, Gm.PROTOBUF, RefreshPhoneRelayResponse.newBuilder())
        return qrUrl(resp.pairKey)
    }

    private fun qrUrl(pairingKey: ByteString): String {
        val data = URLData.newBuilder()
            .setPairingKey(pairingKey)
            .setAESKey(ByteString.copyFrom(auth.crypto.aesKey))
            .setHMACKey(ByteString.copyFrom(auth.crypto.hmacKey))
            .build()
        return Gm.QR_URL_BASE + Base64.getEncoder().encodeToString(data.toByteArray())
    }

    /** Tells the server to forget this browser, which also removes it from the phone's paired devices. */
    suspend fun unpair() {
        val browser = auth.browser ?: return
        if (auth.isGoogleAccount) {
            val id = auth.pairingId ?: return
            sendNoResponse(ActionType.UNPAIR_GAIA_PAIRING, RevokeGaiaPairingRequest.newBuilder().setPairingAttemptID(id).build())
            return
        }
        val req = RevokeRelayPairingRequest.newBuilder()
            .setAuthMessage(authMessage())
            .setBrowser(browser)
            .build()
        call<RevokeRelayPairingResponse>(Gm.REVOKE_RELAY_PAIRING, req, Gm.PROTOBUF, RevokeRelayPairingResponse.newBuilder())
    }

    private fun completePairing(data: PairedData) {
        updateToken(data.tokenData)
        auth.mobile = data.mobile
        auth.browser = data.browser
        onAuthChanged()
        onEvent(GmEvent.Paired(data.mobile.sourceID))
        scope.launch {
            // Reconnecting before the phone saves the pairing gets this browser unpaired again.
            delay(2_000)
            startPolling(loggedIn = true)
        }
    }

    // ---- Google-account pairing -------------------------------------------------------------------

    @Volatile private var gaiaSession: GaiaPairingSession? = null

    /**
     * Pairs through the Google account whose cookies are in [GmAuth.cookies]: finds the phone, starts a
     * key exchange with it, and returns the emoji the user must pick on the phone. Then call
     * [finishGooglePairing].
     */
    suspend fun startGooglePairing(): GooglePairing {
        check(auth.cookies.containsKey("SAPISID")) { "Sign in to Google first" }
        val resp = signInGaia()
        updateToken(resp.tokenData)
        val device = resp.deviceData.deviceWrapper.device
        auth.browser = device
        auth.mobile = device.toBuilder().setSourceID(device.sourceID.lowercase()).build()
        val lastSeen = resp.deviceData.unknownItems3List.associate { it.destOrSourceUUID to it.unknownTimestampMicroseconds }
        val phones = resp.deviceData.unknownItems2List.filter { it.unknownInt4 == 1 }.sortedByDescending { lastSeen[it.destOrSourceUUID] ?: 0 }
        val phone = phones.firstOrNull() ?: throw GmException(
            "No phone is set up for Google account pairing. On your phone, open Google Messages, tap your profile picture, then Device pairing.",
        )
        auth.destRegId = phone.destOrSourceUUID
        // The phone's answers arrive over the long poll, so it must be listening before we ask.
        startPolling(loggedIn = false)
        withTimeoutOrNull(20_000) { pollConnected.await() } ?: throw GmException("Couldn't reach Google Messages")
        val session = GaiaPairingSession(phone.destOrSourceUUID)
        gaiaSession = session
        val init = session.prepare()
        val serverInit = try {
            withTimeoutOrNull(20_000) { sendGaia(session, ActionType.CREATE_GAIA_PAIRING_CLIENT_INIT, init) }
                ?: throw GmException("Your phone didn't answer. Open Google Messages on it, check it's online, and try again.")
        } catch (e: Exception) {
            cancelGooglePairing()
            throw e
        }
        if (serverInit.finishErrorType != 0) throw gaiaError(serverInit)
        return GooglePairing(session.processServerInit(serverInit), session)
    }

    /** Waits for the user to pick the emoji on the phone, then switches to the paired connection. */
    suspend fun finishGooglePairing(p: GooglePairing) {
        // The phone answers once the user picks an emoji (or gives up); a cancelled wait is reported by the caller.
        val resp = sendGaia(p.session, ActionType.CREATE_GAIA_PAIRING_CLIENT_FINISHED, p.session.finishPayload)
        if (resp.finishErrorType != 0) throw gaiaError(resp)
        auth.crypto = p.session.requestCrypto()
        auth.pairingId = p.session.id
        gaiaSession = null
        onAuthChanged()
        // Paired resets the cache, so it must land before the logged-in connection reports Ready.
        onEvent(GmEvent.Paired(auth.mobile?.sourceID.orEmpty()))
        startPolling(loggedIn = true)
    }

    suspend fun cancelGooglePairing() {
        val session = gaiaSession ?: return
        gaiaSession = null
        post(buildRpc(ActionType.CANCEL_GAIA_PAIRING, null, requestId = session.id, messageType = MessageType.GAIA_2, encrypt = false, ttlMicros = GAIA_TTL_MICROS))
    }

    private suspend fun signInGaia(): SignInGaiaResponse {
        val req = SignInGaiaRequest.newBuilder()
            .setAuthMessage(AuthMessage.newBuilder().setRequestID(uuid()).setNetwork(Gm.GOOGLE_NETWORK).setConfigVersion(Gm.configVersion))
            .setInner(
                SignInGaiaRequest.Inner.newBuilder()
                    .setDeviceID(
                        SignInGaiaRequest.Inner.DeviceID.newBuilder()
                            .setUnknownInt1(3)
                            .setDeviceID("messages-web-" + auth.sessionId.joinToString("") { "%02x".format(it) }),
                    )
                    .setSomeData(SignInGaiaRequest.Inner.Data.newBuilder().setSomeData(ByteString.copyFrom(auth.refreshKey.publicKeyX509))),
            )
            .setNetwork(Gm.GOOGLE_NETWORK)
            .build()
        return call(Gm.SIGN_IN_GAIA, req, Gm.PBLITE, SignInGaiaResponse.newBuilder())
    }

    private suspend fun sendGaia(session: GaiaPairingSession, action: ActionType, payload: ByteArray): GaiaPairingResponseContainer {
        val container = GaiaPairingRequestContainer.newBuilder()
            .setPairingAttemptID(session.id)
            .setBrowserDetails(Gm.browserDetails)
            .setStartTimestamp(session.startMs)
            .setData(ByteString.copyFrom(payload))
        val type = if (action == ActionType.CREATE_GAIA_PAIRING_CLIENT_FINISHED) {
            container.privateAPIConfirmation = PRIVATE_API_CONFIRMATION
            MessageType.BUGLE_MESSAGE
        } else {
            container.proposedVerificationCodeVersion = 1
            container.proposedKeyDerivationVersion = 1
            MessageType.GAIA_2
        }
        val (requestId, waiter) = sendAsync(action, container.build(), type, encrypt = false, ttlMicros = GAIA_TTL_MICROS)
        try {
            val reply = waiter.await()
            return GaiaPairingResponseContainer.parseFrom(reply.unencrypted ?: ByteArray(0))
        } finally {
            waiters.remove(requestId)
        }
    }

    private fun gaiaError(resp: GaiaPairingResponseContainer) = GmException(
        when (resp.finishErrorCode) {
            GaiaPairingErrorCode.WRONG_VERIFICATION_CODE_SELECTED -> "A different emoji was picked on the phone. Try again."
            GaiaPairingErrorCode.USER_CANCELED_VERIFICATION, GaiaPairingErrorCode.USER_DENIED_VERIFICATION_NOT_ME ->
                "Pairing was cancelled on the phone."
            GaiaPairingErrorCode.REQUEST_OUT_OF_DATE, GaiaPairingErrorCode.REQUEST_NOT_RECEIVED_QUICKLY,
            GaiaPairingErrorCode.VERIFICATION_TIMED_OUT -> "Pairing timed out. Try again."
            else -> "Google Messages couldn't pair (${resp.finishErrorType}/${resp.finishErrorCode})."
        },
    )

    // ---- auth token ------------------------------------------------------------------------------

    private fun updateToken(data: TokenData) {
        auth.tachyonToken = data.tachyonAuthToken
        val ttlMicros = data.ttl.takeIf { it > 0 } ?: TimeUnit.HOURS.toMicros(24)
        auth.tachyonExpiryMs = System.currentTimeMillis() + ttlMicros / 1000
        auth.tachyonTtlMicros = ttlMicros
    }

    suspend fun refreshAuthToken(force: Boolean = false) = refreshLock.withLock {
        val browser = auth.browser ?: return@withLock
        if (!force && auth.tachyonExpiryMs - System.currentTimeMillis() > REFRESH_BUFFER_MS) return@withLock
        val requestId = uuid()
        val timestamp = System.currentTimeMillis() * 1000
        val req = RegisterRefreshRequest.newBuilder()
            .setMessageAuth(authMessage(requestId))
            .setCurrBrowserDevice(browser)
            .setUnixTimestamp(timestamp)
            .setSignature(ByteString.copyFrom(auth.refreshKey.sign("$requestId:$timestamp".toByteArray())))
            .setParameters(RegisterRefreshRequest.Parameters.newBuilder().setEmptyArr(EmptyArr.getDefaultInstance()))
            .setMessageType(2)
            .build()
        val resp: RegisterRefreshResponse = call(Gm.REGISTER_REFRESH, req, Gm.PBLITE, RegisterRefreshResponse.newBuilder())
        if (resp.tokenData.tachyonAuthToken.isEmpty) throw GmException("No auth token in refresh response")
        updateToken(resp.tokenData)
        onAuthChanged()
    }

    private fun authMessage(requestId: String = uuid()): AuthMessage = AuthMessage.newBuilder()
        .setRequestID(requestId)
        .setTachyonAuthToken(auth.tachyonToken ?: ByteString.EMPTY)
        .setNetwork(auth.network)
        .setConfigVersion(Gm.configVersion)
        .build()

    // ---- long polling ----------------------------------------------------------------------------

    private suspend fun longPoll(loggedIn: Boolean) = coroutineScope {
        val listenId = uuid()
        if (loggedIn) launch { pinger() }
        var failures = 0
        var firstConnect = loggedIn
        var disconnectedAt = 0L
        while (isActive) {
            try {
                refreshAuthToken()
                val req = ReceiveMessagesRequest.newBuilder()
                    .setAuth(authMessage(listenId))
                    .setUnknown(
                        ReceiveMessagesRequest.UnknownEmptyObject2.newBuilder()
                            .setUnknown(ReceiveMessagesRequest.UnknownEmptyObject1.getDefaultInstance()),
                    )
                    .build()
                val call = pollHttp.newCall(relayRequest(Gm.RECEIVE_MESSAGES, Pblite.encode(req).toByteArray(), Gm.PBLITE))
                call.await().use { resp ->
                    updateCookies(resp)
                    if (!resp.isSuccessful) throw GmHttpException(resp.code, parseError(resp))
                    pollConnected.complete(Unit)
                    if (failures > 0) {
                        failures = 0
                        if (loggedIn) onEvent(GmEvent.ConnectionRestored)
                    }
                    if (loggedIn && disconnectedAt > 0 && System.currentTimeMillis() - disconnectedAt > 60_000) {
                        // Events from while the connection was down may not be redelivered.
                        launch { requestUpdates() }
                    }
                    if (firstConnect) {
                        firstConnect = false
                        launch { postConnect() }
                    }
                    readStream(call, resp)
                }
                disconnectedAt = System.currentTimeMillis()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if ((e as? GmHttpException)?.fatal == true) {
                    Log.e(TAG, "Long poll rejected", e)
                    if (loggedIn) onEvent(GmEvent.Fatal(e))
                    return@coroutineScope
                }
                failures++
                if (loggedIn) onEvent(GmEvent.ConnectionTrouble(e))
                val wait = minOf((failures + 1) * 5_000L, 60_000L)
                Log.w(TAG, "Long poll failed; retrying in ${wait / 1000}s", e)
                delay(wait)
            }
        }
    }

    /** Reads payloads until the server ends the response or goes quiet; cancelling the job closes the socket. */
    private suspend fun readStream(call: Call, resp: Response) = coroutineScope {
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val reader = LongPollReader(resp.body!!.byteStream())
            while (isActive) {
                val json = try {
                    reader.next()
                } catch (e: IOException) {
                    Log.d(TAG, "Long poll stream ended: ${e.message}")
                    null
                } ?: break
                val payload = try {
                    Pblite.decode<LongPollingPayload>(json, LongPollingPayload.newBuilder())
                } catch (e: Exception) {
                    Log.w(TAG, "Unparseable long-poll payload", e)
                    continue
                }
                when {
                    payload.hasData() -> handleRpc(payload.data)
                    payload.hasAck() -> skipCount.set(payload.ack.count)
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun postConnect() {
        delay(2_000)
        repeat(3) { if (skipCount.get() > 0) delay(1_000) }
        sendAcks()
        delay(1_000)
        try {
            setActiveSession()
            onEvent(GmEvent.Ready)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't set the active session", e)
            onEvent(GmEvent.ConnectionTrouble(e))
        }
    }

    // ---- incoming --------------------------------------------------------------------------------

    private class IncomingRpc(
        val raw: IncomingRPCMessage,
        val data: RPCMessageData? = null,
        val pair: RPCPairData? = null,
        val decrypted: ByteArray? = null,
        val parsed: ProtoMessage? = null,
    ) {
        /** Only set on messages the phone didn't encrypt (Google-account pairing steps). */
        val unencrypted: ByteArray? get() = data?.unencryptedData?.takeIf { !it.isEmpty }?.toByteArray()

        var isOld = false
    }

    private fun handleRpc(raw: IncomingRPCMessage) {
        queueAck(raw.responseID)
        val msg = try {
            decrypt(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't decode incoming ${raw.bugleRoute}", e)
            return
        }
        // Unencrypted messages that aren't Google-pairing steps can arrive ahead of the real (encrypted)
        // response to a request and must not be mistaken for it (libgm ignores them the same way).
        val stray = msg.data != null && msg.data.encryptedData.isEmpty && msg.unencrypted != null &&
            msg.data.action != ActionType.CREATE_GAIA_PAIRING_CLIENT_INIT && msg.data.action != ActionType.CREATE_GAIA_PAIRING_CLIENT_FINISHED
        if (msg.data != null && !stray) {
            waiters.remove(msg.data.sessionID)?.let { waiter ->
                if (msg.data.action != ActionType.NOTIFY_DITTO_ACTIVITY) markPhoneAlive()
                waiter.complete(msg)
                return
            }
        }
        when (raw.bugleRoute) {
            BugleRoute.PairEvent -> when {
                msg.pair?.hasPaired() == true -> completePairing(msg.pair.paired)
                msg.pair?.hasRevoked() == true -> onEvent(GmEvent.Unpaired)
                else -> Unit
            }
            BugleRoute.DataEvent -> {
                if (skipCount.getAndUpdate { if (it > 0) it - 1 else it } > 0) msg.isOld = true
                if (!msg.isOld) markPhoneAlive()
                handleUpdates(msg)
            }
            else -> Unit
        }
    }

    private fun decrypt(raw: IncomingRPCMessage): IncomingRpc = when (raw.bugleRoute) {
        BugleRoute.PairEvent -> IncomingRpc(raw, pair = RPCPairData.parseFrom(raw.messageData))
        BugleRoute.DataEvent -> {
            val data = RPCMessageData.parseFrom(raw.messageData)
            if (data.encryptedData.isEmpty) {
                IncomingRpc(raw, data = data)
            } else {
                val plain = auth.crypto.decrypt(data.encryptedData.toByteArray())
                val parsed = responseBuilder(data.action)?.mergeFrom(plain)?.build()
                IncomingRpc(raw, data = data, decrypted = plain, parsed = parsed)
            }
        }
        else -> IncomingRpc(raw)
    }

    private fun responseBuilder(action: ActionType): ProtoMessage.Builder? = when (action) {
        ActionType.IS_BUGLE_DEFAULT -> IsBugleDefaultResponse.newBuilder()
        ActionType.GET_UPDATES -> UpdateEvents.newBuilder()
        ActionType.LIST_CONVERSATIONS -> ListConversationsResponse.newBuilder()
        ActionType.NOTIFY_DITTO_ACTIVITY -> NotifyDittoActivityResponse.newBuilder()
        ActionType.GET_CONVERSATION -> GetConversationResponse.newBuilder()
        ActionType.LIST_MESSAGES -> ListMessagesResponse.newBuilder()
        ActionType.SEND_MESSAGE -> SendMessageResponse.newBuilder()
        ActionType.SEND_REACTION -> SendReactionResponse.newBuilder()
        ActionType.DELETE_MESSAGE -> DeleteMessageResponse.newBuilder()
        ActionType.GET_FULL_SIZE_IMAGE -> GetFullSizeImageResponse.newBuilder()
        ActionType.GET_PARTICIPANTS_THUMBNAIL, ActionType.GET_CONTACTS_THUMBNAIL -> GetThumbnailResponse.newBuilder()
        ActionType.LIST_CONTACTS -> ListContactsResponse.newBuilder()
        ActionType.LIST_TOP_CONTACTS -> ListTopContactsResponse.newBuilder()
        ActionType.GET_OR_CREATE_CONVERSATION -> GetOrCreateConversationResponse.newBuilder()
        ActionType.UPDATE_CONVERSATION -> UpdateConversationResponse.newBuilder()
        else -> null
    }

    private fun handleUpdates(msg: IncomingRpc) {
        val data = msg.data ?: return
        if (data.action != ActionType.GET_UPDATES) return
        if (msg.parsed == null && msg.unencrypted?.contentEquals(LOGGED_OUT_MARKER) == true) {
            // A Google-account pairing was removed on the phone.
            onEvent(GmEvent.Unpaired)
            return
        }
        if (!msg.isOld) nextDataCheckMs = System.currentTimeMillis() + DATA_CHECK_INTERVAL_MS
        val updates = msg.parsed as? UpdateEvents ?: return
        when (updates.eventCase) {
            UpdateEvents.EventCase.USERALERTEVENT -> if (!msg.isOld) onEvent(GmEvent.Alert(updates.userAlertEvent.alertType))
            UpdateEvents.EventCase.SETTINGSEVENT -> onEvent(GmEvent.SettingsUpdate(updates.settingsEvent))
            UpdateEvents.EventCase.CONVERSATIONEVENT -> for (conv in updates.conversationEvent.dataList) {
                val c = withIds(conv)
                if (isDuplicate(c.conversationID, msg.decrypted)) return
                if (!msg.isOld) onEvent(GmEvent.ConversationUpdate(c))
            }
            UpdateEvents.EventCase.MESSAGEEVENT -> for (m in updates.messageEvent.dataList) {
                val mm = withIds(m)
                if (isDuplicate(mm.messageID, msg.decrypted)) return
                onEvent(GmEvent.MessageUpdate(mm, msg.isOld))
            }
            UpdateEvents.EventCase.TYPINGEVENT -> if (!msg.isOld) onEvent(GmEvent.Typing(updates.typingEvent.data))
            UpdateEvents.EventCase.BROWSERPRESENCECHECKEVENT -> scope.launch {
                runCatching { sendNoResponse(ActionType.ACK_BROWSER_PRESENCE, null) }
            }
            else -> Unit
        }
    }

    /** The phone re-sends identical updates; skip one seen among the last few. */
    private fun isDuplicate(id: String, data: ByteArray?): Boolean {
        if (data == null) return false
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(data))
        synchronized(recentUpdates) {
            val last = recentUpdates.lastOrNull { it.first == id }
            if (last?.second == hash) return true
            recentUpdates.addLast(id to hash)
            while (recentUpdates.size > 8) recentUpdates.removeFirst()
        }
        return false
    }

    // ---- outgoing ----------------------------------------------------------------------------------

    private fun buildRpc(
        action: ActionType,
        data: ProtoMessage?,
        requestId: String = uuid(),
        omitTtl: Boolean = false,
        messageType: MessageType = MessageType.BUGLE_MESSAGE,
        encrypt: Boolean = true,
        ttlMicros: Long? = null,
    ): OutgoingRPCMessage {
        val inner = OutgoingRPCData.newBuilder()
            .setRequestID(requestId)
            .setAction(action)
            .setSessionID(sessionId)
        if (data != null) {
            if (encrypt) inner.encryptedProtoData = ByteString.copyFrom(auth.crypto.encrypt(data.toByteArray()))
            else inner.unencryptedProtoData = data.toByteString()
        }
        val creds = auth
        return OutgoingRPCMessage.newBuilder()
            .apply { creds.mobile?.let { mobile = it } }
            .setData(
                OutgoingRPCMessage.Data.newBuilder()
                    .setRequestID(requestId)
                    .setBugleRoute(BugleRoute.DataEvent)
                    .setMessageData(inner.build().toByteString())
                    .setMessageTypeData(
                        OutgoingRPCMessage.Data.Type.newBuilder().setEmptyArr(EmptyArr.getDefaultInstance()).setMessageType(messageType),
                    ),
            )
            .setAuth(
                OutgoingRPCMessage.Auth.newBuilder()
                    .setRequestID(requestId)
                    .setTachyonAuthToken(creds.tachyonToken ?: ByteString.EMPTY)
                    .setConfigVersion(Gm.configVersion),
            )
            .apply {
                when {
                    ttlMicros != null -> ttl = ttlMicros
                    !omitTtl -> ttl = creds.tachyonTtlMicros
                }
                creds.destRegId?.let { addDestRegistrationIDs(it) }
            }
            .build()
    }

    private suspend fun post(rpc: OutgoingRPCMessage) {
        call<OutgoingRPCResponse>(Gm.SEND_MESSAGE, rpc, Gm.PBLITE, OutgoingRPCResponse.newBuilder())
    }

    private suspend fun sendNoResponse(action: ActionType, data: ProtoMessage?, requestId: String = uuid(), omitTtl: Boolean = false) {
        post(buildRpc(action, data, requestId, omitTtl))
    }

    private suspend fun sendAsync(
        action: ActionType,
        data: ProtoMessage?,
        messageType: MessageType = MessageType.BUGLE_MESSAGE,
        encrypt: Boolean = true,
        ttlMicros: Long? = null,
    ): Pair<String, CompletableDeferred<IncomingRpc>> {
        val rpc = buildRpc(action, data, messageType = messageType, encrypt = encrypt, ttlMicros = ttlMicros)
        val requestId = rpc.data.requestID
        val waiter = CompletableDeferred<IncomingRpc>()
        waiters[requestId] = waiter
        try {
            post(rpc)
        } catch (e: Exception) {
            waiters.remove(requestId)
            throw e
        }
        return requestId to waiter
    }

    /** Sends a request to the phone and waits for its answer, which arrives over the long poll. */
    private suspend fun request(
        action: ActionType,
        data: ProtoMessage?,
        userInitiated: Boolean = false,
        messageType: MessageType = MessageType.BUGLE_MESSAGE,
    ): IncomingRpc {
        val (requestId, waiter) = sendAsync(action, data, messageType)
        try {
            if (userInitiated) {
                withTimeoutOrNull(5_000) { waiter.await() }?.let { return it }
                // The user is waiting: check on the phone now rather than at the next scheduled ping.
                pingNow.trySend(Unit)
            }
            return withTimeoutOrNull(RESPONSE_TIMEOUT_MS) { waiter.await() } ?: throw PhoneNotRespondingException()
        } finally {
            waiters.remove(requestId)
        }
    }

    private inline fun <reified T : ProtoMessage> IncomingRpc.typed(): T =
        parsed as? T ?: throw GmException("Unexpected response to ${data?.action}")

    private suspend fun setActiveSession() {
        sessionId = uuid()
        sendNoResponse(ActionType.GET_UPDATES, null, requestId = sessionId, omitTtl = true)
    }

    /** Takes over from another Messages for Web session that became active. */
    suspend fun claimActiveSession() = setActiveSession()

    private suspend fun requestUpdates() {
        runCatching { sendNoResponse(ActionType.GET_UPDATES, null, requestId = sessionId, omitTtl = true) }
            .onFailure { Log.w(TAG, "GET_UPDATES failed", it) }
    }

    private fun queueAck(id: String) {
        if (id.isNotEmpty()) synchronized(pendingAcks) { pendingAcks += id }
    }

    private suspend fun sendAcks() {
        val ids = synchronized(pendingAcks) { pendingAcks.toList().also { pendingAcks.clear() } }
        if (ids.isEmpty()) return
        val browser = auth.browser
        val req = AckMessageRequest.newBuilder()
            .setAuthData(authMessage())
            .setEmptyArr(EmptyArr.getDefaultInstance())
            .addAllAcks(ids.map { id -> AckMessageRequest.Message.newBuilder().setRequestID(id).apply { browser?.let { device = it } }.build() })
            .build()
        try {
            call<OutgoingRPCResponse>(Gm.ACK_MESSAGES, req, Gm.PBLITE, OutgoingRPCResponse.newBuilder())
        } catch (e: Exception) {
            // Unacked messages can stall delivery, so retry them next round.
            synchronized(pendingAcks) { if (pendingAcks.size + ids.size <= 1024) pendingAcks.addAll(ids) }
            Log.w(TAG, "Acks failed", e)
        }
    }

    // ---- phone liveness ------------------------------------------------------------------------------

    private suspend fun pinger() = coroutineScope {
        var setActiveTried = false
        while (isActive) {
            val urgent = withTimeoutOrNull(pingIntervalMs) { pingNow.receive() } != null
            val ok = ping(if (urgent) 10_000 else 60_000)
            if (ok) {
                setActiveTried = false
            } else {
                pingTimeouts++
                if (!reportedDown && pingTimeouts >= ALERT_AFTER_TIMEOUTS) {
                    reportedDown = true
                    onEvent(GmEvent.PhoneResponding(false))
                }
                when {
                    pingTimeouts >= RECONNECT_AFTER_TIMEOUTS -> {
                        pingIntervalMs = minOf(pingIntervalMs * 2, MAX_PING_INTERVAL_MS)
                        Log.w(TAG, "Phone silent for $pingTimeouts pings; reconnecting")
                        scope.launch { startPolling(loggedIn = true) }
                        return@coroutineScope
                    }
                    pingTimeouts >= 2 && !setActiveTried -> {
                        setActiveTried = true
                        runCatching { setActiveSession() }
                    }
                }
                pingIntervalMs = minOf(pingIntervalMs * 2, MAX_PING_INTERVAL_MS)
            }
            if (System.currentTimeMillis() > nextDataCheckMs) {
                nextDataCheckMs = System.currentTimeMillis() + DATA_CHECK_INTERVAL_MS
                launch { requestUpdates() }
            }
        }
    }

    private suspend fun ping(timeoutMs: Long): Boolean {
        val (requestId, waiter) = try {
            sendAsync(ActionType.NOTIFY_DITTO_ACTIVITY, NotifyDittoActivityRequest.newBuilder().setSuccess(true).build())
        } catch (e: Exception) {
            Log.w(TAG, "Ping failed to send", e)
            return false
        }
        val answered = try {
            withTimeoutOrNull(timeoutMs) { waiter.await() } != null
        } catch (e: GmException) {
            false
        } finally {
            waiters.remove(requestId)
        }
        if (answered) markPhoneAlive()
        return answered
    }

    private fun markPhoneAlive() {
        val wasFailing = pingTimeouts > 0 || reportedDown
        val needsCatchUp = pingTimeouts >= 2 || reportedDown
        pingTimeouts = 0
        pingIntervalMs = PING_INTERVAL_MS
        if (reportedDown) {
            reportedDown = false
            onEvent(GmEvent.PhoneResponding(true))
        }
        if (wasFailing && needsCatchUp) scope.launch { requestUpdates() }
    }

    // ---- requests -------------------------------------------------------------------------------------

    suspend fun listConversations(count: Int, folder: ListConversationsRequest.Folder = ListConversationsRequest.Folder.INBOX, cursor: Cursor? = null): ListConversationsResponse {
        val req = ListConversationsRequest.newBuilder().setCount(count.toLong()).setFolder(folder).apply { cursor?.let { setCursor(it) } }.build()
        // The web app's first listing of a session goes out as an annotation.
        val type = if (conversationsFetched) MessageType.BUGLE_MESSAGE else MessageType.BUGLE_ANNOTATION
        conversationsFetched = true
        val resp = request(ActionType.LIST_CONVERSATIONS, req, messageType = type).typed<ListConversationsResponse>()
        return resp.toBuilder().clearConversations().addAllConversations(resp.conversationsList.map(::withIds)).build()
    }

    suspend fun getConversation(conversationId: String): Conversation? =
        request(ActionType.GET_CONVERSATION, GetConversationRequest.newBuilder().setConversationID(conversationId).build())
            .typed<GetConversationResponse>().takeIf { it.hasConversation() }?.conversation?.let(::withIds)

    /** Newest first. Pass the previous response's cursor for older messages. */
    suspend fun fetchMessages(conversationId: String, count: Int, cursor: Cursor? = null): ListMessagesResponse {
        val req = ListMessagesRequest.newBuilder().setConversationID(conversationId).setCount(count.toLong()).apply { cursor?.let { setCursor(it) } }.build()
        val resp = request(ActionType.LIST_MESSAGES, req).typed<ListMessagesResponse>()
        return resp.toBuilder().clearMessages().addAllMessages(resp.messagesList.map(::withIds)).build()
    }

    suspend fun getOrCreateConversation(numbers: List<String>): Conversation {
        val req = GetOrCreateConversationRequest.newBuilder()
            .addAllNumbers(numbers.map { ContactNumber.newBuilder().setMysteriousInt(2).setNumber(it).setNumber2(it).build() })
        var resp = request(ActionType.GET_OR_CREATE_CONVERSATION, req.build(), userInitiated = true).typed<GetOrCreateConversationResponse>()
        if (resp.status == GetOrCreateConversationResponse.Status.CREATE_RCS) {
            // A group of RCS users: the phone wants confirmation to create an RCS group.
            req.setRCSGroupName("").setCreateRCSGroup(true)
            resp = request(ActionType.GET_OR_CREATE_CONVERSATION, req.build(), userInitiated = true).typed()
        }
        if (!resp.hasConversation() || withIds(resp.conversation).conversationID.isEmpty()) {
            throw GmException("Google Messages didn't open a conversation (${resp.status})")
        }
        return withIds(resp.conversation)
    }

    suspend fun sendMessage(req: SendMessageRequest): SendMessageResponse =
        request(ActionType.SEND_MESSAGE, req, userInitiated = true).typed()

    suspend fun sendReaction(req: SendReactionRequest): SendReactionResponse =
        request(ActionType.SEND_REACTION, req, userInitiated = true).typed()

    suspend fun markRead(conversationId: String, messageId: String) {
        request(ActionType.MESSAGE_READ, MessageReadRequest.newBuilder().setConversationID(conversationId).setMessageID(messageId).build())
    }

    suspend fun deleteMessage(messageId: String): Boolean =
        request(ActionType.DELETE_MESSAGE, DeleteMessageRequest.newBuilder().setMessageID(messageId).build(), userInitiated = true)
            .typed<DeleteMessageResponse>().success

    /** Asks the phone to make the full-size file available when only a thumbnail came through. */
    suspend fun requestFullSizeImage(messageId: String, actionMessageId: String) {
        request(
            ActionType.GET_FULL_SIZE_IMAGE,
            GetFullSizeImageRequest.newBuilder().setMessageID(messageId).setActionMessageID(actionMessageId).build(),
        )
    }

    suspend fun deleteConversation(conversationId: String, phone: String?) {
        val req = UpdateConversationRequest.newBuilder()
            .setAction(ConversationActionStatus.DELETE)
            .setConversationID(conversationId)
            .setDeleteData(DeleteConversationData.newBuilder().setConversationID(conversationId).apply { if (!phone.isNullOrEmpty()) setPhone(phone) })
            .build()
        request(ActionType.UPDATE_CONVERSATION, req, userInitiated = true)
    }

    suspend fun isBugleDefault(): Boolean = request(ActionType.IS_BUGLE_DEFAULT, null).typed<IsBugleDefaultResponse>().success

    // ---- media --------------------------------------------------------------------------------------

    suspend fun uploadMedia(data: ByteArray, fileName: String, mime: String): MediaContent {
        val key = randomBytes(32)
        val encrypted = MediaCrypto.encrypt(key, data)
        val start = StartMediaUploadRequest.newBuilder()
            .setAttachmentType(1)
            .setAuthData(authMessage())
            .apply { auth.mobile?.let { mobile = it } }
            .build()
        val startReq = Request.Builder().url(Gm.UPLOAD_URL)
            .post(Base64.getEncoder().encodeToString(start.toByteArray()).toByteArray().toRequestBody(FORM))
            .apply { uploadHeaders(encrypted.size, "start", null, mime, "resumable") }
            .build()
        val uploadUrl = http.newCall(startReq).await().use { resp ->
            if (!resp.isSuccessful) throw GmHttpException(resp.code, null)
            resp.header("x-goog-upload-url") ?: throw GmException("No upload URL")
        }
        val finishReq = Request.Builder().url(uploadUrl)
            .post(encrypted.toRequestBody(FORM))
            .apply { uploadHeaders(encrypted.size, "upload, finalize", "0", mime, null) }
            .build()
        val uploaded = http.newCall(finishReq).await().use { resp ->
            if (!resp.isSuccessful) throw GmHttpException(resp.code, null)
            var body = resp.body!!.bytes()
            val text = String(body, Charsets.ISO_8859_1)
            if (text.length % 4 == 0 && text.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }) {
                body = Base64.getDecoder().decode(text)
            }
            UploadMediaResponse.parseFrom(body)
        }
        return MediaContent.newBuilder()
            .setFormat(mediaFormat(mime))
            .setMediaID(uploaded.media.mediaID)
            .setMediaName(fileName)
            .setSize(data.size.toLong())
            .setDecryptionKey(ByteString.copyFrom(key))
            .setMimeType(mime)
            .build()
    }

    suspend fun downloadMedia(mediaId: String, key: ByteArray, out: OutputStream) {
        val meta = DownloadAttachmentRequest.newBuilder()
            .setInfo(AttachmentInfo.newBuilder().setAttachmentID(mediaId).setEncrypted(true))
            .setAuthData(authMessage())
            .build()
        val req = Request.Builder().url(Gm.UPLOAD_URL).get()
            .header("x-goog-download-metadata", Base64.getEncoder().encodeToString(meta.toByteArray()))
            .apply { browserHeaders() }
            .build()
        http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) throw GmHttpException(resp.code, null)
            MediaCrypto.decrypt(key, resp.body!!.byteStream(), out)
        }
    }

    private fun mediaFormat(mime: String): MediaFormats = when (mime.lowercase()) {
        "image/jpeg" -> MediaFormats.IMAGE_JPEG
        "image/jpg" -> MediaFormats.IMAGE_JPG
        "image/png" -> MediaFormats.IMAGE_PNG
        "image/gif" -> MediaFormats.IMAGE_GIF
        "image/bmp", "image/x-ms-bmp" -> MediaFormats.IMAGE_X_MS_BMP
        "video/mp4" -> MediaFormats.VIDEO_MP4
        "video/3gpp" -> MediaFormats.VIDEO_3GPP
        "video/3gpp2" -> MediaFormats.VIDEO_3G2
        "video/webm" -> MediaFormats.VIDEO_WEBM
        "video/x-matroska" -> MediaFormats.VIDEO_MKV
        "audio/aac" -> MediaFormats.AUDIO_AAC
        "audio/amr" -> MediaFormats.AUDIO_AMR
        "audio/mp3", "audio/mpeg" -> MediaFormats.AUDIO_MPEG
        "audio/mp4" -> MediaFormats.AUDIO_MP4
        "audio/ogg" -> MediaFormats.AUDIO_OGG
        "text/vcard", "text/x-vcard" -> MediaFormats.TEXT_VCARD
        "application/pdf" -> MediaFormats.APP_PDF
        "text/plain" -> MediaFormats.APP_TXT
        else -> when (mime.substringBefore('/')) {
            "image" -> MediaFormats.IMAGE_UNSPECIFIED
            "video" -> MediaFormats.VIDEO_UNSPECIFIED
            "audio" -> MediaFormats.AUDIO_UNSPECIFIED
            else -> MediaFormats.APP_UNSPECIFIED
        }
    }

    // ---- http ------------------------------------------------------------------------------------------

    private fun Request.Builder.browserHeaders() = apply {
        header("sec-ch-ua", Gm.SEC_UA)
        header("sec-ch-ua-mobile", "?1")
        header("sec-ch-ua-platform", "\"Android\"")
        header("user-agent", Gm.USER_AGENT)
        header("accept", "*/*")
        header("origin", "https://messages.google.com")
        header("sec-fetch-site", "cross-site")
        header("sec-fetch-mode", "cors")
        header("sec-fetch-dest", "empty")
        header("referer", "https://messages.google.com/")
        header("accept-language", "en-US,en;q=0.9")
    }

    private fun Request.Builder.uploadHeaders(size: Int, command: String, offset: String?, mime: String, protocol: String?) = apply {
        browserHeaders()
        protocol?.let { header("x-goog-upload-protocol", it) }
        header("x-goog-upload-header-content-length", size.toString())
        header("x-goog-upload-header-content-type", mime)
        header("x-goog-upload-command", command)
        offset?.let { header("x-goog-upload-offset", it) }
    }

    private fun relayRequest(url: String, body: ByteArray, contentType: String): Request =
        Request.Builder().url(url)
            .post(body.toRequestBody(contentType.toMediaType()))
            .browserHeaders()
            .header("x-user-agent", "grpc-web-javascript/0.1")
            .header("x-goog-api-key", Gm.API_KEY)
            .apply { addCookies(this) }
            .build()

    /** Google-account pairings authenticate as the signed-in user, like the web app does. */
    private fun addCookies(builder: Request.Builder) {
        val cookies = synchronized(auth.cookies) { HashMap(auth.cookies) }
        if (cookies.isEmpty()) return
        builder.header("cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        cookies["SAPISID"]?.let { builder.header("authorization", sapisidHash(it)) }
    }

    private fun updateCookies(resp: Response) {
        if (auth.cookies.isEmpty()) return
        var changed = false
        for (header in resp.headers("set-cookie")) {
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (auth.cookies.put(name, value) != value) changed = true
        }
        if (changed) onAuthChanged()
    }

    private suspend fun <T : ProtoMessage> call(url: String, payload: ProtoMessage, contentType: String, response: ProtoMessage.Builder): T {
        val body = if (contentType == Gm.PBLITE) Pblite.encode(payload).toByteArray() else payload.toByteArray()
        var attempt = 1
        while (true) {
            val resp = http.newCall(relayRequest(url, body, contentType)).await()
            updateCookies(resp)
            resp.use {
                if (resp.code >= 500 && attempt < 3) {
                    delay(attempt * 1_000L)
                    attempt++
                    return@use
                }
                if (!resp.isSuccessful) throw GmHttpException(resp.code, parseError(resp))
                decodeInto(resp, response)
                @Suppress("UNCHECKED_CAST")
                return response.build() as T
            }
        }
    }

    private fun decodeInto(resp: Response, builder: ProtoMessage.Builder) {
        val bytes = resp.body!!.bytes()
        when (resp.header("content-type").orEmpty().substringBefore(';').trim().lowercase()) {
            Gm.PROTOBUF -> builder.mergeFrom(bytes)
            Gm.PBLITE, "text/plain", "application/json" -> Pblite.fromArray(
                kotlinx.serialization.json.Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as kotlinx.serialization.json.JsonArray,
                builder,
            )
            else -> if (bytes.isNotEmpty()) builder.mergeFrom(bytes)
        }
    }

    private fun parseError(resp: Response): ErrorResponse? = runCatching {
        ErrorResponse.newBuilder().also { decodeInto(resp, it) }.build().takeIf { it.message.isNotEmpty() }
    }.getOrNull()

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        private const val TAG = "GmClient"
        private val FORM = "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()
        private const val REFRESH_BUFFER_MS = 60 * 60_000L
        private const val RESPONSE_TIMEOUT_MS = 60_000L
        private const val PING_INTERVAL_MS = 60_000L
        private const val MAX_PING_INTERVAL_MS = 64 * 60_000L
        private const val ALERT_AFTER_TIMEOUTS = 4
        private const val RECONNECT_AFTER_TIMEOUTS = 3
        private const val DATA_CHECK_INTERVAL_MS = (2 * 60 + 55) * 60_000L

        private fun uuid() = UUID.randomUUID().toString()

        /** What the phone sends instead of updates once a Google-account pairing is removed. */
        private val LOGGED_OUT_MARKER = byteArrayOf(0x72, 0x00)
        private val GAIA_TTL_MICROS = TimeUnit.SECONDS.toMicros(300)
        private const val PRIVATE_API_CONFIRMATION = "This is an undocumented API. Use or access of undocumented Google APIs without " +
            "express authorization is prohibited per the Google API Terms of Service (https://developers.google.com/terms)."

        fun sapisidHash(sapisid: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
            val digest = MessageDigest.getInstance("SHA-1").digest("$nowSeconds $sapisid ${Gm.MESSAGES_ORIGIN}".toByteArray())
            return "SAPISIDHASH ${nowSeconds}_" + digest.joinToString("") { "%02x".format(it) }
        }

        fun withIds(conv: Conversation): Conversation =
            if (conv.conversationID.isNotEmpty() && conv.latestMessageID.isNotEmpty()) conv
            else conv.toBuilder().apply {
                if (conversationID.isEmpty()) conversationID = conv.someKindOfGroupID.conversationID
                if (latestMessageID.isEmpty()) latestMessageID = conv.latestMessageAnotherID.messageID
            }.build()

        fun withIds(msg: GmMessage): GmMessage =
            if (msg.messageID.isNotEmpty() && msg.conversationID.isNotEmpty()) msg
            else msg.toBuilder().apply {
                if (messageID.isEmpty()) messageID = msg.anotherMessageID.messageID
                if (conversationID.isEmpty()) conversationID = msg.someKindOfGroupID.conversationID
            }.build()
    }
}
