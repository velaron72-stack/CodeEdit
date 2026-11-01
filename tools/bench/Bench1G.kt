package app.codeedit.bench

import app.codeedit.core.doc.*
import app.codeedit.core.io.FileChannelSource
import app.codeedit.core.io.FileSaveTarget
import app.codeedit.core.search.*
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.Executor

private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000
private fun heapMb(): Long { val r = Runtime.getRuntime(); System.gc(); return (r.totalMemory() - r.freeMemory()) shr 20 }

/** Usage: Bench1G <file> [replaceOutDir]. Run with -Xmx256m to check the memory claim. */
fun main(args: Array<String>) {
    val f = File(args[0])
    println("file ${f.length() shr 20} MB, maxHeap ${Runtime.getRuntime().maxMemory() shr 20} MB")
    val ch = FileChannel.open(f.toPath(), StandardOpenOption.READ)
    val src = FileChannelSource(ch)
    var t0 = System.nanoTime()
    val res = DocumentOpener.open(src, f.name) as OpenResult.Ok
    val doc = res.doc
    println("open (sniff + doc create): ${ms(t0)} ms, mode=${res.info.mode}, enc=${res.info.encoding}")
    t0 = System.nanoTime()
    var s = 0; for (i in 0 until 100) s += doc.getLine(i).length
    println("first 100 lines: ${ms(t0)} ms (lineCount so far ${doc.lineCount})")
    t0 = System.nanoTime()
    while (!doc.isIndexComplete) Thread.sleep(5)
    println("full index: ${ms(t0)} ms, lines=${doc.lineCount}, heap after index ${heapMb()} MB")
    // random access
    val rnd = java.util.Random(7); val lat = LongArray(2000)
    for (i in lat.indices) { val l = rnd.nextInt(doc.lineCount); val t = System.nanoTime(); doc.getLine(l); lat[i] = System.nanoTime() - t }
    lat.sort(); println("random getLine: p50 ${lat[1000] / 1000} us, p99 ${lat[1980] / 1000} us, max ${lat[1999] / 1000} us")
    // sequential scroll of screens
    t0 = System.nanoTime(); var l = 0; var n = 0
    while (l < doc.lineCount - 60) { for (k in 0 until 60) n += doc.lineLength(l + k); l += 5000 }
    println("jump-scroll sampling ${doc.lineCount / 5000} screens: ${ms(t0)} ms")
    // streaming literal search
    t0 = System.nanoTime()
    val sess = SearchSession(doc, SearchQuery("THE_NEEDLE_AT_THE_END", matchCase = true), Executor { Thread(it).apply { isDaemon = true }.start() })
    sess.start(); while (!sess.isComplete) Thread.sleep(5)
    println("literal full-file search: ${ms(t0)} ms, matches=${sess.matchCount}, heap ${heapMb()} MB")
    t0 = System.nanoTime(); val m = sess.findNext(Pos(doc.lineCount / 2, 0)); println("findNext from middle -> ${m?.start} in ${ms(t0)} ms")
    t0 = System.nanoTime(); val mp = sess.findPrevious(Pos(doc.lineCount - 1, 5)); println("findPrevious -> ${mp?.start} in ${ms(t0)} ms")
    sess.close()
    t0 = System.nanoTime()
    val s2 = SearchSession(doc, SearchQuery("return \\w+ \\}\\s*$", regex = true), Executor { Thread(it).apply { isDaemon = true }.start() })
    s2.start(); val deadline = System.currentTimeMillis() + 120000; while (!s2.isComplete && System.currentTimeMillis() < deadline) Thread.sleep(5)
    println("regex full-file search: ${ms(t0)} ms, matches=${s2.matchCount}, complete=${s2.isComplete}, heap ${heapMb()} MB")
    s2.close()
    if (args.size > 1) {
        val outDir = File(args[1]); outDir.mkdirs()
        val target = FileSaveTarget(File(outDir, "out.txt"), keepBackup = false)
        t0 = System.nanoTime()
        val r = Replacer.streamReplaceAll(src, res.info, SearchQuery("value"), "VAL", target, null, null)
        println("stream replace all: ${ms(t0)} ms, ok=${r.ok}, replacements=${r.replacements}, out ${File(outDir, "out.txt").length() shr 20} MB, heap ${heapMb()} MB")
    }
    doc.close()
}
