package dev.fumulabo.har

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.Reader
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

data class NameValue(val name: String, val value: String)

data class HarTimings(
    val blocked: Double, val dns: Double, val connect: Double, val ssl: Double,
    val send: Double, val wait: Double, val receive: Double,
)

data class HarEntry(
    val index: Int,
    val startedMillis: Long,
    val timeMs: Double,
    val method: String,
    val url: String,
    val httpVersion: String,
    val status: Int,
    val statusText: String,
    val mimeType: String,
    val bodySize: Long,
    val requestHeaders: List<NameValue>,
    val responseHeaders: List<NameValue>,
    val query: List<NameValue>,
    val requestBody: String?,
    val responseBody: String?,
    val responseEncoding: String?,
    val timings: HarTimings,
) {
    val shortType: String
        get() = mimeType.substringBefore(';').substringAfter('/').ifBlank { "-" }
}

data class HarFile(val creator: String, val entries: List<HarEntry>) {
    val startMillis: Long = entries.minOfOrNull { it.startedMillis } ?: 0L
    val endMillis: Long = entries.maxOfOrNull { it.startedMillis + it.timeMs.toLong() } ?: 0L
}

object HarParser {
    fun parse(reader: Reader): HarFile {
        val root = JsonParser.parseReader(reader).asJsonObject
        val log = root.obj("log") ?: throw IllegalArgumentException("Not a HAR file: missing \"log\"")
        val creator = log.obj("creator")?.let { "${it.str("name")} ${it.str("version")}".trim() } ?: ""
        val entries = log.arr("entries").mapIndexedNotNull { i, e ->
            runCatching { entry(i + 1, e.asJsonObject) }.getOrNull()
        }
        return HarFile(creator, entries)
    }

    private fun entry(index: Int, e: JsonObject): HarEntry {
        val req = e.obj("request") ?: JsonObject()
        val res = e.obj("response") ?: JsonObject()
        val content = res.obj("content") ?: JsonObject()
        val t = e.obj("timings") ?: JsonObject()
        val size = content.num("size").toLong().takeIf { it > 0 }
            ?: res.num("bodySize").toLong().coerceAtLeast(0)
        return HarEntry(
            index = index,
            startedMillis = parseTime(e.str("startedDateTime")),
            timeMs = e.num("time").coerceAtLeast(0.0),
            method = req.str("method"),
            url = req.str("url"),
            httpVersion = req.str("httpVersion"),
            status = res.num("status").toInt(),
            statusText = res.str("statusText"),
            mimeType = content.str("mimeType"),
            bodySize = size,
            requestHeaders = pairs(req.arr("headers")),
            responseHeaders = pairs(res.arr("headers")),
            query = pairs(req.arr("queryString")),
            requestBody = req.obj("postData")?.str("text")?.takeIf { it.isNotEmpty() },
            responseBody = content.str("text").takeIf { it.isNotEmpty() },
            responseEncoding = content.str("encoding").takeIf { it.isNotEmpty() },
            timings = HarTimings(
                t.ms("blocked"), t.ms("dns"), t.ms("connect"), t.ms("ssl"),
                t.ms("send"), t.ms("wait"), t.ms("receive"),
            ),
        )
    }

    private fun parseTime(s: String): Long = try {
        OffsetDateTime.parse(s).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        0L
    }

    private fun pairs(a: JsonArray): List<NameValue> = a.mapNotNull {
        val o = it as? JsonObject ?: return@mapNotNull null
        NameValue(o.str("name"), o.str("value"))
    }

    private fun JsonObject.prim(k: String): JsonElement? = get(k)?.takeIf { it.isJsonPrimitive }
    private fun JsonObject.obj(k: String): JsonObject? = get(k) as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray = get(k) as? JsonArray ?: JsonArray()
    private fun JsonObject.str(k: String): String = prim(k)?.asString ?: ""
    private fun JsonObject.num(k: String): Double = runCatching { prim(k)?.asDouble }.getOrNull() ?: 0.0
    /** HAR uses -1 for "does not apply". */
    private fun JsonObject.ms(k: String): Double = num(k).coerceAtLeast(0.0)
}

object Curl {
    private val skipped = setOf("content-length", "host", "connection")

    fun of(e: HarEntry): String = buildString {
        append("curl ").append(q(e.url))
        if (e.method != "GET") append(" \\\n  -X ").append(e.method)
        for (h in e.requestHeaders) {
            if (h.name.startsWith(":") || h.name.lowercase() in skipped) continue
            append(" \\\n  -H ").append(q("${h.name}: ${h.value}"))
        }
        e.requestBody?.let { append(" \\\n  --data-raw ").append(q(it)) }
    }

    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
