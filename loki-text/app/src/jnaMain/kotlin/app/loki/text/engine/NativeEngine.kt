package app.loki.text.engine

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

private interface Loki : Library {
    fun loki_session_new(): Pointer?
    fun loki_session_free(s: Pointer?)
    fun loki_call(s: Pointer?, method: String, paramsJson: String?): Pointer?
    fun loki_string_free(p: Pointer?)
    fun loki_render_page(s: Pointer?, page: Int, scale: Float, width: IntByReference, height: IntByReference, pages: IntByReference): Pointer?
    fun loki_buffer_free(p: Pointer?, len: Long)
}

private val lib: Loki by lazy {
    // Desktop: -Dloki.lib=<dir with libloki_ffi>. Android: the .so is in jniLibs, found by name.
    System.getProperty("loki.lib")?.let { System.setProperty("jna.library.path", it) }
    Native.load("loki_ffi", Loki::class.java)
}

actual class NativeEngine actual constructor() : AutoCloseable {
    private var handle: Pointer? = lib.loki_session_new()

    actual fun callRaw(method: String, paramsJson: String?): String {
        val h = handle ?: return """{"ok":false,"error":"engine closed"}"""
        val out = lib.loki_call(h, method, paramsJson) ?: return """{"ok":false,"error":"engine returned null"}"""
        try {
            return out.getString(0, "UTF-8")
        } finally {
            lib.loki_string_free(out)
        }
    }

    actual fun renderPage(page: Int, scale: Float): PageBitmap? {
        val h = handle ?: return null
        val w = IntByReference()
        val ht = IntByReference()
        val n = IntByReference()
        val p = lib.loki_render_page(h, page, scale, w, ht, n) ?: return null
        val len = w.value.toLong() * ht.value * 4
        try {
            return PageBitmap(w.value, ht.value, n.value, p.getByteArray(0, len.toInt()))
        } finally {
            lib.loki_buffer_free(p, len)
        }
    }

    actual override fun close() {
        lib.loki_session_free(handle)
        handle = null
    }
}
