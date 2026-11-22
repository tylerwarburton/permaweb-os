package org.permaweb.andee

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.json.JSONTokener

internal object NodeMetadataHttp {
    private const val MAX_BYTES = 512 * 1024
    private const val ADDRESS_MAX_BYTES = 1024

    /** Body of a 200 response no larger than [max] bytes, or null. */
    private fun get(url: String, max: Int, headers: Map<String, String>): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3000
            connection.readTimeout = 8000
            headers.forEach(connection::setRequestProperty)
            if (connection.responseCode != 200) return null
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= max) {
                    val count = input.read(buffer, 0, minOf(buffer.size, max + 1 - output.size()))
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > max) null else bytes
        } catch (_: Exception) { null }
        finally { connection?.disconnect() }
    }

    /** meta@1.0 info/address: a bare address, or JSON wrapping it as a string or `body`. */
    fun address(url: String): String? {
        val text = get(url, ADDRESS_MAX_BYTES, emptyMap())?.toString(Charsets.UTF_8)?.trim() ?: return null
        return try {
            when (val value = JSONTokener(text).nextValue()) {
                is String -> value
                is JSONObject -> value.optString("address", "").ifEmpty { value.optString("body", "") }
                else -> null
            }
        } catch (_: Exception) { text }
    }

    fun read(url: String): NodeMetadata? {
        val bytes = get(url, MAX_BYTES, mapOf(
            "accept" to "application/json",
            "require-codec" to "application/json",
            "accept-bundle" to "true",
        )) ?: return null
        return try {
            val response = JSONObject(String(bytes, Charsets.UTF_8))
            val data = when (val body = response.opt("body")) {
                is JSONObject -> if (response.has("address")) response else body
                is String -> if (response.has("address")) response else JSONObject(body)
                else -> response
            }
            val hooks = data.optJSONObject("on")?.optJSONArray("start")
            val tunnel = (0 until (hooks?.length() ?: 0)).asSequence()
                .mapNotNull { hooks?.optJSONObject(it) }
                .firstOrNull {
                    it.optString("device") == "tunnel@1.0" &&
                        it.optString("path") == "connect" && it.optInt("workers", 1) > 0
                }
            NodeMetadata(
                data.optString("address", ""),
                if (data.has("location-url")) data.optString("location-url", "") else null,
                data.optString("committer", ""),
                tunnel?.optString("peer", ""),
            )
        } catch (_: Exception) { null }
    }
}
