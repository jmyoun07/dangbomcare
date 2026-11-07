# =============================================
# 당봄케어 ProGuard / R8 rules
# release minify ON 시 안전 동작을 위한 keep rules
# =============================================

# 스택 트레이스 디버깅용 (Crashlytics에서 라인 번호 보존)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# =============================================
# Kotlin
# =============================================
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-dontwarn kotlin.**
-dontwarn kotlinx.**

# =============================================
# Retrofit + OkHttp + Gson (API 모델 직렬화)
# =============================================
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson — 우리 모델 클래스 keep (SerializedName fields)
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}

# =============================================
# Kotlin serialization
# =============================================
-keep,includedescriptorclasses class com.dangbomcare.app.**$$serializer { *; }
-keepclassmembers class com.dangbomcare.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.dangbomcare.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# =============================================
# Compose
# =============================================
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# =============================================
# Health Connect
# =============================================
-keep class androidx.health.connect.client.** { *; }
-dontwarn androidx.health.connect.**

# =============================================
# Firebase + Crashlytics + Analytics
# =============================================
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
-keep class com.crashlytics.** { *; }
-dontwarn com.crashlytics.**

# =============================================
# DataStore + WorkManager
# =============================================
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# =============================================
# 당봄케어 모델 (API 직렬화) — 명시적 keep
# Models.kt의 모든 data class가 Gson reflection으로 직렬화됨
# =============================================
-keep class com.dangbomcare.app.data.api.** { *; }
-keep class com.dangbomcare.app.data.health.HealthConnectRepository$* { *; }

# =============================================
# Enum (Gson 등 reflection 사용)
# =============================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# =============================================
# Parcelable
# =============================================
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# =============================================
# JNI / Native methods
# =============================================
-keepclasseswithmembernames class * {
    native <methods>;
}
