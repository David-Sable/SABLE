# JNI: the engine binds to these names (engine/../sable_jni.cpp).
-keep class app.sable.engine.SableEngine { *; }
-keepclasseswithmembernames class * { native <methods>; }
