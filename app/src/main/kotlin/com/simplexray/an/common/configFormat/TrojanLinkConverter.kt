package com.simplexray.an.common.configFormat

import android.content.Context
import com.simplexray.an.prefs.Preferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

class TrojanLinkConverter(private val defaultSocksPort: Int = -1) : ConfigFormatConverter {
    override fun detect(content: String): Boolean = content.startsWith("trojan://", ignoreCase = true)

    override fun convert(context: Context, content: String): Result<DetectedConfig> {
        return try {
            val uri = URI(content)

            val name = uri.fragment?.takeIf { it.isNotBlank() } ?: ("imported_trojan_" + System.currentTimeMillis())
            val address = uri.host ?: throw IllegalArgumentException("Missing host")
            val port = if (uri.port == -1) 443 else uri.port
            val password = uri.userInfo ?: throw IllegalArgumentException("Missing password")

            val params = parseQuery(uri.rawQuery)
            val net = params["type"]?.lowercase() ?: params["net"]?.lowercase() ?: "tcp"
            val tlsEnabled = (params["security"]?.lowercase() ?: "tls") == "tls"
            val sni = params["sni"] ?: params["peer"]
            val host = params["host"]
            val path = params["path"]
            val alpn = params["alpn"]
            val fp = params["fp"]

            val streamSettings = buildStreamSettings(
                network = net,
                tlsEnabled = tlsEnabled,
                sni = sni,
                host = host,
                path = path,
                alpn = alpn,
                fingerprint = fp,
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
                        put("protocol", "trojan")
                        put("settings", JSONObject().apply {
                            put("servers", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("address", address)
                                    put("port", port)
                                    put("password", password)
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

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { kv ->
            if (kv.isBlank()) return@mapNotNull null
            val i = kv.indexOf('=')
            val k = if (i >= 0) kv.substring(0, i) else kv
            if (k.isBlank()) return@mapNotNull null
            val v = if (i >= 0) kv.substring(i + 1) else ""
            k.lowercase() to decode(v)
        }.toMap()
    }

    private fun decode(s: String): String = try {
        java.net.URLDecoder.decode(s, Charsets.UTF_8)
    } catch (_: Throwable) { s }

    private fun buildStreamSettings(
        network: String,
        tlsEnabled: Boolean,
        sni: String?,
        host: String?,
        path: String?,
        alpn: String?,
        fingerprint: String?,
    ): JSONObject {
        val stream = JSONObject()
        val mapped = if (network == "h2") "http" else network
        stream.put("network", mapped)
        if (tlsEnabled) {
            stream.put("security", "tls")
            stream.put("tlsSettings", JSONObject().apply {
                sni?.let { put("serverName", it) }
                alpn?.let { put("alpn", JSONArray().apply { it.split(',').forEach { a -> put(a.trim()) } }) }
                fingerprint?.let { put("fingerprint", it) }
            })
        }
        when (mapped) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                path?.let { put("path", it) }
                if (!host.isNullOrBlank()) put("headers", JSONObject().apply { put("Host", host) })
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                val service = path?.trim('/', ' ')
                service?.let { put("serviceName", it) }
                put("multiMode", true)
            })
            "http" -> stream.put("httpSettings", JSONObject().apply {
                if (!host.isNullOrBlank()) put("host", JSONArray().apply { put(host) })
                path?.let { put("path", it) }
            })
            "quic" -> stream.put("quicSettings", JSONObject())
        }
        return stream
    }
}

