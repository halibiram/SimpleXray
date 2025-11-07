package com.simplexray.an.common.configFormat

import android.content.Context

interface ConfigFormatConverter {
    fun detect(content: String): Boolean
    fun convert(context: Context, content: String): Result<DetectedConfig>

    companion object {
        val knownImplementations = listOf(
            SimpleXrayFormatConverter(),
            XuiHysteria2Converter(), // x-ui Hysteria2 format
            XuiVlessConverter(), // x-ui VLESS format (check first for x-ui specific params)
            VlessLinkConverter(), // Generic VLESS link format
        )

        fun convertOrNull(context: Context, content: String): Result<DetectedConfig>? {
            for (implementation in knownImplementations) {
                if (implementation.detect(content)) return implementation.convert(context, content)
            }
            return null
        }

        fun convert(context: Context, content: String): Result<DetectedConfig> {
            return convertOrNull(context, content) ?: run {
                return Result.success(Pair("imported_share_" + System.currentTimeMillis(), content))
            }
        }
    }
}