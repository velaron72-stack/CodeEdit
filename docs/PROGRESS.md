# Status (honest, by evidence level)

| Area | Inspected | Static check | Compiled | Auto-tested | Device-tested |
|---|---|---|---|---|---|
| Native TextMate engine (C++) | yes | ASan/UBSan | host (g++/clang) | corpus 95/95, themes 1008/1008, smoke 238 | no |
| Core (documents, IO, search, replace, edit actions, folds) | yes | — | JVM (Kotlin 2.0.21) | 76 JUnit tests | no |
| Highlight layer (detector, content sniffer, extension memory, synchronous prefetch), settings schema, session/drafts, viewport math | yes | — | JVM | 57 JUnit tests, incl. real-engine ones | no |
| Content sniffer on real files | yes | — | JVM | offline run on 3 443 files, see `HIGHLIGHT.md` (harness not shipped) | no |
| `EditorView`, painter, touch, input connection, fonts, `files/UriFiles` | yes | — | **yes, against the real `android.jar` (API 30)**, outside Gradle | no | no |
| `EditorViewModel`, `SearchController`, `Tab`, `CodeEditApp`, `SettingsRepo`, `RefreshRate` | yes | — | yes, but against small hand-written stubs of androidx (state, ViewModel, DataStore, DocumentFile), so API shapes of the real libraries are not verified | no | no |
| Compose screens (`ui/{Theme,Components,EditorHost,EditorScreen,Menus,SearchPanel,Dialogs,SettingsScreen,FolderScreen,Root}`), `MainActivity`, manifest, resources | yes | re-read; imports and signatures cross-checked by grep | **no** (this round touched `EditorHost`, `Dialogs`, `Root`, `MainActivity`) | no | no |
| Gradle build, `.github/workflows/build.yml` | yes | YAML parsed; the signing step's shell code was extracted and run locally for 4 cases (Secrets, new key, cached key, incomplete Secrets) | **not run on GitHub since the signing rewrite** | — | — |

Rows marked "no" under *Compiled* have only been read: the first full Gradle run may report compile errors there.
133 JVM tests (listed in `app/src/test`) pass locally; none is skipped (the real-engine tests ran with the host build of the native library).

## What the user saw on his phone (previous build, 1080x2400 screenshots)
1. a blank band where the tab bar should be;
2. an exteraGram `.plugin` file shown as "Plain Text";
3. line spacing and the gutter too wide;
4. scrolling and highlighting felt laggy (no 90/120 Hz).

## Changes for those reports (version 1.0.1) — none of them has been verified on a device yet
1. **Tab bar** — cause found by reading the code, not by running it: `EditorPainter.draw` filled the whole canvas with `drawColor`
   without a clip; the editor `AndroidView` is drawn after the Compose top bar, so it could paint over it (and force a full-window
   repaint every frame). Now: canvas clip in `onDraw`, `View.clipBounds`, and `Modifier.clipToBounds()` (also gives the view its own
   graphics layer). If the bar is still missing after this, the cause is something else and needs a new screenshot/log.
2. **Signing** — release and debug are signed with one key; Secrets, or an auto-generated key kept in the Actions cache; the run fails if an
   APK is not signed. `docs/SIGNING.md`, `tools/make_keystore.sh`.
3. **Layout** — line spacing default 1.0 (was 1.25, range now 0.8-2.5), minimum 2 digits in the gutter (was 3), fold column 12 dp
   (was 16), smaller paddings.
4. **Smoothness** — one token lookup per visible line and frame, no per-frame string allocation for the line-number width, no
   full-window overdraw, request for the fastest display mode of the current resolution (setting "Maximum screen refresh rate",
   on by default; the OS may ignore it), native engine warm-up at app start, first screenful tokenized before the first frame
   (no plain-text flash). **Not measured on a device**; host-only engine speed measured earlier: ~7-12 k lines/s.
5. **Language** — `.plugin` -> Python, language from content when the name says nothing, "Remember for .ext" in the language dialog.

## Not done / known limits
* No Smali / .gitignore grammar exists in the package (plain text).
* VS Code `embeddedLanguages` ids not implemented; no minimap; in wrap mode Home/End move by logical line.
* SAF writes cannot be atomic (provider limitation): the app writes a temp file, verifies, then overwrites in place and keeps a draft on failure.
* Replace-all for editable documents runs on the UI thread (big files use the streaming path).
* Highlight cold-start for lines > 20 000 from a checkpoint is approximate until the frontier catches up; frontier cap 1.5 M lines.
* Content sniffing is a heuristic (numbers in `HIGHLIGHT.md`); the language dialog overrides it.
* The 90/120 Hz request is a hint: battery saver or an OEM per-app setting can keep 60 Hz.
* The auto-generated signing key lives in the Actions cache (dropped after 7 idle days); a permanent key needs the Secrets.
* Gradle wrapper is not included (no network when the project was produced): CI installs Gradle 8.11.1 itself; locally use Gradle 8.11.1.
