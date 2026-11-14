package com.mineygg.questmate.core

import etf.EtfDecoder
import etf.EtfEncoder
import org.json.JSONArray
import org.json.JSONObject

/** Bridge between the app's org.json objects and the etf package (erlangTerm.kt). */
object EtfCodec {
    /** JSON object/array/value -> ETF bytes. Throws if the value can't be packed. */
    fun pack(value: Any?): ByteArray = EtfEncoder.pack(jsonToKotlin(value))

    /** ETF bytes -> JSONObject / JSONArray / value. Throws if the bytes aren't valid ETF. */
    fun unpack(data: ByteArray): Any? = kotlinToJson(EtfDecoder.unpack(data))

    private fun jsonToKotlin(value: Any?): Any? = when (value) {
        is JSONObject -> {
            val map = LinkedHashMap<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = jsonToKotlin(value.get(key))
            }
            map
        }
        is JSONArray -> (0 until value.length()).map { jsonToKotlin(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun kotlinToJson(value: Any?): Any? = when (value) {
        is Map<*, *> -> {
            val json = JSONObject()
            for ((k, v) in value) json.put(k.toString(), kotlinToJson(v))
            json
        }
        is List<*> -> {
            val json = JSONArray()
            for (item in value) json.put(kotlinToJson(item))
            json
        }
        null -> JSONObject.NULL
        else -> value
    }
}
