package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class ShadowsocksLinkConverter(private val defaultSocksPort: Int = -1) : ConfigFormatConverter {
    override fun detect(content: String): Boolean = content.startsWith("ss://", ignoreCase = true)

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val (spec, name, query) = splitSpecNameQuery(content.removePrefix("ss://"))
            val decodedSpec = if (spec.contains("@")) spec else decodeBase64Flexible(spec)

            val beforeQuery = decodedSpec.substringBefore('?')
            val userHost = beforeQuery.split("@", limit = 2)
            require(userHost.size == 2) { "Invalid shadowsocks spec" }
            val methodPass = userHost[0]
            val hostPort = userHost[1]

            val method = methodPass.substringBefore(":")
            val password = methodPass.substringAfter(":")
            val address = hostPort.substringBefore(":")
            val port = hostPort.substringAfter(":").toInt()

            val finalName = if (name.isNullOrBlank()) "imported_ss_" + System.currentTimeMillis() else name

            val socksPort = if (defaultSocksPort > 0) defaultSocksPort else runCatching { Preferences(context).socksPort }.getOrElse { 10808 }

            val config = JSONObject().apply {
                put("log", JSONObject().apply { put("loglevel", "warning") })
                put("inbounds", JSONArray().apply {
                    put(JSONObject().apply {
                        put("port", socksPort)
                        put("listen", "127.0.0.1")
                        put("protocol", "socks")
                        put("settings", JSONObject().apply { put("udp", true) })
                    })
                })
                put("outbounds", JSONArray().apply {
                    put(JSONObject().apply {
                        put("protocol", "shadowsocks")
                        put("settings", JSONObject().apply {
                            put("servers", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("address", address)
                                    put("port", port)
                                    put("method", method)
                                    put("password", password)
                                })
                            })
                        })
                    })
                })
            }

            Result.success(finalName to config.toString(2))
        } catch (e: Throwable) {
            try { e.printStackTrace() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    private fun splitSpecNameQuery(raw: String): Triple<String, String?, String?> {
        val name = raw.substringAfter('#', "").takeIf { it.isNotEmpty() }
        val beforeName = raw.substringBefore('#')
        val spec = beforeName.substringBefore('?')
        val query = beforeName.substringAfter('?', "").takeIf { it.isNotEmpty() }
        return Triple(spec, name, query)
    }

    private fun decodeBase64Flexible(s: String): String {
        fun withPadding(x: String): String { val r = x.length % 4; return if (r == 0) x else x + "=".repeat(4 - r) }
        return try { String(Base64.getUrlDecoder().decode(withPadding(s)), Charsets.UTF_8) } catch (_: IllegalArgumentException) { String(Base64.getDecoder().decode(withPadding(s)), Charsets.UTF_8) }
    }
}

