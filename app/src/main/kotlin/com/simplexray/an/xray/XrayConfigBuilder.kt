package com.simplexray.an.xray

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

object XrayConfigBuilder {
    private val gson = Gson()

    fun defaultConfig(apiHost: String, apiPort: Int): JsonObject {
        val root = JsonObject()
        
        // Logging - Use debug level with access/error log paths
        root.add("log", JsonObject().apply {
            addProperty("loglevel", "debug")
            addProperty("access", "/data/data/com.simplexray.an/files/xray_access.log")
            addProperty("error", "/data/data/com.simplexray.an/files/xray_error.log")
        })
        
        // Enable api service with StatsService
        val api = JsonObject().apply {
            addProperty("tag", "api")
            add("services", gson.toJsonTree(listOf("StatsService")))
        }
        root.add("api", api)
        // Stats enabled
        root.add("stats", JsonObject())
        // Policy to collect stats
        val system = JsonObject().apply {
            addProperty("statsInboundUplink", true)
            addProperty("statsInboundDownlink", true)
            addProperty("statsOutboundUplink", true)
            addProperty("statsOutboundDownlink", true)
        }
        val levels = JsonObject().apply {
            add("0", JsonObject().apply {
                addProperty("statsUserUplink", true)
                addProperty("statsUserDownlink", true)
            })
        }
        val policy = JsonObject().apply {
            add("system", system)
            add("levels", levels)
        }
        root.add("policy", policy)

        // Add inbounds/outbounds placeholders (user should merge their own)
        root.add("inbounds", gson.toJsonTree(emptyList<Any>()))
        root.add("outbounds", gson.toJsonTree(emptyList<Any>()))

        // Optional: transport/grpc/ws etc. left to user config
        return root
    }

    fun writeConfig(context: Context, config: JsonObject, filename: String = "xray.json"): File {
        // SEC: Validate filename to prevent path traversal
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            throw SecurityException("Invalid filename: contains path traversal characters")
        }
        // SEC: Validate filename length
        if (filename.length > 255) {
            throw IllegalArgumentException("Filename too long: ${filename.length} characters")
        }
        val file = File(context.filesDir, filename)
        // SEC: Validate config size before writing (10MB max)
        val configJson = gson.toJson(config)
        if (configJson.length > 10 * 1024 * 1024) {
            throw IllegalArgumentException("Config too large: ${configJson.length} bytes (max 10MB)")
        }
        // PERF: Writing entire config to disk - consider atomic write with backup
        // Implement atomic write with backup
        val backupFile = File(context.filesDir, "$filename.backup")
        try {
            // Write to backup first
            backupFile.writeText(configJson)
            // Then move to actual file (atomic on most filesystems)
            if (file.exists()) {
                file.delete()
            }
            backupFile.renameTo(file)
        } catch (e: Exception) {
            // Cleanup backup on failure
            backupFile.delete()
            throw e
        }
        return file
    }
}

