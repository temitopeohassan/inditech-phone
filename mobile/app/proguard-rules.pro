# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class com.inditech.dpc.**$$serializer { *; }
-keepclassmembers class com.inditech.dpc.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
# Retrofit
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keep interface com.inditech.dpc.data.api.** { *; }
# Device admin receiver must keep its name (referenced in provisioning payloads)
-keep class com.inditech.dpc.admin.** { *; }
# Paho MQTT
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.**
-dontwarn javax.naming.**
