# Keep JSch (uses reflection to load key exchange / cipher implementations).
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Apache Commons Net
-dontwarn org.apache.commons.net.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# SMBJ + its dependencies (reflection / event bus) and BouncyCastle crypto backend
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassador.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn com.hierynomus.**
-dontwarn net.engio.mbassador.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# Room generated code is kept by the Room consumer rules automatically.
