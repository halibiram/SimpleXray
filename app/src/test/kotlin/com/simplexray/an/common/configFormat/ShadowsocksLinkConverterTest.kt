package com.simplexray.an.common.configFormat

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import java.util.Base64

class ShadowsocksLinkConverterTest {

    private lateinit var converter: ShadowsocksLinkConverter
    private lateinit var mockContext: Context

    @Before
    fun setup() {
        converter = ShadowsocksLinkConverter(defaultSocksPort = 10808)
        mockContext = mockk(relaxed = true) { every { applicationContext } returns this }
    }

    @Test
    fun `detect should return true for ss links`() {
        assertThat(converter.detect("ss://abcdef")).isTrue()
    }

    @Test
    fun `convert should parse base64 ss link with name`() {
        val spec = "aes-256-gcm:pass123@example.com:8388"
        val b64 = Base64.getEncoder().encodeToString(spec.toByteArray())
        val link = "ss://$b64#MySS"

        val result = converter.convert(mockContext, link)
        assertThat(result.isSuccess).isTrue()
        val (name, content) = result.getOrNull()!!
        assertThat(name).isEqualTo("MySS")

        val cfg = JSONObject(content)
        val outbound = cfg.getJSONArray("outbounds").getJSONObject(0)
        assertThat(outbound.getString("protocol")).isEqualTo("shadowsocks")
        val servers = outbound.getJSONObject("settings").getJSONArray("servers")
        val server = servers.getJSONObject(0)
        assertThat(server.getString("address")).isEqualTo("example.com")
        assertThat(server.getInt("port")).isEqualTo(8388)
        assertThat(server.getString("method")).isEqualTo("aes-256-gcm")
        assertThat(server.getString("password")).isEqualTo("pass123")
    }
}

