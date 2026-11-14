# Building & internals

Developer notes for Quest Mate. For installing and using the app, see the [README](README.md).

## Build

The Gradle wrapper is included, so you don't need Gradle installed.
Requirements: JDK 17, Android SDK (platform 35 + build-tools), and `ANDROID_HOME` set
(or a `local.properties` containing `sdk.dir=/path/to/sdk`).

    .\gradlew.bat assembleDebug        (Windows)   |   ./gradlew assembleDebug   (Linux/macOS)
    adb install -r app/build/outputs/apk/debug/app-debug.apk

Updates: the app checks this repo's latest GitHub release. Push a tag like `v1.0.1` and the workflow builds a
signed APK and publishes it as a release; the in-app **Update** button downloads and installs it.

Release signing: create a private key once and keep a backup (losing it means users must reinstall):

    keytool -genkeypair -v -keystore questmate-release.jks -alias questmate -keyalg RSA -keysize 2048 -validity 36500

Then add these repository secrets (Settings > Secrets and variables > Actions): `QUESTMATE_KEYSTORE_BASE64`
(the .jks file, base64-encoded), `QUESTMATE_KEYSTORE_PASSWORD`, `QUESTMATE_KEY_ALIAS`, `QUESTMATE_KEY_PASSWORD`.
Never commit the key. Local builds without these variables use the debug key.

Code style: `./gradlew ktlintCheck` to lint, `./gradlew ktlintFormat` to fix (rules in `.editorconfig`).

No local Gradle/SDK? Push this folder to a GitHub repo and run the **Build APK** workflow
(Actions tab). It uploads `questmate.apk` as an artifact (not zipped), and tagged builds attach it to a release.

## How it works

- **Sign-in:** a WebView opens Discord's login page. Once you are past it, the app asks the page for the session
  token (`LoginScreen.kt`), checks it with `GET /users/@me`, stores it in EncryptedSharedPreferences and clears the
  WebView cookies. **Paste token** does the same check without the WebView.
- **Gateway:** `core/Gateway.kt` (OkHttp WebSocket) identifies, heartbeats, resumes after a drop and sends presence
  updates for game quests.
- **Quests:** `core/QuestRunner.kt` fetches `/quests/@me`, enrolls, then runs the video task (progress steps kept
  within the ~10 s time gate) or the game task (30 s heartbeats).
- **Background:** `work/Scheduler.kt`. A periodic tick (every 3 days by default, 12 h to 7 days in Settings) starts an expedited, foreground `QuestWorker`
  (`dataSync` type), because a run takes much longer than a normal background job. *Run quests now* and *Stop*
  use the same unique work.
- **Client values** (Discord/Electron/Chrome versions, build numbers, the Windows build pool) live in
  `app/src/main/res/raw/client_config.json`, versioned (`"version": "0.0.1"`) with a `"schema"` for the structure.
  The app bundles that file and, before each run (at most every 6 h), downloads the copy on this repo's `main`
  branch. It switches to the download only if it is valid, has the same schema and a higher `version`; otherwise it
  keeps the cached or bundled values. To publish new values: edit the file, raise `version`, push to `main`.
  Raise `schema` only for a structure change that old apps can't read. A version can't be lowered once published:
  fix a bad file with a still higher version. `core/ClientConfig.kt` has the rules, `core/DiscordConfig.kt` and
  `core/DeviceProfile.kt` read the values.
