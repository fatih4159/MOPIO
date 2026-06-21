# build/

This folder receives the compiled debug APK.

**Automated builds:** The GitHub Actions workflow (`.github/workflows/build.yml`) builds
`mopio-debug.apk` on every push and uploads it as a CI artifact named `mopio-debug-apk`.

**Build locally:**

```bash
# 1. Get the proot binary (required before building)
./scripts/get_proot.sh

# 2. Build the debug APK
./gradlew assembleDebug

# 3. APK lands at:
#    app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
cp app/build/outputs/apk/debug/app-arm64-v8a-debug.apk build/mopio-debug.apk
```
