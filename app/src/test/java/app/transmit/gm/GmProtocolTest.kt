package app.transmit.gm

import app.transmit.gm.proto.ActionType
import app.transmit.gm.proto.AuthMessage
import app.transmit.gm.proto.BugleRoute
import app.transmit.gm.proto.ConfigVersion
import app.transmit.gm.proto.Conversation
import app.transmit.gm.proto.CustomEmojiData
import app.transmit.gm.proto.EmojiType
import app.transmit.gm.proto.LongPollingPayload
import app.transmit.gm.proto.MessageStatus
import app.transmit.gm.proto.MessageStatusType
import app.transmit.gm.proto.OutgoingRPCMessage
import app.transmit.gm.proto.Participant
import app.transmit.gm.proto.ReactionData
import app.transmit.gm.proto.ReactionEntry
import app.transmit.gm.proto.ReplyMessage
import app.transmit.gm.proto.SmallInfo
import app.transmit.gm.proto.SomeKindOfGroupID
import app.transmit.gm.proto.EcP256PublicKey
import app.transmit.gm.proto.GaiaPairingResponseContainer
import app.transmit.gm.proto.GenericPublicKey
import app.transmit.gm.proto.PublicKeyType
import app.transmit.gm.proto.Ukey2ClientFinished
import app.transmit.gm.proto.Ukey2ClientInit
import app.transmit.gm.proto.Ukey2HandshakeCipher
import app.transmit.gm.proto.Ukey2Message
import app.transmit.gm.proto.Ukey2ServerInit
import app.transmit.messaging.DeliveryStatus
import app.transmit.messaging.Photomoji
import com.google.protobuf.ByteString
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import app.transmit.gm.proto.Message as GmMessage

class GmProtocolTest {
    // ---- pblite --------------------------------------------------------------------------------

    @Test
    fun pbliteEncodesFieldsByPosition() {
        val msg = AuthMessage.newBuilder()
            .setRequestID("req")
            .setNetwork("Bugle")
            .setConfigVersion(ConfigVersion.newBuilder().setYear(2026).setMonth(9).setDay(2).setV1(4).setV2(6))
            .build()
        // requestID=1, network=3, configVersion=7; gaps are null.
        assertEquals("""["req",null,"Bugle",null,null,null,[null,null,2026,9,2,null,4,null,6]]""", Pblite.encode(msg))
    }

    @Test
    fun pbliteRoundTripsNestedMessagesBytesAndEnums() {
        val original = OutgoingRPCMessage.newBuilder()
            .setData(
                OutgoingRPCMessage.Data.newBuilder()
                    .setRequestID("abc")
                    .setBugleRoute(BugleRoute.DataEvent)
                    .setMessageData(ByteString.copyFrom(byteArrayOf(0, 1, 2, -1))),
            )
            .setAuth(OutgoingRPCMessage.Auth.newBuilder().setRequestID("abc").setTachyonAuthToken(ByteString.copyFromUtf8("token")))
            .setTTL(86_400_000_000L)
            .build()
        val decoded: OutgoingRPCMessage = Pblite.decode(Pblite.encode(original), OutgoingRPCMessage.newBuilder())
        assertEquals(original, decoded)
    }

    @Test
    fun pbliteAcceptsInt64AsStringsAndTrailingFieldObjects() {
        // ack (field 4) given in a trailing {"4": ...} object, with its int count as a string.
        val payload: LongPollingPayload = Pblite.decode("""[null,null,null,{"4":[ "7" ]}]""", LongPollingPayload.newBuilder())
        assertTrue(payload.hasAck())
        assertEquals(7, payload.ack.count)
    }

    @Test
    fun pbliteRejectsWrongShapes() {
        assertThrows(Exception::class.java) { Pblite.decode<AuthMessage>("""[["not a string"]]""", AuthMessage.newBuilder()) }
    }

    // ---- crypto --------------------------------------------------------------------------------

    @Test
    fun requestCryptoRoundTripsAndChecksTheMac() {
        val crypto = RequestCrypto.generate()
        val plain = "hello from the phone".toByteArray()
        val sealed = crypto.encrypt(plain)
        assertEquals(plain.size + 16 + 32, sealed.size)
        assertArrayEquals(plain, crypto.decrypt(sealed))
        sealed[0] = (sealed[0].toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { crypto.decrypt(sealed) }
    }

    @Test
    fun mediaCryptoRoundTripsAcrossChunkBoundaries() {
        val key = randomBytes(32)
        for (size in listOf(0, 1, 32_768 - 28, 32_768 - 27, 100_000)) {
            val data = ByteArray(size) { (it * 31).toByte() }
            val out = ByteArrayOutputStream()
            MediaCrypto.decrypt(key, ByteArrayInputStream(MediaCrypto.encrypt(key, data)), out)
            assertArrayEquals("size $size", data, out.toByteArray())
        }
    }

    @Test
    fun mediaCryptoRejectsTruncatedFiles() {
        val key = randomBytes(32)
        val sealed = MediaCrypto.encrypt(key, ByteArray(70_000) { it.toByte() })
        val truncated = sealed.copyOf(sealed.size - 32_768)
        assertThrows(Exception::class.java) { MediaCrypto.decrypt(key, ByteArrayInputStream(truncated), ByteArrayOutputStream()) }
    }

    @Test
    fun refreshKeySignaturesVerifyAndSurviveReload() {
        val key = RefreshKey.generate()
        val reloaded = RefreshKey.load(key.privateKeyPkcs8, key.publicKeyX509)
        val data = "req:1700000000000000".toByteArray()
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key.publicKeyX509))
        for (sig in listOf(key.sign(data), reloaded.sign(data))) {
            assertTrue(Signature.getInstance("SHA256withECDSA").apply { initVerify(pub); update(data) }.verify(sig))
        }
    }

    // ---- long poll -----------------------------------------------------------------------------

    @Test
    fun longPollReaderSplitsTopLevelPayloads() {
        val stream = """[[[null,["a]b\"c",[1,2]]],[null,null,[]] ,[null,null,null,[3]]]"""
        val reader = LongPollReader(ByteArrayInputStream(stream.toByteArray()))
        assertEquals("""[null,["a]b\"c",[1,2]]]""", reader.next())
        assertEquals("[null,null,[]]", reader.next())
        assertEquals("[null,null,null,[3]]", reader.next())
        assertNull(reader.next())
    }

    @Test
    fun longPollReaderStopsAtEof() {
        val reader = LongPollReader(ByteArrayInputStream("[[[1],[2".toByteArray()))
        assertEquals("[1]", reader.next())
        assertNull(reader.next())
    }

    // ---- mapping -------------------------------------------------------------------------------

    @Test
    fun threadIdsAreStableAndOffsetFromSmsThreads() {
        assertEquals(10_000_000_042L, GmMapping.threadId("42"))
        assertEquals(GmMapping.threadId("x-1"), GmMapping.threadId("x-1"))
        assertTrue(GmMapping.threadId("x-1") > 10_000_000_000L)
    }

    @Test
    fun statusesMapToDirectionAndDelivery() {
        fun msg(s: MessageStatusType) = GmMessage.newBuilder().setMessageStatus(MessageStatus.newBuilder().setStatus(s)).build()
        assertTrue(GmMapping.isOutgoing(msg(MessageStatusType.OUTGOING_SENDING)))
        assertFalse(GmMapping.isOutgoing(msg(MessageStatusType.INCOMING_COMPLETE)))
        assertEquals(DeliveryStatus.READ, GmMapping.deliveryStatus(msg(MessageStatusType.OUTGOING_DISPLAYED)))
        assertEquals(DeliveryStatus.DELIVERED, GmMapping.outgoingStatus(MessageStatusType.OUTGOING_DELIVERED))
        assertEquals(DeliveryStatus.SENT, GmMapping.outgoingStatus(MessageStatusType.OUTGOING_NOT_DELIVERED_YET))
        assertEquals(DeliveryStatus.SENDING, GmMapping.outgoingStatus(MessageStatusType.OUTGOING_YET_TO_SEND))
        assertEquals(DeliveryStatus.FAILED, GmMapping.deliveryStatus(msg(MessageStatusType.OUTGOING_FAILED_TOO_LARGE)))
        assertEquals(DeliveryStatus.RECEIVED, GmMapping.deliveryStatus(msg(MessageStatusType.INCOMING_COMPLETE)))
        assertTrue(GmMapping.isNotice(msg(MessageStatusType.TOMBSTONE_RCS_GROUP_CREATED)))
        assertTrue(GmMapping.isDeleted(msg(MessageStatusType.MESSAGE_DELETED)))
    }

    @Test
    fun repliesPointAtTheirOriginal() {
        val reply = GmMessage.newBuilder().setMessageID("8").setReplyMessage(ReplyMessage.newBuilder().setMessageID("5").setConversationID("2")).build()
        assertEquals("5", GmMapping.replyTo(reply))
        assertNull(GmMapping.replyTo(GmMessage.newBuilder().setMessageID("8").build()))
        // An empty reference, as the phone sometimes leaves on plain messages, isn't a reply.
        assertNull(GmMapping.replyTo(GmMessage.newBuilder().setReplyMessage(ReplyMessage.getDefaultInstance()).build()))
    }

    @Test
    fun onlyRcsMessagesOnThePhoneCanBeRepliedTo() {
        fun msg(type: Long, status: MessageStatusType = MessageStatusType.INCOMING_COMPLETE, id: String = "5") =
            GmMessage.newBuilder().setMessageID(id).setType(type).setMessageStatus(MessageStatus.newBuilder().setStatus(status)).build()
        assertTrue(GmMapping.canReplyTo(msg(4)))
        assertTrue(GmMapping.canReplyTo(msg(4, MessageStatusType.OUTGOING_DELIVERED)))
        // SMS, downloaded MMS and undownloaded MMS can't carry a reply.
        for (type in 1L..3L) assertFalse(GmMapping.canReplyTo(msg(type)))
        // Not on the other person's phone, or not on this one yet.
        assertFalse(GmMapping.canReplyTo(msg(4, MessageStatusType.OUTGOING_FAILED_GENERIC)))
        assertFalse(GmMapping.canReplyTo(msg(4, MessageStatusType.OUTGOING_SENDING)))
        assertFalse(GmMapping.canReplyTo(msg(0, MessageStatusType.OUTGOING_COMPLETE, id = GmBridge.PLACEHOLDER + "abc")))
    }

    @Test
    fun missingIdsAreFilledFromAlternateFields() {
        val conv = Conversation.newBuilder().setSomeKindOfGroupID(SomeKindOfGroupID.newBuilder().setConversationID("9")).build()
        assertEquals("9", GmClient.withIds(conv).conversationID)
    }

    @Test
    fun namesComeFromParticipantsOrTheOneToOneConversation() {
        fun person(number: String, fullName: String = "", me: Boolean = false) = Participant.newBuilder()
            .setID(SmallInfo.newBuilder().setNumber(number)).setFullName(fullName).setIsMe(me).setIsVisible(true)
        fun conv(name: String, group: Boolean, vararg people: Participant.Builder) = Conversation.newBuilder()
            .setName(name).setIsGroupChat(group).addParticipants(person("+15550000000", me = true))
            .apply { people.forEach { addParticipants(it) } }.build()

        // A business chat names only the conversation.
        val rbm = "ramp_cf8gd1ek_agent@rbm.goog"
        assertEquals(listOf(rbm to "Ramp"), GmMapping.names(conv("Ramp", false, person(rbm))))
        // A participant's own name wins.
        assertEquals(listOf("+15551234567" to "Ana"), GmMapping.names(conv("Ana B", false, person("+15551234567", "Ana"))))
        // A stranger's chat is named after their number, and an id after itself: no name.
        assertEquals(emptyList<Pair<String, String>>(), GmMapping.names(conv("(555) 123-4567", false, person("+15551234567"))))
        assertEquals(emptyList<Pair<String, String>>(), GmMapping.names(conv(rbm, false, person(rbm, rbm))))
        // A group's name belongs to the group, not to anyone in it.
        assertEquals(
            listOf("+15551234567" to "Ana"),
            GmMapping.names(conv("Friends", true, person("+15551234567", "Ana"), person("+15557654321"))),
        )
    }

    @Test
    fun emojiTypesRoundTrip() {
        for (e in listOf("👍", "😍", "😂", "😮", "😥", "😠", "👎", "🤔", "😢", "😡", "❤️")) {
            assertEquals(e, GmMapping.emoji(GmMapping.emojiType(e)))
        }
        assertEquals(EmojiType.CUSTOM, GmMapping.emojiType("🎉"))
        assertEquals(ActionType.SEND_REACTION, ActionType.valueOf("SEND_REACTION"))
    }

    // ---- reactions

    private fun person(id: String, number: String, me: Boolean = false) = Participant.newBuilder()
        .setID(SmallInfo.newBuilder().setParticipantID(id).setNumber(number)).setIsMe(me).setIsVisible(true)

    private val group = Conversation.newBuilder().setIsGroupChat(true)
        .addParticipants(person("1", "+15550000000", me = true))
        .addParticipants(person("7", "+15551110000"))
        .addParticipants(person("9", "+15552220000"))
        .build()

    private fun entry(data: ReactionData.Builder, vararg ids: String) =
        ReactionEntry.newBuilder().setData(data).addAllParticipantIDs(ids.toList()).build()

    /** Group chats can show who reacted: the phone's participant ids become the people's numbers. */
    @Test
    fun aReactionKnowsWhoReacted() {
        val r = GmMapping.reaction(entry(ReactionData.newBuilder().setUnicode("😂").setType(EmojiType.LAUGH), "7", "1", "9"), group)!!
        assertEquals("😂", r.emoji)
        assertEquals(3, r.count)
        assertTrue(r.mine)
        assertEquals(listOf("+15551110000", "+15552220000"), r.others)
        assertNull(r.photomoji)
        // Someone the chat doesn't list (they left the group) still counts, without a number.
        val gone = GmMapping.reaction(entry(ReactionData.newBuilder().setType(EmojiType.RED_HEART), "7", "42"), group)!!
        assertEquals("❤️", gone.emoji)
        assertEquals(2, gone.count)
        assertFalse(gone.mine)
        assertEquals(listOf("+15551110000"), gone.others)
        // No one: no reaction.
        assertNull(GmMapping.reaction(entry(ReactionData.newBuilder().setUnicode("👍")), group))
    }

    /**
     * Beta9 showed photomoji as their ids ("f65f3565-fbf4-…"): the phone puts a photomoji's id where the emoji goes.
     * The id stays the key (a removal sends it back); the picture is used only when it's on the web.
     */
    @Test
    fun aPhotomojiIsAPictureNotItsId() {
        val id = "f65f3565-fbf4-45f3-a072-7860f23eccdc"
        val bare = GmMapping.reaction(entry(ReactionData.newBuilder().setUnicode(id).setType(EmojiType.CUSTOM), "7"), group)!!
        assertEquals(id, bare.emoji)
        assertEquals(Photomoji(null), bare.photomoji)

        fun custom(sized: String, plain: String) = ReactionData.newBuilder().setUnicode(id).setType(EmojiType.CUSTOM).setCustomEmoji(
            CustomEmojiData.newBuilder().setUuid(id).setInnerData(
                CustomEmojiData.Inner.newBuilder()
                    .setFirst(CustomEmojiData.Inner.ImageData.newBuilder().setMimeType("image/png").setUri(plain))
                    .setSecond(
                        CustomEmojiData.Inner.WrappedImageData.newBuilder()
                            .setData(CustomEmojiData.Inner.WrappedImageData.ImageData.newBuilder().setMimeType("image/webp").setUri(sized)),
                    ),
            ),
        )
        assertEquals(Photomoji("https://example.com/sized"), GmMapping.photomoji(custom("https://example.com/sized", "https://example.com/plain").build()))
        assertEquals(Photomoji("https://example.com/plain"), GmMapping.photomoji(custom("", "https://example.com/plain").build()))
        // A file on the phone can't be fetched from here.
        assertEquals(Photomoji(null), GmMapping.photomoji(custom("content://media/1", "").build()))
        // Logged without the addresses' paths.
        assertEquals(
            "type=CUSTOM custom=true sized=content://media (image/webp) plain=https://example.com (image/png)",
            GmMapping.photomojiSource(custom("content://media/1", "https://example.com/plain?token=secret").build()),
        )
        // An emoji that isn't one of the phone's named types is still an emoji.
        assertNull(GmMapping.photomoji(ReactionData.newBuilder().setUnicode("✨").setType(EmojiType.CUSTOM).build()))
    }

    // ---- Google-account pairing. Expected values come from an independent port of libgm's pair_google.go.

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun hkdfMatchesRfc5869() {
        val okm = GaiaPairingSession.hkdf(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() })
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf", okm.hex())
    }

    @Test
    fun requestKeysMatchLibgm() {
        val next = ByteArray(32) { it.toByte() }
        val v0 = GaiaPairingSession.requestCrypto(next, 0)
        assertEquals("efc82501acf57d8f69ca8057f388f32e3f40dfd560eadb41eb910d49c43cd318", v0.aesKey.hex())
        assertEquals("3974d2c43b41bbbb67587761e3f7a1c1f75f8c61916e443f726dd4e796eb20c2", v0.hmacKey.hex())
        val v1 = GaiaPairingSession.requestCrypto(next, 1)
        assertEquals("5f6ce79dd48af3453f7f24e53268537f7a5962870144de1a52f9ed25765d3a43", v1.aesKey.hex())
        assertEquals("3b0ea4399b37c190dcb52cc976d879424c2713e4b59cdde1b77f84340194c37e", v1.hmacKey.hex())
    }

    @Test
    fun emojiMatchLibgm() {
        assertEquals(305, PairingEmoji.V0.size)
        assertEquals(308, PairingEmoji.V1.size)
        val auth = hex("deadbeef") + ByteArray(28)
        assertEquals("👻", GaiaPairingSession.emojiFor(auth, 0))
        assertEquals("🐑", GaiaPairingSession.emojiFor(auth, 1))
        val auth2 = hex("00000107") + ByteArray(28)
        assertEquals("🖍", GaiaPairingSession.emojiFor(auth2, 0))
        assertEquals("🧬", GaiaPairingSession.emojiFor(auth2, 1))
    }

    /** An account on several phones: only phones count (not this browser), newest first, each once. */
    @Test
    fun gaiaPhonesAreNewestFirst() {
        fun item(id: String, kind: Int) = app.transmit.gm.proto.RPCGaiaData.UnknownContainer.Item2.Item1.newBuilder()
            .setDestOrSourceUUID(id).setUnknownInt4(kind).build()
        fun seen(id: String, micros: Long) = app.transmit.gm.proto.RPCGaiaData.UnknownContainer.Item4.newBuilder()
            .setDestOrSourceUUID(id).setUnknownTimestampMicroseconds(micros).build()
        val resp = app.transmit.gm.proto.SignInGaiaResponse.newBuilder().setDeviceData(
            app.transmit.gm.proto.SignInGaiaResponse.DeviceData.newBuilder()
                .addUnknownItems2(item("old", 1))
                .addUnknownItems2(item("browser", 6))
                .addUnknownItems2(item("new", 1))
                .addUnknownItems2(item("new", 1))
                .addUnknownItems2(item("unseen", 1))
                .addUnknownItems3(seen("old", 1_000))
                .addUnknownItems3(seen("new", 2_000))
                .addUnknownItems3(seen("browser", 3_000)),
        ).build()
        assertEquals(listOf("new", "old", "unseen"), GmClient.gaiaPhones(resp))
        // The phone paired last time comes first while it's still on the account.
        assertEquals(listOf("old", "new", "unseen"), GmClient.gaiaPhones(resp, lastPhone = "old"))
        assertEquals(listOf("new", "old", "unseen"), GmClient.gaiaPhones(resp, lastPhone = "gone"))
    }

    @Test
    fun sapisidHashMatchesTheWebApp() {
        assertEquals("SAPISIDHASH 1700000000_4576fb2c630b144fd71b28f49511349a73a8ea95", GmClient.sapisidHash("abc", 1700000000))
    }

    /** Plays the phone's side of UKEY2 and checks both ends land on the same emoji and keys. */
    @Test
    fun ukey2HandshakeAgreesWithThePhone() {
        val client = GaiaPairingSession("phone")
        val initPayload = client.prepare()
        val init = Ukey2ClientInit.parseFrom(Ukey2Message.parseFrom(initPayload).messageData)
        assertEquals(1, init.version)
        // The commitment binds the CLIENT_INIT to the CLIENT_FINISH sent later.
        assertArrayEquals(
            java.security.MessageDigest.getInstance("SHA-512").digest(client.finishPayload),
            init.getCipherCommitments(0).commitment.toByteArray(),
        )
        val clientPoint = Ukey2ClientFinished.parseFrom(Ukey2Message.parseFrom(client.finishPayload).messageData).publicKey.ecP256PublicKey
        assertEquals(33, clientPoint.x.size())

        val phone = java.security.KeyPairGenerator.getInstance("EC").apply { initialize(java.security.spec.ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val phonePub = phone.public as java.security.interfaces.ECPublicKey
        fun signed(n: java.math.BigInteger) = ByteString.copyFrom(n.toByteArray()) // two's complement, like the phone (Java) sends
        val serverInit = Ukey2Message.newBuilder()
            .setMessageType(Ukey2Message.Type.SERVER_INIT)
            .setMessageData(
                Ukey2ServerInit.newBuilder()
                    .setVersion(1)
                    .setRandom(ByteString.copyFrom(ByteArray(32) { 7 }))
                    .setHandshakeCipher(Ukey2HandshakeCipher.P256_SHA512)
                    .setPublicKey(
                        GenericPublicKey.newBuilder().setType(PublicKeyType.EC_P256)
                            .setEcP256PublicKey(EcP256PublicKey.newBuilder().setX(signed(phonePub.w.affineX)).setY(signed(phonePub.w.affineY))),
                    )
                    .build().toByteString(),
            )
            .build().toByteArray()
        val emoji = client.processServerInit(
            GaiaPairingResponseContainer.newBuilder()
                .setData(ByteString.copyFrom(serverInit))
                .setConfirmedVerificationCodeVersion(1)
                .setConfirmedKeyDerivationVersion(1)
                .build(),
        )

        // The phone's view of the same exchange.
        val params = phonePub.params
        val clientKey = KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.ECPublicKeySpec(
                java.security.spec.ECPoint(java.math.BigInteger(1, clientPoint.x.toByteArray()), java.math.BigInteger(1, clientPoint.y.toByteArray())),
                params,
            ),
        )
        val dh = javax.crypto.KeyAgreement.getInstance("ECDH").apply { init(phone.private); doPhase(clientKey, true) }.generateSecret()
        val shared = java.security.MessageDigest.getInstance("SHA-256").digest(dh)
        val info = initPayload + serverInit
        val auth = GaiaPairingSession.hkdf(shared, "UKEY2 v1 auth".toByteArray(), info)
        val next = GaiaPairingSession.hkdf(shared, "UKEY2 v1 next".toByteArray(), info)
        assertEquals(GaiaPairingSession.emojiFor(auth, 1), emoji)
        assertArrayEquals(GaiaPairingSession.requestCrypto(next, 1).aesKey, client.requestCrypto().aesKey)
    }

    // ---- Media the phone leaves out of its copies

    private fun photo(mediaId: String = "", thumb: String = "", key: Byte = 0, mime: String = "image/jpeg") =
        GmMessage.newBuilder().setMessageID("m").addMessageInfo(
            app.transmit.gm.proto.MessageInfo.newBuilder().setActionMessageID("a").setMediaContent(
                app.transmit.gm.proto.MediaContent.newBuilder().setMediaID(mediaId).setThumbnailMediaID(thumb)
                    .setDecryptionKey(ByteString.copyFrom(byteArrayOf(key))).setMimeType(mime).setSize(if (mediaId.isEmpty()) 0 else 10),
            ),
        ).build()

    @Test
    fun phoneCopyWithoutFileKeepsTheUpload() {
        val merged = GmMapping.keepMedia(photo(mime = ""), photo("uploaded", key = 7))
        val media = GmMapping.mediaParts(merged).single()
        assertEquals("uploaded", media.mediaID)
        assertEquals(7.toByte(), media.decryptionKey.byteAt(0))
        assertEquals("image/jpeg", media.mimeType)
        assertEquals(10L, media.size)
    }

    @Test
    fun thumbnailOnlyCopyGetsTheFullFile() {
        val merged = GmMapping.keepMedia(photo(thumb = "thumb"), photo("full", key = 3))
        assertEquals("full", GmMapping.mediaParts(merged).single().mediaID)
        assertEquals("thumb", GmMapping.mediaParts(merged).single().thumbnailMediaID)
    }

    @Test
    fun aNewerFileWinsUnlessTheOldOneIsHere() {
        val new = photo("phone", key = 1)
        assertEquals("phone", GmMapping.mediaParts(GmMapping.keepMedia(new, photo("ours", key = 2))).single().mediaID)
        assertEquals("ours", GmMapping.mediaParts(GmMapping.keepMedia(new, photo("ours", key = 2)) { true }).single().mediaID)
    }

    @Test
    fun localAndMissingMediaIsNeverCopied() {
        val new = photo()
        assertTrue(GmMapping.keepMedia(new, photo(GmMapping.LOCAL_MEDIA + "content://x")) === new)
        assertTrue(GmMapping.keepMedia(new, photo()) === new)
        val text = GmMessage.newBuilder().setMessageID("t").addMessageInfo(
            app.transmit.gm.proto.MessageInfo.newBuilder().setMessageContent(app.transmit.gm.proto.MessageContent.newBuilder().setContent("hi")),
        ).build()
        assertTrue(GmMapping.keepMedia(text, photo("x")) === text)
    }

    @Test
    fun pendingMediaStillCounts() {
        val parts = GmMapping.mediaParts(photo())
        assertEquals(1, parts.size)
        assertFalse(GmMapping.hasMedia(parts.single()))
        assertTrue(GmMapping.hasMedia(GmMapping.mediaParts(photo(thumb = "t")).single()))
    }

    @Test
    fun phoneUnreadFlagIsOutOfDateOnlyUntilSomethingNewArrives() {
        fun conv(unread: Boolean, lastMs: Long) = Conversation.newBuilder().setUnread(unread).setLastMessageTimestamp(lastMs * 1000).build()
        assertTrue(GmMapping.isStaleUnread(conv(unread = true, lastMs = 5_000), readUpToMs = 5_000))
        assertFalse(GmMapping.isStaleUnread(conv(unread = true, lastMs = 6_000), readUpToMs = 5_000))
        assertFalse(GmMapping.isStaleUnread(conv(unread = true, lastMs = 1_000), readUpToMs = null))
        assertFalse(GmMapping.isStaleUnread(conv(unread = false, lastMs = 1_000), readUpToMs = 5_000))
    }
}
