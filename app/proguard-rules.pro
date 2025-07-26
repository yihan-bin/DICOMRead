# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Add any project specific keep options here:
#-keep class com.example.dicomread.** { *; } # Example: Keep all classes in your package

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces:
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ★★★ 理由: 确保JNI函数和VTK/GDCM的本地方法不会被混淆，这是Native代码正常工作的关键 ★★★
-keepclasseswithmembernames class * {
    native <methods>;
}

# Parcelize
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}
