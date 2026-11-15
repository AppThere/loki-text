@file:OptIn(ExperimentalForeignApi::class)

package app.loki.text.engine

import app.loki.text.native.loki_buffer_free
import app.loki.text.native.loki_call
import app.loki.text.native.loki_render_page
import app.loki.text.native.loki_session_free
import app.loki.text.native.loki_session_new
import app.loki.text.native.loki_string_free
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

actual class NativeEngine actual constructor() : AutoCloseable {
    private var handle = loki_session_new()

    actual fun callRaw(method: String, paramsJson: String?): String {
        val h = handle ?: return """{"ok":false,"error":"engine closed"}"""
        val out = loki_call(h, method, paramsJson) ?: return """{"ok":false,"error":"engine returned null"}"""
        try {
            return out.toKString()
        } finally {
            loki_string_free(out)
        }
    }

    actual fun renderPage(page: Int, scale: Float): PageBitmap? = memScoped {
        val h = handle ?: return null
        val w = alloc<kotlinx.cinterop.UIntVar>()
        val ht = alloc<kotlinx.cinterop.UIntVar>()
        val n = alloc<kotlinx.cinterop.UIntVar>()
        val p = loki_render_page(h, page.toUInt(), scale, w.ptr, ht.ptr, n.ptr) ?: return null
        val len = w.value.toInt() * ht.value.toInt() * 4
        try {
            PageBitmap(w.value.toInt(), ht.value.toInt(), n.value.toInt(), p.readBytes(len))
        } finally {
            loki_buffer_free(p, len.toULong())
        }
    }

    actual override fun close() {
        handle?.let { loki_session_free(it) }
        handle = null
    }
}
