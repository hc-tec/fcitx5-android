# Sherpa-ONNX JNI reflects into Kotlin config objects by exact field name.
# Release shrinking must keep these classes and members intact.
-keep class com.k2fsa.sherpa.onnx.** { *; }
