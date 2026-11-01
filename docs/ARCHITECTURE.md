# CodeEdit — architecture & module contracts

Android code editor. Kotlin + Jetpack Compose shell, custom `View` for text, C++/JNI TextMate engine
(port of vscode-textmate 9.3.2 + Oniguruma), pure-Kotlin document core (works on 1 GB+ files).
Package root: `app.codeedit`. applicationId `app.codeedit`. minSdk 26, compile/targetSdk 35. Kotlin 2.0.21, AGP 8.7.3,
Gradle 8.11.1, JDK 17, NDK default of AGP, CMake 3.22.1. ABIs: arm64-v8a, armeabi-v7a (+ universal APK).

## Ground rules (all workers)
* No demo / mock / stub / TODO-throwing code. Every public function works. If something is impossible, document it in code + `docs/LIMITS.md` and implement the best correct behaviour.
* Never claim verification that was not performed. Report: *inspected / statically checked / compiled / unit-tested / device-tested*.
* Only edit files you own (below). Need a contract change? Append a line to `docs/CONTRACT_REQUESTS.md` (create if missing) and work around it.
* Existing files in `core/doc/Types.kt`, `core/doc/LineStore.kt`, `core/io/RandomAccessSource.kt`, `highlight/Contracts.kt`, `editor/Contracts.kt` are the **contract**. You may ADD members with defaults; do not rename/remove. 
* The main thread never does long reads, full parses, searches, encoding or writes.
* Dependencies allowed in Gradle: androidx core-ktx, activity-compose, compose BOM 2024.12.01 (ui, material3, material-icons-extended, foundation), lifecycle-runtime-compose / viewmodel-compose 2.8.7, datastore-preferences 1.1.1, documentfile 1.0.1, kotlinx-coroutines-android 1.9.0. Nothing else (ask via CONTRACT_REQUESTS). `core.*` and `highlight` Kotlin code must not import `android.*` except where stated (so it compiles/tests on a plain JVM with `tools/kc.sh`).
* Local compile of pure-Kotlin code: `tools/kc.sh <outdir> "" <src files/dirs>`; run: `java -cp "<outdir>:$(tools/kc.sh --cp)" MainKt`. JUnit 4.13.2 is on that classpath. There is NO Android SDK, no Maven, no network in the sandbox: Android-dependent code cannot be compiled locally; be extra careful with API names/signatures; CI will compile it later.
* Code style: small files by responsibility (<~600 lines), KDoc on public API, `@JvmField` on hot fields, no allocation in per-frame/per-token hot paths.

## Ownership
| Worker | Owns |
|---|---|
| N (native) | `app/src/main/cpp/**`, `native-tests/**`, `app/src/main/java/app/codeedit/highlight/TmNative.kt`, `GrammarProvider.kt` |
| H (highlight Kotlin) | `highlight/` (everything except TmNative/GrammarProvider/Contracts), `core/util/MiniJson.kt` |
| C (core) | `core/doc/*` (except contract files), `core/io/*`, `core/search/*`, `app/src/test/**` for these |
| E (editor) | `editor/*` (except Contracts.kt), `core/edit/*` (EditorSession, EditorState, commands, brackets, folding, auto-indent) |
| S (shell) | `ui/*`, `data/*`, `MainActivity.kt`, `CodeEditApp.kt`, `res/**`, `AndroidManifest.xml` |
| lead | Gradle files, CI, CMake glue (`cpp/CMakeLists.txt` is N's), docs, integration |

## Data flow
```
file/URI ──(S)──> RandomAccessSource ──(C) DocumentOpener──> ViewDocument | EditDocument  (LineStore)
                                                   │
 HighlightService (H) ── createHighlighter(store, lang) ──> SyntaxHighlighter ──(JNI, N)── C++ TextMate
                                                   │
 EditorSession (E) = store + selection + scroll/zoom + highlighter + encoding/eol ──> EditorView (E, custom View)
 SearchSession (C) ── MatchHighlighter ──> EditorView                    Shell (S) = Compose UI, tabs, menus, settings, intents
```

## JNI contract (N implements C++, N writes `TmNative.kt`; H uses it)
Native library name: `codeedit_native` (`System.loadLibrary`). Engine = faithful C++17 port of vscode-textmate (grammar compile,
tokenizeString, injections, `while` rules, `\G`/`\A` anchors, back-references, theme trie, `EncodedTokenAttributes`) on Oniguruma
using **UTF-16LE** regex encoding so Java `char` indices are match offsets (no UTF-8 conversion). Grammar JSON is parsed natively.
```kotlin
fun interface GrammarProvider { fun loadGrammar(file: String): ByteArray? }   // file = "grammars/python.json" (path from manifest)
object TmNative {
  @JvmStatic external fun nativeVersion(): String
  @JvmStatic external fun createRegistry(provider: GrammarProvider, manifestJson: ByteArray): Long   // reads languages[].scope/file/injectTo
  @JvmStatic external fun destroyRegistry(registry: Long)
  /** themeJson = tm-themes JSON (tokenColors/settings + colors). Returns ARGB colour map, index = colour id in token meta. Registry-wide; invalidates nothing itself. */
  @JvmStatic external fun setTheme(registry: Long, themeJson: ByteArray, defaultFg: Int, defaultBg: Int): IntArray?
  /** 0 on failure; then lastError() has text. Loads grammar + transitive deps through provider. */
  @JvmStatic external fun createSession(registry: Long, rootScope: String, maxLineChars: Int, checkpointStride: Int, maxCheckpoints: Int): Long
  @JvmStatic external fun destroySession(session: Long)
  @JvmStatic external fun lastError(): String?
  @JvmStatic external fun invalidateFrom(session: Long, line: Int)      // drop checkpoints stored at lines > line; line < 0 drops all
  @JvmStatic external fun nearestCheckpoint(session: Long, line: Int): Int // largest L<=line with stored state; 0 is always valid; -1 never
  @JvmStatic external fun maxCheckpoint(session: Long): Int
  @JvmStatic external fun checkpointCount(session: Long): Int
  /** chars/offsets: line i = chars[offsets[i] until offsets[i+1]] (no EOL; native appends "\n" like vscode). Start state = checkpoint at startLine
   *  (startLine==0 => initial state); if none and !cold => returns null; cold => initial state, nothing recorded. Tokens are emitted for lines with
   *  absolute index >= emitFrom. record => store state at START of line L for L%stride==0, L>startLine, L<=startLine+lineCount.
   *  Result: for every emitted line: [n, start0, meta0, start1, meta1, ...]. Long lines (> maxLineChars): one token, no state change. */
  @JvmStatic external fun tokenize(session: Long, startLine: Int, chars: CharArray, offsets: IntArray, lineCount: Int,
                                   emitFrom: Int, cold: Boolean, record: Boolean, timeLimitMs: Int): IntArray?
}
```
Native copies `chars`/`offsets` with `Get*ArrayRegion` (never hold a critical section while tokenizing). Registry/session objects are NOT thread-safe:
H serialises calls (one lock per session; registry `setTheme` under an exclusive lock vs tokenization under shared lock).
Memory: checkpoints are refcounted persistent `StateStack` nodes; when count > maxCheckpoints native doubles the stride and drops non-multiples.

## manifest.json (generated by `tools/gen_manifest.py`, in `assets/textmate/`)
`{format, grammarPackage{name,version,license,homepage}, themePackage{...}, engine, languages[{id,name,scope,file:"x.json",aliases[],extensions[".py"],filenames["Makefile"],firstLine?, embedded?[ids], injectTo?[scopes], hidden?, license?}], themes[{id,name,type:"dark|light",file,license}]}`.
`file` is relative to `grammars/` or `themes/`. Injection grammars (`injectTo`) must be registered as injections of each target scope (vscode-textmate `getInjections`). Plain text = language id `plaintext` (no grammar).

## High-level APIs used across workers

### Core (C) — package `app.codeedit.core.io` / `core.doc` / `core.search`
```kotlin
// io
class FileChannelSource(channel: java.nio.channels.FileChannel, override val lastModified: Long = 0L, onClose: (() -> Unit)? = null) : RandomAccessSource
class SpoolingSource(input: java.io.InputStream, tempDir: java.io.File, expectedSize: Long = -1L, executor: java.util.concurrent.Executor? = null,
                     override val lastModified: Long = 0L, onClose: (() -> Unit)? = null) : RandomAccessSource { val error: Throwable?; fun cancel() }  // growing temp file, isFinal when copy ends
// doc
data class OpenOptions(val forceMode: DocMode? = null, val forceEncoding: TextEncoding? = null, val editMaxBytes: Long = 24L*1024*1024,
                       val heapBudgetBytes: Long = Runtime.getRuntime().maxMemory()/3, val indexExecutor: java.util.concurrent.Executor? = null)
sealed class OpenResult { class Ok(val doc: LineStore, val info: DocInfo): OpenResult(); class Binary(val reason: String): OpenResult(); class Failure(val error: Throwable): OpenResult() }
object DocumentOpener {
  fun open(source: RandomAccessSource, fileName: String?, options: OpenOptions = OpenOptions()): OpenResult   // sniffs <=64KB: binary? encoding(BOM/UTF-8/UTF-16/cp1251 heuristics) eol mixed; picks mode (size, long lines, heap)
  fun openForEdit(source: RandomAccessSource, info: DocInfo, options: OpenOptions = OpenOptions()): OpenResult // whole file -> EditDocument; Failure(OutOfBudgetException) when too big
  fun newDocument(text: String = "", eol: Eol = Eol.LF): Pair<EditableDocument, DocInfo>
  fun fromText(text: String, info: DocInfo): EditableDocument           // draft restore (text already decoded, '\n' separators)
}
class ViewDocument : LineStore  { companion object { const val MAX_LINE_CHARS = 1 shl 16 } }  // read-only, streaming, sparse line index built in background, bounded block cache
class EditDocument : EditableDocument                                    // gap buffer + line starts, undo stack with coalescing, memory-capped history
class OutOfBudgetException(message: String): Exception(message)
interface SaveTarget { fun openOutput(): java.io.OutputStream; fun commit(); fun abort() }   // commit() only after a complete write
class FileSaveTarget(val file: java.io.File, val keepBackup: Boolean = false) : SaveTarget   // temp file in same dir + atomic rename, optional .bak
data class SaveResult(val ok: Boolean, val bytesWritten: Long, val error: Throwable?)
object DocumentSaver { fun save(doc: EditableDocument, encoding: TextEncoding, eol: Eol, ensureFinalNewline: Boolean, target: SaveTarget,
                                progress: ((Float) -> Unit)? = null, cancel: java.util.concurrent.atomic.AtomicBoolean? = null): SaveResult
                       fun copyBytes(source: RandomAccessSource, target: SaveTarget, progress: ((Float) -> Unit)? = null, cancel: java.util.concurrent.atomic.AtomicBoolean? = null): SaveResult }
object TextCodec { fun encodingFromName(name: String): TextEncoding?; val commonEncodings: List<TextEncoding> /* UTF-8, UTF-8 BOM, UTF-16LE/BE, windows-1251, windows-1252, ISO-8859-1, KOI8-R, ... */ }
// search
data class SearchQuery(val text: String, val matchCase: Boolean = false, val wholeWord: Boolean = false, val regex: Boolean = false)
class QueryException(message: String) : Exception(message)
class SearchSession(store: LineStore, query: SearchQuery, executor: java.util.concurrent.Executor, listener: Listener? = null) : java.io.Closeable {  // throws QueryException
  fun interface Listener { fun onProgress(matchCount: Long, complete: Boolean) }   // background thread
  val matchCount: Long; val isComplete: Boolean; val timedOut: Boolean
  fun start()                                          // background full scan, compact LongArray storage (cap ~5M matches => "5M+")
  fun findNext(from: Pos, wrap: Boolean = true, inclusive: Boolean = false): Match?   // bounded, works before the scan finishes
  fun findPrevious(from: Pos, wrap: Boolean = true): Match?
  fun matchesInLine(line: Int): IntArray               // [col,len,...] computed directly for that line (cached by contentVersion)
  fun ordinalOf(m: Match): Long                        // 1-based, -1 unknown
  fun onDocumentChanged(startLine: Int, oldCount: Int, newCount: Int)  // EDIT docs: invalidates and restarts the scan
  fun cancel(); override fun close()
}
object Replacer {
  fun replaceAll(doc: EditableDocument, query: SearchQuery, replacement: String, selBefore: Selection?, selAfter: Selection?): Int   // one undo step; regex groups $1 / \1
  fun expand(query: SearchQuery, matchedText: String, replacement: String): String
  fun expandAt(query: SearchQuery, context: String, start: Int, end: Int, replacement: String): String   // replacement text for one match (groups resolved with context)
  fun streamReplaceAll(source: RandomAccessSource, info: DocInfo, query: SearchQuery, replacement: String, target: SaveTarget,
                       progress: ((Float) -> Unit)?, cancel: java.util.concurrent.atomic.AtomicBoolean?): StreamReplaceResult  // StreamReplaceResult(ok, bytesWritten, replacements, error); original untouched until commit(); per-line
}
```
Regex time limit: wrap CharSequence that throws on deadline; `timedOut` flag. View-mode search is per-line (multi-line regex only in EDIT mode).

### Highlight (H) — package `app.codeedit.highlight`
```kotlin
data class PackageInfo(val grammarName: String, val grammarVersion: String, val themeName: String, val themeVersion: String, val engine: String, val languageCount: Int, val themeCount: Int, val licenses: List<String>)
class HighlightService(assets: AssetReader, uiExecutor: java.util.concurrent.Executor, workerExecutor: java.util.concurrent.ExecutorService? = null) : java.io.Closeable {
  val languages: List<LanguageInfo>            // visible (non-hidden), sorted by name; first = plaintext entry
  val allLanguages: List<LanguageInfo>
  val themes: List<ThemeEntry>
  val packageInfo: PackageInfo
  val theme: ThemeInfo                         // current
  fun setTheme(themeId: String): ThemeInfo     // any thread; invalidates every live highlighter (they fire listener(0, Int.MAX_VALUE))
  fun languageById(id: String): LanguageInfo?
  fun detectLanguage(fileName: String?, firstLine: String?): LanguageInfo?   // by exact filename, extension (longest match, ".d.ts" style), shebang/first-line regex; null => plain text
  fun createHighlighter(store: LineStore, languageId: String): SyntaxHighlighter?   // null for plaintext/unknown/unloadable; registers itself as DocumentListener
}
object MiniJson { fun parse(bytes: ByteArray): Any?  /* Map<String,Any?>, List<Any?>, String, Double/Long, Boolean, null */ ; fun write(v: Any?): String }  // package app.codeedit.core.util
```
Highlighter behaviour: block tokenization of visible range ± margin on a worker; checkpoint stride adaptive (64 for small files up to ~2000 for 25M-line files); background "frontier" job walks forward from the last checkpoint (exact states) at low priority; if the nearest checkpoint is farther than ~20k lines → cold start for the visible block (`isApproximate` true) and the frontier later fixes it; LRU token cache (~3000 lines); edits: invalidate checkpoints after the edit line, synchronous tokenization of edited lines on the UI thread (from nearest checkpoint), async re-tokenization of following visible lines; lines longer than `maxTokenizeLineChars` plain.

### Editor (E) — packages `app.codeedit.core.edit`, `app.codeedit.editor`
```kotlin
class EditorSession(val id: String, var store: LineStore, var info: DocInfo, var languageId: String = PLAIN_TEXT_ID) : java.io.Closeable {
  var highlighter: SyntaxHighlighter?          // closing the session closes it
  var selection: Selection                      // persisted by the shell
  var topLine: Int; var topOffsetPx: Float; var scrollX: Float   // scroll = (logical top line, pixel offset inside that line's block)
  var fontSizeSp: Float                         // per-tab zoom; Float.NaN => use EditorConfig.fontSizeSp
  var encoding: TextEncoding; var eol: Eol      // user-chosen save settings (start = info)
  var wrapOverride: Boolean?                    // null => config
  val editable: EditableDocument?               // store as? EditableDocument
  val isModified: Boolean
  val mode: DocMode
  fun replaceStore(newStore: LineStore, newInfo: DocInfo)   // VIEW<->EDIT switch: keeps selection/scroll
}
class EditorView(context: Context, attrs: AttributeSet? = null) : View(context, attrs), EditorCommands {
  var listener: EditorListener?
  fun bind(session: EditorSession?)
  fun applyConfig(cfg: EditorConfig); fun applyColors(colors: EditorColors)
  val currentSession: EditorSession?
  fun saveViewState()      // copies live scroll/selection into the session (also done on unbind/detach)
  var extraBottomInsetPx: Int    // keyboard/extra-keys overlap so the caret stays visible
  var onFloatingInsets...        // (not needed)
}
object FontRegistry { data class Entry(val id: String, val name: String, val builtin: Boolean, val license: String)
  fun list(context: Context): List<Entry>; fun typeface(context: Context, id: String): Typeface
  fun importFont(context: Context, uri: Uri): Entry?   // copies .ttf/.otf from SAF into filesDir/fonts, validates by loading
}
```
View behaviour: virtualised rendering (only visible lines), vertical position = (topLine, topOffsetPx) so 25M-line files do not hit float limits; fast-scroll thumb maps to `estimatedLineCount`; pinch zoom changes size live; IME via `InputConnection`; hardware-keyboard shortcuts Ctrl+S/Z/Y/F/H/A/C/X/V/G; selection handles; context menu; line numbers; current line; matching bracket; folding; indent guides; whitespace; word wrap; cursor styles.

### Shell (S) — responsibilities
Compose UI, MainActivity (singleTask, intents VIEW/EDIT/SEND/SEND_MULTIPLE, `onNewIntent`), start screen, tabs, 3-line menu, search/replace panel (uses `SearchSession`), settings (schema-driven: screen + search + JSON import/export validation + reset), DataStore, session/drafts persistence, autosave, external-modification detection, SAF open/save/persistable permissions, floating return button (in-app overlay, no overlay permission), extra-keys row, tab memory manager, about/licenses, Material You/AMOLED, `FontRegistry` use, builds `EditorConfig` from settings, maps `ThemeInfo` to editor colors.

## Build/CI (lead)
`.github/workflows/build.yml`: JDK 17, Android SDK, Gradle cache, assets fetch if missing, lint, native build via CMake (Oniguruma fetched at configure time with FetchContent, tag v6.9.10), unit tests, debug+release APKs (per-ABI + universal) as artifacts, signing only from secrets.
