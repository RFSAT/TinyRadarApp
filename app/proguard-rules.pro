# ── TinyRAD — R8 / ProGuard rules ────────────────────────────────────────────
#
# REWRITTEN IN v3.2.0.
#
# The previous contents of this file were copied verbatim from an unrelated
# project ("ShimmerENACT") and kept packages that do not exist in TinyRAD
# (com.rfsat.shimmerenact.**), libraries that are not dependencies (osmdroid,
# MPAndroidChart, OkHttp, protobuf), and a blanket rule that kept every
# @Composable class with all of its members. Over-broad and stale keep rules
# are exactly what the Play Console flags as "your R8 configuration could be
# causing higher memory usage and lower performance": every kept class is a
# class R8 may not shrink, inline, or repackage.
#
# TinyRAD uses NO reflection, NO serialisation libraries and NO dynamic
# resource lookup, so it needs almost no keep rules. Compose, DataStore,
# Navigation and Lifecycle all ship their own consumer rules in their AARs.
#
# NOTE for AGP 9: android.r8.strictFullModeForKeepRules now defaults to true,
# so "-keep class A" no longer implicitly keeps A's default constructor. Any
# rule added below that needs the constructor must say so explicitly, e.g.
# "-keep class A { <init>(); }".

# Preserve crash-report readability. SourceFile + LineNumberTable let Play
# Console and Android Studio retrace obfuscated stack traces via mapping.txt.
-keepattributes SourceFile, LineNumberTable
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

# Strip verbose/debug logging from release builds. AppLog routes user-facing
# messages through its own file writer, so only the noisy android.util.Log
# debug and verbose levels are removed; warn/error are retained.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
