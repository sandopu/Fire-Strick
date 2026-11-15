# FirePad Remote (Native Android Kotlin)

## Build APK Option 1 — Android Studio
1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Click **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Build APK Option 2 — Cloud Build (GitHub Actions, No Android Studio needed)
1. Push this folder to a GitHub repository.
2. Open the **Actions** tab and run **Build FirePad APK**.
3. Download the **FirePad-Remote-APK** artifact containing `app-debug.apk`.
