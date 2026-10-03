# Decoder fixture

`decoder-test.h264` is three synthetic 64×64 teal frames at 8 fps, generated locally with FFmpeg's OpenH264 encoder and Annex-B access-unit delimiters. It is included only in the test APK to exercise Android's real MediaCodec renderer. It contains no camera recordings and is never used as a fallback feed in the app.
