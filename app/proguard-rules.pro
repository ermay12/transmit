# Anthropic Java SDK relies on Jackson + Kotlin reflection for (de)serialization.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn javax.annotation.**
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
# Only the SDK's structured-output schema generator (unused here) touches these; Android lacks them.
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn java.lang.reflect.AnnotatedParameterizedType
# Google Messages protocol: pblite encoding walks message fields reflectively by descriptor, and
# full protobuf-java finds accessors by name (its own descriptor protos included), so keep it whole.
-keep class app.transmit.gm.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
