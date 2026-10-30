# Best Player

Android video player (Kotlin + Media3/ExoPlayer). The owner writes in Thai; reply in Thai.

## Rules
- **Bump the version on every change you ship.** In `app/build.gradle.kts`, increase `versionCode` by 1 and `versionName`
  (minor for features, e.g. 1.2 -> 1.3; patch for fixes, e.g. 1.3 -> 1.3.1). The in-app credit
  ("Best Player vX · By NorthXbar") reads the version automatically.
- Keep the "By NorthXbar" credit on the main screen footer and in the settings dialog.
- Release signing: GitHub Secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` (same for store and key), optional `KEY_ALIAS` (default `bestplayer`). Never commit the keystore.
- This environment cannot download the Android SDK; builds run on GitHub Actions (`.github/workflows/build.yml`)
  on every push, and the APK is the `BestPlayer-debug` artifact of the run.
