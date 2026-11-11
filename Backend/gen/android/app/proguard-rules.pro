# Le moteur (Starling) et notre écran de jeu sont appelés depuis le code natif
# par leur nom (JNI) et lancés par le manifeste : R8 ne voit pas ces appels et
# renommerait ou retirerait ces classes, ce qui ferait planter le jeu au
# lancement, seulement en version release. Starling, lui, ne passe jamais par R8
# (son build_apk.py compile avec d8).
-keep class io.github.juanluci.starling.** { *; }
-keep class fr.yuyuframe.** { *; }

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile