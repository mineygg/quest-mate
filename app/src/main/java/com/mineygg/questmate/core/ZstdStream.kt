package com.mineygg.questmate.core

import com.github.luben.zstd.ZstdDecompressCtx
import java.io.ByteArrayOutputStream
import java.nio.Buffer
import java.nio.ByteBuffer

/**
 * One zstd-stream connection: every websocket frame is the next piece of ONE continuous zstd stream,
 * so the decompression context must live as long as the connection (a new one per socket).
 */
class ZstdStream : AutoCloseable {
    private val ctx = ZstdDecompressCtx()
    private val dst = ByteBuffer.allocateDirect(64 * 1024)
    private var closed = false

    /** Feeds one frame and returns everything it decompresses to. Fails if the stream was closed. */
    @Synchronized
    fun decode(frame: ByteArray): ByteArray {
        check(!closed) { "zstd stream closed" }
        val src = ByteBuffer.allocateDirect(frame.size)
        src.put(frame)
        (src as Buffer).flip() // via Buffer: ByteBuffer.flip() is Java 9+ and missing on older Android
        val out = ByteArrayOutputStream(frame.size * 4)
        val chunk = ByteArray(dst.capacity())
        while (true) {
            (dst as Buffer).clear()
            val before = src.remaining()
            val flushed = ctx.decompressDirectByteBufferStream(dst, src)
            (dst as Buffer).flip()
            val produced = dst.remaining()
            if (produced > 0) {
                dst.get(chunk, 0, produced)
                out.write(chunk, 0, produced)
            }
            if (!src.hasRemaining() && flushed) break
            if (produced == 0 && src.remaining() == before) break // no progress: don't spin
        }
        return out.toByteArray()
    }

    /** Waits for a decode in progress, then frees the native context (once). */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching { ctx.close() }
    }
}
