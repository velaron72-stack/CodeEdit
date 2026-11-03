# Code Edit

Android code editor (replacement for "Open with"): Kotlin + Jetpack Compose shell, custom `View` for text, native TextMate
highlighting, files only through the Storage Access Framework. minSdk 26, Material 3.

* 1 GB+ files open instantly (index in the background, only visible rows are laid out), VIEW and EDIT modes, streaming search / replace.
* Tabs (restored on launch), drafts + autosave, atomic-as-possible save, external-modification detection.
* Intents `VIEW`, `EDIT`, `SEND`, `SEND_MULTIPLE` (`singleTask`), floating return button without the overlay permission.
* Syntax highlighting only from the bundled `tm-grammars` / `tm-themes` packages — see `docs/HIGHLIGHT.md`.
* Settings (searchable, JSON import/export), extra-keys row, pinch zoom, fast-scroll thumb, 90/120 Hz request (setting "Maximum screen refresh rate").

## Build
GitHub: push the repository, run **Actions -> Build APK** (or push to `main`). The workflow builds the native engine tests, runs
JVM tests, then `assembleDebug` + `assembleRelease` and uploads `app/build/outputs/apk/**/*.apk` as artifact `apks`
(`arm64-v8a`, `armeabi-v7a`, `universal`).

Signing: release and debug APKs are signed with the same key (V1+V2+V3), so a new build installs over the previous one. The workflow uses
the repository **Secrets** below when they exist; otherwise it creates a key on the first run and keeps it in the Actions cache of the
repository (details, limits and a permanent-key recipe: `docs/SIGNING.md`). Nothing secret is stored in the repo or in the artifacts;
the *Summary* of each run shows which mode was used, the log shows the certificate fingerprint of every APK.

| Secret (optional) | Content |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | keystore data (`bash tools/make_keystore.sh` writes all four to `signing/github-secrets.txt`) |

Package names: release `app.codeedit`, debug `app.codeedit.debug` (installs side by side). Install the **release** APK for daily use:
it is shrunk (R8) and not debuggable; debug builds of Compose apps are slower (not measured for this app).

Local: JDK 17, Android SDK 35 + NDK 27.0.12077973 + CMake 3.22.1, Gradle 8.11.1: `gradle :app:assembleDebug`.

## Layout
`app/src/main/java/app/codeedit/{core,highlight,editor,data,files,ui}`, native engine `app/src/main/cpp`, assets
`app/src/main/assets/{textmate,fonts,licenses}`, docs in `docs/`, third-party licenses in `third_party_licenses/`.
Status and verification levels: `docs/PROGRESS.md`.
