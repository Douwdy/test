# Le décodeur FFmpeg est chargé par réflexion et appelé depuis du code natif (JNI).
-keep class androidx.media3.decoder.ffmpeg.** { *; }
