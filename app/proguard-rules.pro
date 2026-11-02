# The device admin receiver is named in the manifest and in the provisioning
# command (SPEC §4); R8 keeps manifest classes by itself. Nothing else is
# looked up by name, but for the extractor below.

# NewPipe Extractor (SPEC §7.2), as NewPipe keeps it: Rhino runs the platform's
# player script and reaches its own classes by reflection; the "time ago"
# patterns are loaded by name.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
