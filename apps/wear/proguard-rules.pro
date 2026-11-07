# kotlinx.serialization ships its own R8 rules; keep the wire models' generated serializers.
-keepclassmembers @kotlinx.serialization.Serializable class com.t3tools.t3code.wear.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
