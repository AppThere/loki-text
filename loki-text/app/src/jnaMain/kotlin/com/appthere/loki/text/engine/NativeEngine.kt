package com.appthere.loki.text.engine

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
    // Calls share the session; closing it waits for them, so a call running on another thread never uses a freed one.
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    actual fun callRaw(method: String, paramsJson: String?): String = lock.readLock().withLockDo {
        val h = handle ?: return@withLockDo """{"ok":false,"error":"engine closed"}"""
        val out = lib.loki_call(h, method, paramsJson) ?: return@withLockDo """{"ok":false,"error":"engine returned null"}"""
        try {
            out.getString(0, "UTF-8")
        } finally {
            lib.loki_string_free(out)
        }
    }

    actual fun renderPage(page: Int, scale: Float): PageBitmap? = lock.readLock().withLockDo {
        val h = handle ?: return@withLockDo null
        val w = IntByReference()
        val ht = IntByReference()
        val n = IntByReference()
        val p = lib.loki_render_page(h, page, scale, w, ht, n) ?: return@withLockDo null
        val len = w.value.toLong() * ht.value * 4
        try {
            PageBitmap(w.value, ht.value, n.value, p.getByteArray(0, len.toInt()))
        } finally {
            lib.loki_buffer_free(p, len)
        }
    }

    actual override fun close() = lock.writeLock().withLockDo {
        val h = handle ?: return@withLockDo
        handle = null
        lib.loki_session_free(h)
    }
}

private inline fun <T> java.util.concurrent.locks.Lock.withLockDo(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
