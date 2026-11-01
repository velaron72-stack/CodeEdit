# Status (honest, by evidence level)

| Area | Inspected | Static check | Compiled | Auto-tested | Device-tested |
|---|---|---|---|---|---|
| Native TextMate engine (C++) | yes | ASan/UBSan | host (g++/clang) | corpus 95/95, themes 1008/1008, smoke 238 | no |
| Core (documents, IO, search, replace, edit actions, folds) | yes | — | JVM (Kotlin 2.0.21) | 76 JUnit tests | no |
| Highlight layer, settings schema, session/drafts, viewport math | yes | — | JVM | 36 JUnit tests | no |
| `EditorView`, painter, touch, input connection, fonts, `files/UriFiles` | yes | — | **yes, against the real `android.jar` (API 30)**, outside Gradle | no | no |
| `EditorViewModel`, `SearchController`, `Tab`, `CodeEditApp`, `SettingsRepo` | yes | — | yes, but against small hand-written stubs of androidx (state, ViewModel, DataStore, DocumentFile), so API shapes of the real libraries are not verified | no | no |
| Compose screens (`ui/{Theme,Components,EditorHost,EditorScreen,Menus,SearchPanel,Dialogs,SettingsScreen,FolderScreen,Root}`), `MainActivity`, manifest, resources | yes | grep-level cross-checks | **no** | no | no |
| Gradle build, `.github/workflows/build.yml` | yes | — | **first CI run failed in the SDK-setup step (fixed); nothing after it has run yet** | — | — |

Rows marked "no" under *Compiled* have only been read: the first full Gradle run may report compile errors there.
112 JVM tests (listed in `app/src/test`) pass locally.

Not done / known limits
* No Smali / .gitignore grammar exists in the package (plain text).
* VS Code `embeddedLanguages` ids not implemented; no minimap; in wrap mode Home/End move by logical line.
* SAF writes cannot be atomic (provider limitation): the app writes a temp file, verifies, then overwrites in place and keeps a draft on failure.
* Replace-all for editable documents runs on the UI thread (big files use the streaming path).
* Highlight cold-start for lines > 20 000 from a checkpoint is approximate until the frontier catches up; frontier cap 1.5 M lines.
* Gradle wrapper is not included (no network when the project was produced): CI installs Gradle 8.11.1 itself; locally use Gradle 8.11.1.
