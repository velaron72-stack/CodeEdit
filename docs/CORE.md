# Core (documents, I/O, search) — status and measurements

Pure JVM Kotlin (no Android imports), so it is compiled and unit-tested on a desktop JDK in CI and locally.

## Pieces
| Area | Files | Notes |
|---|---|---|
| Source abstraction | `core/io/RandomAccessSource`, `FileChannelSource`, `SpoolingSource` | Random-access reads; `SpoolingSource` spools a non-seekable stream (e.g. pipe from a content provider) to a temp file while it is being indexed. |
| Encoding | `core/io/TextCodec` | BOM sniffing, UTF-8 validity over the head sample, UTF-16 BOM-less guess, windows-1251/1252 heuristic, binary signatures/NUL density. |
| Save | `core/io/SaveTarget`, `core/doc/DocumentSaver` | Temp file + atomic move (optional `.bak`), strict encoder (`UnencodableException` instead of silent `?`). |
| View mode | `core/doc/ViewDocument` | Read-only, streaming. Sparse index (one byte-offset anchor per 64 lines), 16 × 256 KB block LRU, 512-line string cache, lines capped at 65 536 chars (flagged truncated). Index thread publishes the first lines after the first 64 KB. |
| Edit mode | `core/doc/EditDocument` | Gap buffer + line-start table, undo/redo groups with typing/deleting coalescing, compound edits, saved-point tracking. |
| Open policy | `core/doc/DocumentOpener` | Files > 24 MB, files with very long lines, growing sources, or anything over the heap budget open as VIEW. EDIT can be forced and falls back to VIEW when out of budget. |
| Search | `core/search/CompiledQuery`, `SearchSession` | Literal fast path, regex (`java.util.regex`) with a deadline wrapper, whole word, case folding; results packed in a `LongArray` (capped at 5 M). |
| Replace | `core/search/Replacer` | `$n`/`\n`/`\t`/`\\` templates; in-memory replace-all as one undo step; streaming replace-all from source to a `SaveTarget` (strict decoding, EOL/BOM preserved, abort leaves the target untouched). |

## Tests (`app/src/test/java/app/codeedit/core/`)
`DocTests.kt` (34) + `SearchTests.kt` (19): 53 tests, all passing on JDK 17 + Kotlin 2.0.21 (`tools/kc.sh`). Covered: CRLF/CR/LF mixes, UTF-16 line terminators, cross-block lines, growth of a spooled source, long-line truncation, undo/redo coalescing and saved-point tracking, open policy, atomic save and failure paths, cp1251/UTF-16/BOM round trips, literal/regex/whole-word search, wrap-around, multi-line regex in EDIT snapshots, regex timeout (`(a+)+\1c`), replace templates, stream-replace with injected decode failure and cancellation.

## Measured on a 1 049 MB synthetic source file (15.2 M lines, some lines 0.5–3 KB), JVM `-Xmx256m`, 2 vCPU, page cache warm
(`tools/bench/Bench1G.kt`; this is a desktop JVM on a server disk, **not** a phone — treat as an upper bound on speed.)

| Operation | Result |
|---|---|
| Open (sniff + create view) | 52–76 ms |
| First 100 lines readable | ~9 ms (15 k lines already indexed at that moment) |
| Full index (15.2 M lines) | 2.9–3.3 s in the background; heap after index ≈ 4 MB |
| Random `getLine` | p50 0.14–0.18 ms, p99 1.4–3.1 ms |
| Literal search over the whole file | 4.5 s, heap 8 MB |
| Find next/previous across half the file | 2.2 s / instant |
| Regex search, 5 M matches (cap) | 7.6 s, heap 73 MB |
| Streaming replace-all (3.1 M replacements → 1 043 MB output) | 18 s, heap 8 MB |

Not measured: EditDocument at the 24 MB limit on a device, anything on a real phone, cold-cache storage.

## Known limits
* VIEW mode is read-only by design. Opening a huge file for editing is only offered for sizes the heap budget allows.
* Lines longer than 65 536 chars are shown truncated in VIEW mode; streaming replace still processes them in full.
* A regex that can match across lines only runs on EDIT-mode snapshots; in VIEW mode regexes match per line.
