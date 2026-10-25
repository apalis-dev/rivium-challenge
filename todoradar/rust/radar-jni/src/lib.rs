//! JNI library loaded by the Android and desktop clients; Rivium generates the native entry points.

rivium_jni::export!(class = "io/github/kdbrian/rivapln/todoradar/engine/RadarBridge", app = radar_core::RadarApp);
