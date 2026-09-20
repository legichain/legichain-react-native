# Keep public SDK API surface — host apps reflect against these classes.
-keep class com.legichain.kyc.** { *; }

# OkHttp + Kotlin coroutines are runtime-reflection-light, but keep
# the basic safety rules.
-dontwarn okhttp3.**
-dontwarn okio.**
