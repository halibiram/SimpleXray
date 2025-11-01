package com.simplexray.an.common.configFormat

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Before
import org.junit.Test

class TrojanLinkConverterTest {

    private lateinit var converter: TrojanLinkConverter
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        converter = TrojanLinkConverter(defaultSocksPort = 10808)
        mockContext = mockk(relaxed = true) { every { applicationContext } returns this }
    }

    @Test
    fun `detect should return true for trojan links`() {
        assertThat(converter.detect("trojan://pass@host:443")).isTrue()
    }

    @Test
    fun `convert should parse trojan ws with tls and sni`() {
        val link = "trojan://password123@example.com:443?type=ws&security=tls&host=cdn.example.com&path=%2Fws&sni=sni.example.com#MyTrojan"
        val result = converter.convert(mockContext, link)
        assertThat(result.isSuccess).isTrue()
        val (name, content) = result.getOrNull()!!
        assertThat(name).isEqualTo("MyTrojan")

        val cfg = JSONObject(content)
        val outbound = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertThat(outbound.getString("protocol")).isEqualTo("trojan")

        val servers = outbound.getJSONObject("settings").getJSONArray("servers")
        val server = servers.getJSONObject(0)
        assertThat(server.getString("address")).isEqualTo("example.com")
        assertThat(server.getInt("port")).isEqualTo(443)
        assertThat(server.getString("password")).isEqualTo("password123")

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

