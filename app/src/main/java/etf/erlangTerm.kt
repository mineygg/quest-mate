/**
 * Mapping (mirrors the original erlpack-replacement semantics):
 *   JVM value                          -> ETF term
 *   null                                -> small_atom_ext "nil"
 *   Boolean                             -> small_atom_ext "true"/"false"
 *   Number (integral, fits ranges)      -> small_integer_ext / integer_ext / small_big_ext
 *   Number (non-integral or oversized)  -> new_float_ext
 *   String                              -> binary_ext
 *   List<*>                             -> list_ext (or nil_ext if empty)
 *   Map<*, *>                           -> map_ext
 *   anything else                       -> silently ignored (matches original)
 *
 *   ETF term                            -> JVM value
 *   small_integer_ext / integer_ext      -> Int
 *   small_big_ext / large_big_ext        -> Int/Long (if it fits) or String (if not)
 *   float_ext / new_float_ext            -> Double
 *   atom / small_atom / *_utf8 variants  -> null / Boolean / String (per ProcessAtom rules)
 *   small_tuple_ext / large_tuple_ext    -> List<Any?>
 *   nil_ext                              -> empty List<Any?>
 *   string_ext                           -> List<Int> (char codes, matches original)
 *   list_ext                             -> List<Any?>
 *   map_ext                              -> LinkedHashMap<Any?, Any?>
 *   binary_ext                           -> String
 *   reference_ext / new_reference_ext    -> Map with node/id/creation
 *   port_ext                             -> Map with node/id/creation
 *   pid_ext                              -> Map with node/id/serial/creation
 *   export_ext                           -> Map with mod/fun/arity
 *   compressed_ext                       -> throws (unsupported, same as original)
 */

package etf

import java.nio.ByteBuffer
import java.nio.ByteOrder

// ============================================================================
// ETF Constants
// ============================================================================

private const val FORMAT_VERSION: Int = 131

private const val NEW_FLOAT_EXT: Int = 70               // 'F'
private const val COMPRESSED_EXT: Int = 80              // 'P'
private const val SMALL_INTEGER_EXT: Int = 97           // 'a'
private const val INTEGER_EXT: Int = 98                 // 'b'
private const val FLOAT_EXT: Int = 99                   // 'c'
private const val ATOM_EXT: Int = 100                   // 'd'
private const val REFERENCE_EXT: Int = 101              // 'e'
private const val PORT_EXT: Int = 102                   // 'f'
private const val PID_EXT: Int = 103                    // 'g'
private const val SMALL_TUPLE_EXT: Int = 104            // 'h'
private const val LARGE_TUPLE_EXT: Int = 105            // 'i'
private const val NIL_EXT: Int = 106                    // 'j'
private const val STRING_EXT: Int = 107                 // 'k'
private const val LIST_EXT: Int = 108                   // 'l'
private const val BINARY_EXT: Int = 109                 // 'm'
private const val SMALL_BIG_EXT: Int = 110              // 'n'
private const val LARGE_BIG_EXT: Int = 111              // 'o'
private const val EXPORT_EXT: Int = 113                 // 'q'
private const val NEW_REFERENCE_EXT: Int = 114          // 'r'
private const val SMALL_ATOM_EXT: Int = 115             // 's'
private const val MAP_EXT: Int = 116                    // 't'
private const val ATOM_UTF8_EXT: Int = 118              // 'v'
private const val SMALL_ATOM_UTF8_EXT: Int = 119        // 'w'

private const val INITIAL_BUFFER_SIZE = 1024
private const val MAX_RECURSION_DEPTH = 256
private const val UINT8_MAX_DOUBLE = 255.0
private const val SIGN_POSITIVE: Int = 0
private const val SIGN_NEGATIVE: Int = 1
private const val FLOAT_STR_MAX_LEN = 31
private const val BITS_PER_BYTE = 8
private const val BITS_IN_INT32 = 31

class EtfException(message: String) : RuntimeException(message)

// ============================================================================
// Encoder  --  JVM value -> ETF binary
// ============================================================================

/**
 * Growable big-endian byte writer (mirrors EtfBuffer's 2x-growth vector).
 */
private class EtfBuffer(initialCapacity: Int = INITIAL_BUFFER_SIZE) {
    private var storage: ByteArray = ByteArray(initialCapacity)
    private var length: Int = 0

    private fun ensureCapacity(extra: Int) {
        val needed = length + extra
        if (needed <= storage.size) return
        var newCap = if (storage.isEmpty()) INITIAL_BUFFER_SIZE else storage.size
        while (newCap < needed) newCap *= 2
        storage = storage.copyOf(newCap)
    }

    fun write(bytes: ByteArray, count: Int = bytes.size) {
        ensureCapacity(count)
        System.arraycopy(bytes, 0, storage, length, count)
        length += count
    }

    fun write8(value: Int) {
        ensureCapacity(1)
        storage[length] = (value and 0xFF).toByte()
        length += 1
    }

    fun write16(value: Int) {
        ensureCapacity(2)
        storage[length] = ((value ushr 8) and 0xFF).toByte()
        storage[length + 1] = (value and 0xFF).toByte()
        length += 2
    }

    fun write32(value: Long) {
        ensureCapacity(4)
        storage[length] = ((value ushr 24) and 0xFF).toByte()
        storage[length + 1] = ((value ushr 16) and 0xFF).toByte()
        storage[length + 2] = ((value ushr 8) and 0xFF).toByte()
        storage[length + 3] = (value and 0xFF).toByte()
        length += 4
    }

    fun write64(value: Long) {
        ensureCapacity(8)
        for (i in 0 until 8) {
            val shift = (7 - i) * BITS_PER_BYTE
            storage[length + i] = ((value ushr shift) and 0xFF).toByte()
        }
        length += 8
    }

    /** Release ownership as an exact-size ByteArray. */
    fun toByteArray(): ByteArray = storage.copyOf(length)
}

class EtfEncoder {
    private val pk = EtfBuffer()

    init {
        pk.write8(FORMAT_VERSION)
    }

    fun toByteArray(): ByteArray = pk.toByteArray()

    /**
     * Encode a JVM value into the internal buffer.
     * Accepted types: null, Boolean, Number (Byte/Short/Int/Long/Float/Double/BigInteger-ish),
     * String, List<*>, Map<*, *>. Anything else is silently ignored (matches original).
     */
    fun pack(value: Any?, depth: Int = MAX_RECURSION_DEPTH): Boolean {
        if (depth < 0) {
            throw EtfException("Reached recursion limit")
        }

        return when (value) {
            null -> appendAtomSmall("nil")
            is Boolean -> if (value) appendAtomSmall("true") else appendAtomSmall("false")
            is Number -> packNumber(value)
            is String -> appendBinary(value)
            is List<*> -> packArray(value, depth)
            is Map<*, *> -> packObject(value, depth)
            else -> true // ignore unknown types silently, matches original
        }
    }

    // -- number packing --------------------------------------------------------
    private fun packNumber(num: Number): Boolean {
        val dblVal: Double = num.toDouble()
        var isInt32 = false
        var isUint32 = false

        if (Math.floor(dblVal) == dblVal && !dblVal.isInfinite()) {
            if (dblVal >= Int.MIN_VALUE.toDouble() && dblVal <= Int.MAX_VALUE.toDouble()) {
                isInt32 = true
            }
            if (dblVal >= 0.0 && dblVal <= 4294967295.0 /* UINT32_MAX */) {
                isUint32 = true
            }
        }

        if (isInt32 || isUint32) {
            if (dblVal >= 0.0 && dblVal <= UINT8_MAX_DOUBLE) {
                pk.write8(SMALL_INTEGER_EXT)
                pk.write8(dblVal.toInt())
                return true
            }
            if (isInt32) {
                pk.write8(INTEGER_EXT)
                pk.write32(dblVal.toLong() and 0xFFFFFFFFL)
                return true
            }
            // is_uint32 -- encode as small_big_ext
            return appendUint64(dblVal.toLong())
        }
        // double
        pk.write8(NEW_FLOAT_EXT)
        pk.write64(java.lang.Double.doubleToLongBits(dblVal))
        return true
    }

    private fun appendUint64(v: Long): Boolean {
        // v is treated as an unsigned 64-bit quantity encoded little-endian, per ETF small_big_ext.
        val dataBuf = ByteArray(3 + 8)
        dataBuf[0] = SMALL_BIG_EXT.toByte()
        var byteCount = 0
        var remaining = v
        while (remaining != 0L) {
            dataBuf[3 + byteCount] = (remaining and 0xFF).toByte()
            remaining = remaining ushr BITS_PER_BYTE
            byteCount++
        }
        dataBuf[1] = byteCount.toByte()
        dataBuf[2] = SIGN_POSITIVE.toByte()
        pk.write(dataBuf, 3 + byteCount)
        return true
    }

    // -- atoms -----------------------------------------------------------------
    private fun appendAtomSmall(str: String): Boolean {
        val bytes = str.toByteArray(Charsets.UTF_8)
        pk.write8(SMALL_ATOM_EXT)
        pk.write8(bytes.size)
        pk.write(bytes)
        return true
    }

    // -- binary ----------------------------------------------------------------
    private fun appendBinary(str: String): Boolean {
        val bytes = str.toByteArray(Charsets.UTF_8)
        pk.write8(BINARY_EXT)
        pk.write32(bytes.size.toLong())
        pk.write(bytes)
        return true
    }

    // -- array -----------------------------------------------------------------
    private fun packArray(arr: List<*>, depth: Int): Boolean {
        val length = arr.size
        if (length == 0) {
            pk.write8(NIL_EXT)
            return true
        }
        pk.write8(LIST_EXT)
        pk.write32(length.toLong())
        for (element in arr) {
            if (!pack(element, depth - 1)) return false
        }
        pk.write8(NIL_EXT) // tail
        return true
    }

    // -- object / map ----------------------------------------------------------
    private fun packObject(obj: Map<*, *>, depth: Int): Boolean {
        // First pass: count non-null-marker (i.e. non-"undefined") values.
        // Kotlin has no separate "undefined"; we treat all present map entries as valid,
        // matching the JS semantics of skipping only explicit `undefined` values.
        val entries = obj.entries.toList()
        pk.write8(MAP_EXT)
        pk.write32(entries.size.toLong())

        for ((key, mapVal) in entries) {
            if (!pack(key, depth - 1)) return false
            if (!pack(mapVal, depth - 1)) return false
        }
        return true
    }

    companion object {
        /** Convenience one-shot encode. */
        fun pack(value: Any?): ByteArray {
            val encoder = EtfEncoder()
            encoder.pack(value)
            return encoder.toByteArray()
        }
    }
}

// ============================================================================
// Decoder  --  ETF binary -> JVM value
// ============================================================================

class EtfDecoder(bytes: ByteArray) {
    private val buf: ByteArray = bytes
    private val sz: Int = bytes.size
    private var pos: Int = 0

    init {
        val ver = r8()
        if (ver != FORMAT_VERSION) {
            throw EtfException("Bad version number.")
        }
    }

    fun unpack(): Any? {
        if (pos >= sz) {
            throw EtfException("Unpacking beyond the end of the buffer")
        }

        return when (val tag = r8()) {
            SMALL_INTEGER_EXT -> r8()
            INTEGER_EXT -> r32().toInt()
            FLOAT_EXT -> decodeFloat()
            NEW_FLOAT_EXT -> decodeNewFloat()
            ATOM_EXT -> {
                val atomLen = r16()
                processAtom(readBytes(atomLen))
            }
            SMALL_ATOM_EXT -> {
                val atomLen = r8()
                processAtom(readBytes(atomLen))
            }
            ATOM_UTF8_EXT -> {
                val atomLen = r16()
                processAtom(readBytes(atomLen))
            }
            SMALL_ATOM_UTF8_EXT -> {
                val atomLen = r8()
                processAtom(readBytes(atomLen))
            }
            SMALL_TUPLE_EXT -> decodeArray(r8())
            LARGE_TUPLE_EXT -> decodeArray(r32())
            NIL_EXT -> emptyList<Any?>()
            STRING_EXT -> decodeStringAsList()
            LIST_EXT -> decodeList()
            MAP_EXT -> decodeMap()
            BINARY_EXT -> decodeBinaryAsString()
            SMALL_BIG_EXT -> decodeBig(r8())
            LARGE_BIG_EXT -> decodeBig(r32().toInt())
            REFERENCE_EXT -> decodeReference()
            NEW_REFERENCE_EXT -> decodeNewReference()
            PORT_EXT -> decodePort()
            PID_EXT -> decodePid()
            EXPORT_EXT -> decodeExport()
            COMPRESSED_EXT -> decodeCompressed()
            else -> throw EtfException("Unsupported erlang term type identifier found: $tag")
        }
    }

    // -- primitive reads -------------------------------------------------------
    private fun r8(): Int {
        if (pos + 1 > sz) throw EtfException("Read past end of buffer")
        val v = buf[pos].toInt() and 0xFF
        pos += 1
        return v
    }

    private fun r16(): Int {
        if (pos + 2 > sz) throw EtfException("Read past end of buffer")
        val v = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return v
    }

    private fun r32(): Long {
        if (pos + 4 > sz) throw EtfException("Read past end of buffer")
        var v = 0L
        for (i in 0 until 4) {
            v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
        }
        pos += 4
        return v
    }

    private fun r64(): Long {
        if (pos + 8 > sz) throw EtfException("Read past end of buffer")
        var v = 0L
        for (i in 0 until 8) {
            v = (v shl 8) or (buf[pos + i].toLong() and 0xFF)
        }
        pos += 8
        return v
    }

    private fun readBytes(count: Int): ByteArray {
        if (pos + count > sz) throw EtfException("Read past end of buffer")
        val out = buf.copyOfRange(pos, pos + count)
        pos += count
        return out
    }

    // -- atom ------------------------------------------------------------------
    private fun processAtom(atomBytes: ByteArray): Any? {
        val atomStr = String(atomBytes, Charsets.UTF_8)
        return when (atomStr) {
            "nil", "null" -> null
            "true" -> true
            "false" -> false
            else -> atomStr
        }
    }

    // -- float -----------------------------------------------------------------
    private fun decodeFloat(): Double {
        val dataBytes = readBytes(FLOAT_STR_MAX_LEN)
        // The original C string is NUL-padded and parsed with strtod, which stops
        // at the first non-numeric character (typically a NUL byte or trailing space).
        val str = String(dataBytes, Charsets.US_ASCII)
        val trimmed = str.substringBefore('\u0000').trim()
        return try {
            trimmed.toDouble()
        } catch (e: NumberFormatException) {
            throw EtfException("Invalid float")
        }
    }

    private fun decodeNewFloat(): Double {
        val bits = r64()
        return java.lang.Double.longBitsToDouble(bits)
    }

    // -- big integer -----------------------------------------------------------
    private fun decodeBig(digits: Int): Any {
        val sign = r8()
        if (digits > 8) {
            throw EtfException("Big int > 8 bytes unsupported")
        }
        var value = 0L
        var multiplier = 1L
        for (i in 0 until digits) {
            value += (r8().toLong()) * multiplier
            multiplier = multiplier shl BITS_PER_BYTE
        }

        if (digits <= 4) {
            if (sign == SIGN_POSITIVE) {
                return (value and 0xFFFFFFFFL) // unsigned 32-bit value, fits in Long safely
            }
            if ((value and (1L shl BITS_IN_INT32)) == 0L) {
                return -(value.toInt())
            }
        }

        // Represent as decimal string, matching original's fallback behavior.
        return if (sign == SIGN_NEGATIVE) "-$value" else value.toString()
    }

    // -- containers ------------------------------------------------------------
    private fun decodeArray(length: Int): List<Any?> {
        val arr = ArrayList<Any?>(length)
        for (i in 0 until length) {
            arr.add(unpack())
        }
        return arr
    }

    private fun decodeArray(length: Long): List<Any?> = decodeArray(length.toInt())

    private fun decodeList(): List<Any?> {
        val listLen = r32().toInt()
        val arr = decodeArray(listLen)
        val listTail = r8()
        if (listTail != NIL_EXT) {
            throw EtfException("List missing tail marker")
        }
        return arr
    }

    private fun decodeMap(): LinkedHashMap<Any?, Any?> {
        val mapLen = r32()
        val obj = LinkedHashMap<Any?, Any?>()
        for (i in 0 until mapLen) {
            val key = unpack()
            val mapVal = unpack()
            obj[key] = mapVal
        }
        return obj
    }

    private fun decodeStringAsList(): List<Int> {
        val strLen = r16()
        if (pos + strLen > sz) {
            throw EtfException("Read past end of buffer")
        }
        val arr = ArrayList<Int>(strLen)
        for (i in 0 until strLen) {
            arr.add(r8())
        }
        return arr
    }

    private fun decodeBinaryAsString(): String {
        val binLen = r32().toInt()
        val binBytes = readBytes(binLen)
        return String(binBytes, Charsets.UTF_8)
    }

    // -- misc Erlang types -----------------------------------------------------
    private fun decodeReference(): Map<String, Any?> {
        val node = unpack()
        val id = listOf(r32())
        val creation = r8()
        return linkedMapOf("node" to node, "id" to id, "creation" to creation)
    }

    private fun decodeNewReference(): Map<String, Any?> {
        val refLen = r16()
        val node = unpack()
        val creation = r8()
        val ids = ArrayList<Long>(refLen)
        for (i in 0 until refLen) {
            ids.add(r32())
        }
        return linkedMapOf("node" to node, "creation" to creation, "id" to ids)
    }

    private fun decodePort(): Map<String, Any?> {
        val node = unpack()
        val id = r32()
        val creation = r8()
        return linkedMapOf("node" to node, "id" to id, "creation" to creation)
    }

    private fun decodePid(): Map<String, Any?> {
        val node = unpack()
        val id = r32()
        val serial = r32()
        val creation = r8()
        return linkedMapOf("node" to node, "id" to id, "serial" to serial, "creation" to creation)
    }

    private fun decodeExport(): Map<String, Any?> {
        val mod = unpack()
        val fun_ = unpack()
        val arity = unpack()
        return linkedMapOf("mod" to mod, "fun" to fun_, "arity" to arity)
    }

    private fun decodeCompressed(): Nothing {
        // Compressed ETF terms are rare on the Discord gateway; unsupported here too
        // (kept as a hard error, matching the original's stance).
        r32() // u_size, discarded
        throw EtfException("Compressed ETF terms not supported (not used by Discord gateway)")
    }

    companion object {
        /** Convenience one-shot decode. */
        fun unpack(bytes: ByteArray): Any? = EtfDecoder(bytes).unpack()
    }
}