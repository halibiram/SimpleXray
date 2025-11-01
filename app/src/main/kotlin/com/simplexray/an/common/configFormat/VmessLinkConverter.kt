package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

class VmessLinkConverter(private val defaultSocksPort: Int = -1) : ConfigFormatConverter {
    override fun detect(content: String): Boolean = content.startsWith("vmess://", ignoreCase = true)

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val raw = content.removePrefix("vmess://")
            val decoded = decodeBase64Flexible(raw)
            val obj = JSONObject(decoded)

            val name = obj.optString("ps").ifBlank { "imported_vmess_" + System.currentTimeMillis() }
            val address = obj.getString("add")
            val port = obj.optString("port").ifBlank { obj.optInt("port", 443).toString() }.toInt()
            val id = obj.getString("id")

            val net = obj.optString("net", "tcp").lowercase()
            val type = obj.optString("type", "none").lowercase()
            val host = obj.optString("host").ifBlank { null }
            val path = obj.optString("path").ifBlank { null }
            val tls = obj.optString("tls").lowercase()
            val sni = obj.optString("sni").ifBlank { null }
            val alpn = obj.optString("alpn").ifBlank { null }
            val fp = obj.optString("fp").ifBlank { null }

            val streamSettings = buildStreamSettings(
                network = net,
                tlsEnabled = tls == "tls",
                sni = sni,
                host = host,
                path = path,
                alpn = alpn,
                fingerprint = fp,
                type = type,
            )

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
                        put("protocol", "vmess")
                        put("settings", JSONObject().apply {
                            put("vnext", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("address", address)
                                    put("port", port)
                                    put("users", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("id", id)
                                            put("alterId", 0)
                                            put("security", "auto")
                                        })
                                    })
                                })
                            })
                        })
                        put("streamSettings", streamSettings)
                    })
                })
            }

            Result.success(name to config.toString(2))
        } catch (e: Throwable) {
            try { e.printStackTrace() } catch (_: Exception) {}
            Result.failure(e)
        }
    }

    private fun decodeBase64Flexible(s: String): String {
        // Try URL-safe then standard, fix missing padding
        fun withPadding(x: String): String {
            val rem = x.length % 4
            return if (rem == 0) x else x + "=".repeat(4 - rem)
        }
        return try {
            val b = Base64.getUrlDecoder().decode(withPadding(s))
            String(b, Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            val b = Base64.getDecoder().decode(withPadding(s))
            String(b, Charsets.UTF_8)
        }
    }

    private fun buildStreamSettings(
        network: String,
        tlsEnabled: Boolean,
        sni: String?,
        host: String?,
        path: String?,
        alpn: String?,
        fingerprint: String?,
        type: String?,
    ): JSONObject {
        val stream = JSONObject()
        stream.put("network", when (network) {
            "h2" -> "http" // map h2 to http transport
            else -> network
        })

        if (tlsEnabled) {
            stream.put("security", "tls")
            stream.put("tlsSettings", JSONObject().apply {
                sni?.let { put("serverName", it) }
                alpn?.let { put("alpn", JSONArray().apply { it.split(',').forEach { a -> put(a.trim()) } }) }
                fingerprint?.let { put("fingerprint", it) }
            })
        }

        when (network) {
            "ws" -> {
                stream.put("wsSettings", JSONObject().apply {
                    path?.let { put("path", it) }
                    if (!host.isNullOrBlank()) {
                        put("headers", JSONObject().apply { put("Host", host) })
                    }
                })
            }
            "grpc" -> {
                val service = when {
                    !path.isNullOrBlank() -> path.trim('/',' ') // many links put serviceName into path
                    !host.isNullOrBlank() -> host // fallback
                    else -> null
                }
                stream.put("grpcSettings", JSONObject().apply {
                    service?.let { put("serviceName", it) }
                    put("multiMode", true)
                })
            }
            "http" -> {
                stream.put("httpSettings", JSONObject().apply {
                    if (!host.isNullOrBlank()) {
                        put("host", JSONArray().apply { put(host) })
                    }
                    path?.let { put("path", it) }
                })
            }
            "quic" -> {
                stream.put("quicSettings", JSONObject().apply {
                    type?.let { put("security", it) }
                    // key/header omitted by default
                })
            }
        }

        return stream
    }
}

