package com.simplexray.an.common.configFormat

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import java.util.Base64

class VmessLinkConverterTest {

    private lateinit var converter: VmessLinkConverter
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        converter = VmessLinkConverter(defaultSocksPort = 10808)
        mockContext = mockk(relaxed = true) {
            every { applicationContext } returns this
        }
    }

    @Test
    fun `detect should return true for vmess links`() {
        assertThat(converter.detect("vmess://abc")).isTrue()
    }

    @Test
    fun `convert should parse vmess ws with tls and sni`() {
        val json = JSONObject().apply {
            put("v", "2")
            put("ps", "MyVMess")
            put("add", "example.com")
            put("port", "443")
            put("id", "00000000-0000-0000-0000-000000000000")
            put("net", "ws")
            put("type", "none")
            put("host", "cdn.example.com")
            put("path", "/ws")
            put("tls", "tls")
            put("sni", "sni.example.com")
            put("alpn", "h2,h3")
            put("fp", "chrome")
        }.toString()
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray())
        val link = "vmess://$b64"

        val result = converter.convert(mockContext, link)
        assertThat(result.isSuccess).isTrue()
        val (_, content) = result.getOrNull()!!
        val cfg = JSONObject(content)

        val outbound = cfg.getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        assertThat(stream.getString("network")).isEqualTo("ws")

        val ws = stream.getJSONObject("wsSettings")
        assertThat(ws.getString("path")).isEqualTo("/ws")
        val headers = ws.getJSONObject("headers")
        assertThat(headers.getString("Host")).isEqualTo("cdn.example.com")

        assertThat(stream.getString("security")).isEqualTo("tls")
        val tls = stream.getJSONObject("tlsSettings")
        assertThat(tls.getString("serverName")).isEqualTo("sni.example.com")
    }
}

