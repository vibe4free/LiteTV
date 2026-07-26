# ProGuard/R8 rules for the LiteTV app module.
# Referenced from app/build.gradle (release buildType).

# Keep crash stack traces readable; mapping.txt is written to
# app/build/outputs/mapping/release/ for de-obfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Gson — relies on generic signatures and on field names matching JSON keys.
# ---------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

-dontwarn sun.misc.**
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.google.gson.stream.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------------------------------------------------------------------------
# Hawk — serialises stored values through Gson and reflects on its own
# DataInfo wrapper, so it must not be stripped or renamed.
# ---------------------------------------------------------------------------
-keep class com.orhanobut.hawk.** { *; }
-dontwarn com.orhanobut.hawk.**

# ---------------------------------------------------------------------------
# App models — serialised/deserialised by name (EPG cache, playlist data).
# Keeping them makes the on-disk cache format independent of obfuscation.
# ---------------------------------------------------------------------------
-keep class com.cabletv.player.model.** { *; }
-keep class com.cabletv.player.epg.EpgManager$Program { *; }

# ---------------------------------------------------------------------------
# Player module (DKPlayer). Instantiated from XML layouts and via factories;
# the util classes reflect on platform/vendor classes by name.
# ---------------------------------------------------------------------------
-keep class xyz.doikki.videoplayer.player.VideoView { *; }
-keep class xyz.doikki.videoplayer.exo.** { *; }
-keep class * implements xyz.doikki.videoplayer.player.PlayerFactory { *; }
-keep class * implements xyz.doikki.videoplayer.render.RenderViewFactory { *; }

# ExoPlayer and Glide ship their own consumer rules in their AARs; only the
# optional/absent integrations need silencing here.
-dontwarn com.google.android.exoplayer2.ext.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ---------------------------------------------------------------------------
# Views constructed by name from XML keep their (Context, AttributeSet) ctor.
# ---------------------------------------------------------------------------
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
