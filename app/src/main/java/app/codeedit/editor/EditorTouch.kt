package app.codeedit.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.OverScroller
import app.codeedit.core.doc.Pos
import app.codeedit.core.doc.Selection
import app.codeedit.core.edit.WordUtil
import app.codeedit.highlight.EditorColors

/** Gestures of [EditorView]: tap/double-tap/long-press, scroll + fling, pinch zoom, selection handles, fast-scroll thumb. */
internal class EditorTouch(private val v: EditorView) {
    private enum class Drag { NONE, THUMB, HANDLE_START, HANDLE_END, HANDLE_CARET, LONG_SELECT }

    private var drag = Drag.NONE
    private val scroller = OverScroller(v.context)
    private var lastFlingY = 0
    private var lastFlingX = 0
    private var axisLock = 0                 // 0 undecided, 1 vertical, 2 horizontal
    private var scaling = false
    private var lastX = 0f
    private var lastY = 0f
    private var grabDx = 0f
    private var grabDy = 0f
    private var fixedEnd = Pos.ZERO          // the end that stays put while dragging a handle / long-press selecting
    private var fixedEnd2 = Pos.ZERO
    private var thumbGrabOffset = 0f
    private var thumbGrow = 0f
    private var lastActivity = 0L
    private var lastFrame = 0L
    private var bubbleLine = -1
    private var autoScrollPosted = false

    private val thumbRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = FloatArray(2)
    private val cellOut = IntArray(1)

    private val gd = GestureDetector(v.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { stopFling(); return true }
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(e); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTapAt(e); return true }
        override fun onLongPress(e: MotionEvent) { onLongPressAt(e) }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaling) return false
            if (axisLock == 0) {
                val tdx = if (e1 != null) e2.x - e1.x else -dx
                val tdy = if (e1 != null) e2.y - e1.y else -dy
                axisLock = if (Math.abs(tdy) >= Math.abs(tdx) || v.layout.wrapping) 1 else 2
            }
            if (axisLock == 1) scrollVertical(dy) else scrollHorizontal(dx)
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling) return false
            startFling(vx, vy)
            return true
        }
    }).apply { setIsLongpressEnabled(true) }

    private val sd = ScaleGestureDetector(v.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            if (!v.config.pinchZoom || v.session == null) return false
            scaling = true; stopFling()
            val c = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
            gd.onTouchEvent(c); c.recycle()
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor); return true }
        override fun onScaleEnd(d: ScaleGestureDetector) { /* scaling flag is cleared on the final UP */ }
    }).apply { isQuickScaleEnabled = false }

    fun reset() {
        stopFling(); drag = Drag.NONE; scaling = false; axisLock = 0; bubbleLine = -1
        v.removeCallbacks(autoScroll); autoScrollPosted = false
    }

    // ------------------------------------------------------------------ events
    fun onTouchEvent(e: MotionEvent): Boolean {
        val s = v.session ?: return false
        if (!v.ensureBound()) return false
        if (v.config.pinchZoom) sd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; axisLock = 0
                v.parent?.requestDisallowInterceptTouchEvent(true)
                scaling = false
                startDragIfHit(e)
                if (drag != Drag.NONE) { stopFling(); return true }
            }
            MotionEvent.ACTION_MOVE -> if (drag != Drag.NONE) { applyDrag(e.x, e.y); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDrag = drag != Drag.NONE
                val wasScaling = scaling
                if (wasDrag) endDrag(e.actionMasked == MotionEvent.ACTION_UP)
                if (wasScaling) scaling = false
                if (wasDrag || wasScaling) return true
            }
        }
        if (sd.isInProgress || scaling || e.pointerCount > 1) return true
        return gd.onTouchEvent(e)
    }

    fun onGenericMotion(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_SCROLL || v.session == null) return false
        val vs = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val hs = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
        if ((e.metaState and android.view.KeyEvent.META_CTRL_ON) != 0 && v.config.pinchZoom) { zoomBy(if (vs > 0) 1.08f else 1f / 1.08f); return true }
        if (vs != 0f) scrollVertical(-vs * v.lineH * 3f)
        if (hs != 0f) scrollHorizontal(hs * v.charW * 6f)
        return true
    }

    // ------------------------------------------------------------------ scrolling
    private fun noteScroll() { lastActivity = SystemClock.uptimeMillis() }

    fun scrollVertical(dy: Float) {
        val s = v.session ?: return
        ViewportMath.scrollBy(s, v.layout, v.lineH, dy)
        noteScroll(); v.listener?.onScrollChanged(); v.postInvalidateOnAnimation()
    }

    private fun scrollHorizontal(dx: Float) {
        val s = v.session ?: return
        if (v.layout.wrapping) return
        s.scrollX += dx
        v.clampScrollX()
        noteScroll(); v.listener?.onScrollChanged(); v.postInvalidateOnAnimation()
    }

    private fun totalLines(): Long { val st = v.session!!.store; return maxOf(st.lineCount.toLong(), st.estimatedLineCount) }

    private fun startFling(vx: Float, vy: Float) {
        val s = v.session ?: return
        val boost = Math.sqrt(totalLines() / 20_000.0).coerceIn(1.0, 25.0).toFloat()
        lastFlingY = 0; lastFlingX = 0
        if (axisLock == 2) scroller.fling(0, 0, (-vx).toInt(), 0, -BIG, BIG, 0, 0)
        else scroller.fling(0, 0, 0, (-vy * boost).toInt(), 0, 0, -BIG, BIG)
        v.postInvalidateOnAnimation()
    }

    fun stopFling() { if (!scroller.isFinished) scroller.abortAnimation() }

    fun computeFling() {
        if (v.session == null) return
        if (scroller.computeScrollOffset()) {
            val dy = scroller.currY - lastFlingY; val dx = scroller.currX - lastFlingX
            lastFlingY = scroller.currY; lastFlingX = scroller.currX
            if (dy != 0) scrollVertical(dy.toFloat())
            if (dx != 0) scrollHorizontal(dx.toFloat())
            v.postInvalidateOnAnimation()
        }
    }

    private fun zoomBy(factor: Float) {
        val s = v.session ?: return
        val old = v.effectiveSp()
        val ns = (old * factor).coerceIn(v.config.minFontSizeSp, v.config.maxFontSizeSp)
        if (Math.abs(ns - old) < 0.01f) return
        val oldLineH = v.lineH
        s.fontSizeSp = ns
        v.applyMetrics(); v.updateWrap()
        s.topOffsetPx *= v.lineH / oldLineH
        ViewportMath.normalize(s, v.layout, v.lineH)
        v.clampScrollX()
        v.listener?.onFontSizeChanged(ns)
        v.invalidate()
    }

    // ------------------------------------------------------------------ taps
    private fun onTap(e: MotionEvent) {
        val s = v.session ?: return
        v.requestFocus()
        if (e.x < v.gutterW) { onGutterTap(e); return }
        val p = v.posAt(e.x, e.y)
        v.closeActionMode()
        v.setSelection(Selection.caret(p), scroll = false)
        v.showCaretHandle = s.editable != null
        if (s.editable != null) v.requestFocusAndShowKeyboard()
        v.invalidate()
    }

    private fun onGutterTap(e: MotionEvent) {
        val s = v.session ?: return
        val hit = ViewportMath.hit(s, v.layout, v.lineH, e.y)
        if (hit.beyondEnd && e.y > hit.lineTopY + v.layout.rowCount(hit.line) * v.lineH) return
        if (v.config.folding && e.x > v.gutterW - v.foldColW - v.dp(4f)) { v.foldToggleAt(hit.line); return }
        if (v.config.showLineNumbers) {
            val st = s.store
            val l = hit.line
            val sel = if (l + 1 < st.lineCount) Selection(Pos(l, 0), Pos(l + 1, 0)) else Selection(Pos(l, 0), Pos(l, st.lineLength(l)))
            v.setSelection(sel, scroll = false)
            v.showCaretHandle = false
            v.startSelectionActionMode()
        }
    }

    private fun wordSelectionAt(p: Pos): Selection? {
        val s = v.session ?: return null
        val len = v.utilBuf.fill(s.store, p.line)
        if (len == 0) return null
        val packed = WordUtil.wordRangeAt(v.utilBuf.chars, len, p.col)
        return Selection(Pos(p.line, (packed ushr 32).toInt()), Pos(p.line, (packed and 0xFFFFFFFFL).toInt()))
    }

    private fun onDoubleTapAt(e: MotionEvent) {
        if (!v.config.doubleTapSelectsWord || e.x < v.gutterW) return
        val p = v.posAt(e.x, e.y)
        val w = wordSelectionAt(p) ?: return
        v.setSelection(w, scroll = false)
        v.showCaretHandle = false
        v.startSelectionActionMode()
        v.invalidate()
    }

    private fun onLongPressAt(e: MotionEvent) {
        val s = v.session ?: return
        if (e.x < v.gutterW || drag != Drag.NONE || scaling) return
        v.haptic()
        val p = v.posAt(e.x, e.y)
        val w = wordSelectionAt(p) ?: Selection.caret(p)
        v.setSelection(w, scroll = false)
        v.showCaretHandle = false
        fixedEnd = w.start; fixedEnd2 = w.end
        drag = Drag.LONG_SELECT
        v.parent?.requestDisallowInterceptTouchEvent(true)
        v.startSelectionActionMode()
        v.invalidate()
    }

    // ------------------------------------------------------------------ dragging (thumb / handles / long-press select)
    private fun thumbGeometry(out: RectF, grown: Float): Boolean {
        val s = v.session ?: return false
        if (!v.config.fastScroll) return false
        val total = totalLines()
        val rows = v.viewportH() / v.lineH
        if (total <= rows * 1.5f) return false
        val trackTop = v.dp(4f); val trackBottom = v.viewportH() - v.dp(4f)
        val trackH = trackBottom - trackTop
        if (trackH <= 0f) return false
        val thumbH = (trackH * rows / total).coerceIn(v.dp(40f), maxOf(v.dp(40f), trackH * 0.6f))
        val frac = ViewportMath.fraction(s, total)
        val top = trackTop + (trackH - thumbH) * frac
        val wdt = v.dp(3f) + (v.dp(12f) - v.dp(3f)) * grown
        val right = v.width - v.dp(2f)
        out.set(right - wdt, top, right, top + thumbH)
        return true
    }

    private fun startDragIfHit(e: MotionEvent) {
        val s = v.session ?: return
        // thumb
        if (thumbGeometry(thumbRect, thumbGrow) && e.x >= v.width - v.dp(30f) &&
            e.y >= thumbRect.top - v.dp(12f) && e.y <= thumbRect.bottom + v.dp(12f)) {
            drag = Drag.THUMB
            thumbGrabOffset = e.y - thumbRect.top
            bubbleLine = s.topLine
            noteScroll(); v.haptic()
            v.invalidate()
            return
        }
        // handles
        val sel = s.selection
        val editable = s.editable != null
        val r = v.dp(26f)
        if (!sel.isEmpty) {
            if (handlePoint(sel.start, pt) && near(e, pt, r)) { startHandleDrag(Drag.HANDLE_START, e, sel.end, pt); return }
            if (handlePoint(sel.end, pt) && near(e, pt, r)) { startHandleDrag(Drag.HANDLE_END, e, sel.start, pt); return }
        } else if (v.showCaretHandle && editable) {
            if (handlePoint(sel.head, pt) && near(e, pt, r)) { startHandleDrag(Drag.HANDLE_CARET, e, sel.head, pt); return }
        }
    }

    private fun near(e: MotionEvent, p: FloatArray, r: Float): Boolean {
        val cx = p[0]; val cy = p[1] + v.dp(11f)
        val dx = e.x - cx; val dy = e.y - cy
        return dx * dx + dy * dy <= r * r
    }

    private fun startHandleDrag(d: Drag, e: MotionEvent, fixed: Pos, handle: FloatArray) {
        drag = d
        fixedEnd = fixed
        grabDx = e.x - handle[0]
        grabDy = e.y - (handle[1] - v.lineH / 2f)
        v.closeActionMode()
        v.invalidate()
    }

    /** x = left edge of the char at p, y = bottom of its row (view coordinates). */
    private fun handlePoint(p: Pos, out: FloatArray): Boolean {
        val s = v.session ?: return false
        if (s.folds.isHidden(p.line)) return false
        val y0 = ViewportMath.yOfLineTop(s, v.layout, v.lineH, p.line, 400) ?: return false
        val row = v.rowAndCells(s.clampPos(p), cellOut)
        val x = v.cellX(cellOut[0]); val y = y0 + row * v.lineH + v.lineH
        if (y < 0f || y - v.lineH > v.height || x < v.gutterW - v.charW || x > v.width + v.charW) return false
        out[0] = x; out[1] = y
        return true
    }

    private fun applyDrag(x: Float, y: Float) {
        val s = v.session ?: return
        lastX = x; lastY = y
        when (drag) {
            Drag.THUMB -> {
                if (!thumbGeometry(thumbRect, thumbGrow)) return
                val trackTop = v.dp(4f); val trackH = v.viewportH() - v.dp(8f)
                val thumbH = thumbRect.height()
                val denom = (trackH - thumbH).coerceAtLeast(1f)
                val frac = ((y - thumbGrabOffset - trackTop) / denom).coerceIn(0f, 1f)
                val line = ViewportMath.lineForFraction(frac, s.store.lineCount, totalLines())
                s.topLine = s.folds.visibleAnchor(line); s.topOffsetPx = 0f
                bubbleLine = s.topLine
                noteScroll(); v.listener?.onScrollChanged(); v.invalidate()
            }
            Drag.HANDLE_START, Drag.HANDLE_END, Drag.HANDLE_CARET -> {
                val p = v.posAt(x - grabDx, y - grabDy)
                val sel = if (drag == Drag.HANDLE_CARET) Selection.caret(p) else Selection(fixedEnd, p)
                v.setSelection(sel, scroll = false)
                maybeAutoScroll()
            }
            Drag.LONG_SELECT -> {
                val p = v.posAt(x, y)
                val sel = if (p < fixedEnd) Selection(fixedEnd2, p) else Selection(fixedEnd, p)
                v.setSelection(sel, scroll = false)
                maybeAutoScroll()
            }
            Drag.NONE -> {}
        }
    }

    private fun endDrag(up: Boolean) {
        val d = drag
        drag = Drag.NONE
        v.removeCallbacks(autoScroll); autoScrollPosted = false
        bubbleLine = -1
        noteScroll()
        val s = v.session ?: return
        if (d == Drag.HANDLE_START || d == Drag.HANDLE_END || d == Drag.LONG_SELECT) { if (!s.selection.isEmpty) v.startSelectionActionMode() }
        v.invalidate()
    }

    private val autoScroll = object : Runnable {
        override fun run() {
            autoScrollPosted = false
            if (drag == Drag.NONE || drag == Drag.THUMB) return
            val vh = v.viewportH()
            val zone = v.lineH * 1.6f
            var dy = 0f; var dx = 0f
            if (lastY < zone) dy = -(zone - lastY) * 0.4f - 2f
            else if (lastY > vh - zone) dy = (lastY - (vh - zone)) * 0.4f + 2f
            if (!v.layout.wrapping) {
                if (lastX < v.gutterW + v.charW * 2) dx = -(v.gutterW + v.charW * 2 - lastX) * 0.3f - 2f
                else if (lastX > v.width - v.charW * 3) dx = (lastX - (v.width - v.charW * 3)) * 0.3f + 2f
            }
            if (dy != 0f || dx != 0f) {
                if (dy != 0f) scrollVertical(dy)
                if (dx != 0f) scrollHorizontal(dx)
                applyDrag(lastX, lastY)
                v.postOnAnimation(this); autoScrollPosted = true
            }
        }
    }

    private fun maybeAutoScroll() {
        if (autoScrollPosted) return
        val vh = v.viewportH(); val zone = v.lineH * 1.6f
        if (lastY < zone || lastY > vh - zone || lastX < v.gutterW + v.charW * 2 || lastX > v.width - v.charW * 3) {
            autoScrollPosted = true; v.postOnAnimation(autoScroll)
        }
    }

    // ------------------------------------------------------------------ overlays
    fun needsAnimation(): Boolean {
        if (drag == Drag.THUMB) return true
        if (Math.abs(thumbGrow) > 0.001f) return true
        val idle = SystemClock.uptimeMillis() - lastActivity
        return idle < THUMB_SHOW_MS + THUMB_FADE_MS
    }

    fun drawOverlays(c: Canvas, col: EditorColors) {
        val s = v.session ?: return
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 64L)
        lastFrame = now
        // thumb growth animation
        val target = if (drag == Drag.THUMB) 1f else 0f
        thumbGrow += (target - thumbGrow) * (1f - Math.pow(0.001, dt / 120.0).toFloat())
        if (Math.abs(target - thumbGrow) < 0.01f) thumbGrow = target
        val idle = now - lastActivity
        val alpha = if (drag == Drag.THUMB || thumbGrow > 0f) 1f
        else if (idle < THUMB_SHOW_MS) 1f else (1f - (idle - THUMB_SHOW_MS).toFloat() / THUMB_FADE_MS).coerceIn(0f, 1f)
        if (alpha > 0f && thumbGeometry(thumbRect, thumbGrow)) {
            paint.style = Paint.Style.FILL
            val base = col.scrollbarThumb
            val a = ((base ushr 24) * alpha).toInt().coerceIn(0, 255)
            paint.color = (base and 0x00FFFFFF) or (a shl 24)
            val rr = thumbRect.width() / 2f
            c.drawRoundRect(thumbRect, rr, rr, paint)
            if (bubbleLine >= 0 && drag == Drag.THUMB) drawBubble(c, col)
        }
        // handles
        if (v.hasFocus() || drag != Drag.NONE) drawHandles(c, s, col)
    }

    private fun drawBubble(c: Canvas, col: EditorColors) {
        val text = (bubbleLine + v.config.lineNumberBase).toString().reversed().chunked(3).joinToString(" ").reversed()
        val p = v.lnPaint
        p.color = col.foreground
        val tw = p.measureText(text)
        val padH = v.dp(10f); val padV = v.dp(6f)
        val h = (p.descent() - p.ascent()) + padV * 2
        val w = tw + padH * 2
        val right = thumbRect.left - v.dp(10f)
        val cy = thumbRect.centerY().coerceIn(h / 2f + v.dp(4f), v.viewportH() - h / 2f - v.dp(4f))
        val r = RectF(right - w, cy - h / 2f, right, cy + h / 2f)
        paint.style = Paint.Style.FILL
        paint.color = if (col.isDark) 0xF2262626.toInt() else 0xF2FFFFFF.toInt()
        c.drawRoundRect(r, h / 2f, h / 2f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = v.dp(1f)
        paint.color = col.indentGuideActive
        c.drawRoundRect(r, h / 2f, h / 2f, paint)
        paint.style = Paint.Style.FILL
        p.textAlign = Paint.Align.LEFT
        c.drawText(text, r.left + padH, r.centerY() - (p.descent() + p.ascent()) / 2f, p)
    }

    private fun drawHandles(c: Canvas, s: app.codeedit.core.edit.EditorSession, col: EditorColors) {
        val sel = s.selection
        val editable = s.editable != null
        paint.style = Paint.Style.FILL
        paint.color = col.scrollbarThumb or 0xFF000000.toInt()
        if (!sel.isEmpty) {
            if (handlePoint(sel.start, pt)) drawHandle(c, pt[0], pt[1])
            if (handlePoint(sel.end, pt)) drawHandle(c, pt[0], pt[1])
        } else if (v.showCaretHandle && editable) {
            if (handlePoint(sel.head, pt)) drawHandle(c, pt[0], pt[1])
        }
    }

    private fun drawHandle(c: Canvas, x: Float, y: Float) {
        val r = v.dp(8f)
        c.drawRect(x - v.dp(1f), y - v.lineH, x + v.dp(1f), y, paint)
        c.drawCircle(x, y + r * 0.9f, r, paint)
    }

    fun selectionRect(out: Rect) {
        val s = v.session
        if (s == null) { out.set(0, 0, v.width, 1); return }
        val a = s.selection.start
        val b = s.selection.end
        var top = 0f; var bottom = 1f; var left = 0f; var right = v.width.toFloat()
        if (handlePoint(a, pt)) { top = pt[1] - v.lineH; left = pt[0]; bottom = pt[1]; right = pt[0] + 1f }
        if (handlePoint(b, pt)) { bottom = maxOf(bottom, pt[1]); right = maxOf(right, pt[0]); if (top == 0f && left == 0f) { top = pt[1] - v.lineH; left = pt[0] } }
        out.set(left.toInt(), top.toInt().coerceAtLeast(0), right.toInt().coerceAtLeast(left.toInt() + 1), bottom.toInt().coerceAtLeast(top.toInt() + 1))
    }

    private companion object {
        const val BIG = 1_000_000_000
        const val THUMB_SHOW_MS = 900L
        const val THUMB_FADE_MS = 350L
    }
}
