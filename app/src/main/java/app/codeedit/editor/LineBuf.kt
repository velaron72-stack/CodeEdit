package app.codeedit.editor

import app.codeedit.core.doc.LineStore

/** Reusable char buffer holding one document line. */
internal class LineBuf {
    @JvmField var chars = CharArray(256)
    @JvmField var len = 0
    fun fill(store: LineStore, line: Int): Int {
        val n = store.lineLength(line)
        if (chars.size < n) chars = CharArray(maxOf(n, chars.size * 2))
        len = if (n == 0) 0 else store.copyLine(line, 0, n, chars, 0)
        return len
    }
}
