package app.transmit.gm

import app.transmit.gm.proto.PbliteProto
import com.google.protobuf.ByteString
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import java.util.Base64

/**
 * Google's "pblite" JSON form of protobuf, which the Messages for Web servers speak: a message is a
 * JSON array whose element `n - 1` holds field `n`, nested messages are nested arrays, and bytes are
 * base64. Fields marked `pblite_binary` carry binary protobuf (as base64) instead of a nested array.
 * Port of go.mau.fi/util/pblite.
 */
object Pblite {
    fun encode(msg: Message): String = toArray(msg).toString()

    fun <T : Message> decode(json: String, builder: Message.Builder): T {
        val root = Json.parseToJsonElement(json) as? JsonArray ?: throw PbliteException("expected a JSON array")
        fromArray(root, builder)
        @Suppress("UNCHECKED_CAST")
        return builder.build() as T
    }

    fun toArray(msg: Message): JsonArray {
        val fields = msg.descriptorForType.fields
        val out = MutableList<JsonElement>(fields.maxOfOrNull { it.number } ?: 0) { JsonNull }
        for (fd in fields) {
            out[fd.number - 1] = when {
                fd.isRepeated -> {
                    val n = msg.getRepeatedFieldCount(fd)
                    if (n == 0) continue
                    JsonArray(List(n) { encodeOne(fd, msg.getRepeatedField(fd, it)) })
                }
                !msg.hasField(fd) -> continue
                else -> encodeOne(fd, msg.getField(fd))
            }
        }
        return JsonArray(out)
    }

    private fun encodeOne(fd: FieldDescriptor, value: Any): JsonElement = when (fd.javaType) {
        FieldDescriptor.JavaType.MESSAGE ->
            if (isBinary(fd)) JsonPrimitive(b64((value as Message).toByteArray())) else toArray(value as Message)
        FieldDescriptor.JavaType.BYTE_STRING -> JsonPrimitive(b64((value as ByteString).toByteArray()))
        FieldDescriptor.JavaType.INT -> when (fd.type) {
            FieldDescriptor.Type.UINT32, FieldDescriptor.Type.FIXED32 -> JsonPrimitive(Integer.toUnsignedLong(value as Int))
            else -> JsonPrimitive(value as Int)
        }
        FieldDescriptor.JavaType.LONG -> when (fd.type) {
            FieldDescriptor.Type.UINT64, FieldDescriptor.Type.FIXED64 ->
                JsonPrimitive(java.math.BigInteger(java.lang.Long.toUnsignedString(value as Long)))
            else -> JsonPrimitive(value as Long)
        }
        FieldDescriptor.JavaType.FLOAT -> JsonPrimitive(value as Float)
        FieldDescriptor.JavaType.DOUBLE -> JsonPrimitive(value as Double)
        FieldDescriptor.JavaType.BOOLEAN -> JsonPrimitive(value as Boolean)
        FieldDescriptor.JavaType.ENUM -> JsonPrimitive((value as com.google.protobuf.Descriptors.EnumValueDescriptor).number)
        FieldDescriptor.JavaType.STRING ->
            if (isBinary(fd)) JsonPrimitive(b64((value as String).toByteArray(Charsets.UTF_8))) else JsonPrimitive(value as String)
    }

    fun fromArray(data: JsonArray, builder: Message.Builder) {
        // A trailing object holds fields by number, for sparse messages with large field numbers.
        val extra = data.lastOrNull() as? JsonObject
        val items = if (extra != null) data.subList(0, data.size - 1) else data
        for (fd in builder.descriptorForType.fields) {
            val value = extra?.get(fd.number.toString())?.takeIf { it !is JsonNull }
                ?: items.getOrNull(fd.number - 1)?.takeIf { it !is JsonNull }
                ?: continue
            if (fd.isRepeated) {
                val list = value as? JsonArray ?: throw PbliteException("expected an array for repeated field ${fd.fullName}")
                for (element in list) {
                    if (element is JsonNull) continue
                    builder.addRepeatedField(fd, decodeOne(fd, element, builder))
                }
            } else {
                builder.setField(fd, decodeOne(fd, value, builder))
            }
        }
    }

    private fun decodeOne(fd: FieldDescriptor, value: JsonElement, parent: Message.Builder): Any {
        fun prim(): JsonPrimitive = value as? JsonPrimitive ?: throw PbliteException("expected a primitive for ${fd.fullName}, got $value")
        fun str(): String = prim().takeIf { it.isString }?.content ?: throw PbliteException("expected a string for ${fd.fullName}")
        fun num(): String = prim().content
        return when (fd.javaType) {
            FieldDescriptor.JavaType.MESSAGE -> {
                val nested = parent.newBuilderForField(fd)
                if (isBinary(fd)) {
                    nested.mergeFrom(unb64(str()))
                } else {
                    fromArray(value as? JsonArray ?: throw PbliteException("expected an array for ${fd.fullName}"), nested)
                }
                nested.build()
            }
            FieldDescriptor.JavaType.BYTE_STRING -> ByteString.copyFrom(unb64(str()))
            FieldDescriptor.JavaType.INT -> num().toBigDecimal().toLong().toInt()
            FieldDescriptor.JavaType.LONG -> num().toBigDecimal().toBigInteger().toLong()
            FieldDescriptor.JavaType.FLOAT -> num().toFloat()
            FieldDescriptor.JavaType.DOUBLE -> num().toDouble()
            FieldDescriptor.JavaType.BOOLEAN -> prim().booleanOrNull ?: (num().toDouble() != 0.0)
            FieldDescriptor.JavaType.ENUM -> fd.enumType.findValueByNumberCreatingIfUnknown(num().toBigDecimal().toInt())
            FieldDescriptor.JavaType.STRING -> if (isBinary(fd)) String(unb64(str()), Charsets.UTF_8) else str()
        }
    }

    private fun isBinary(fd: FieldDescriptor): Boolean = fd.options.getExtension(PbliteProto.pbliteBinary)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}

class PbliteException(message: String) : Exception(message)
