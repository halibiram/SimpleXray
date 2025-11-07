package com.simplexray.an.common

import android.util.Log
import com.simplexray.an.prefs.Preferences
import org.json.JSONException
import org.json.JSONObject

/**
 * Utility functions for configuration file manipulation
 * TODO: Add config schema validation
 * TODO: Implement config versioning support
 * TODO: Add config sanitization to remove sensitive data
 * TODO: Consider adding config diff functionality
 */
object ConfigUtils {
    private const val TAG = "ConfigUtils"

    // Add size limit to prevent memory issues
    @Throws(JSONException::class)
    fun formatConfigContent(content: String): String {
        // SEC: Limit JSON content size to prevent memory issues (10MB limit)
        if (content.length > 10 * 1024 * 1024) {
            throw JSONException("JSON content too large: ${content.length} bytes (max 10MB)")
        }
        val jsonObject = JSONObject(content)
        (jsonObject["log"] as? JSONObject)?.apply {
            if (has("access") && optString("access") != "none") {
                remove("access")
                Log.d(TAG, "Removed log.access")
            }
            if (has("error") && optString("error") != "none") {
                remove("error")
                Log.d(TAG, "Removed log.error")
            }
        }
        var formattedContent = jsonObject.toString(2)
        formattedContent = formattedContent.replace("\\/", "/")
        return formattedContent
    }

    // Add size limit and basic validation
    @Throws(JSONException::class)
    fun injectStatsService(prefs: Preferences, configContent: String): String {
        // SEC: Limit config content size to prevent memory issues (10MB limit)
        if (configContent.length > 10 * 1024 * 1024) {
            throw JSONException("Config content too large: ${configContent.length} bytes (max 10MB)")
        }
        // Validate that configContent is not empty
        if (configContent.isBlank()) {
            throw JSONException("Config content is empty")
        }
        val jsonObject = JSONObject(configContent)

        // 1. API section - enable StatsService
        val apiObject = JSONObject()
        apiObject.put("tag", "api")
        apiObject.put("listen", "127.0.0.1:${prefs.apiPort}")
        val servicesArray = org.json.JSONArray()
        servicesArray.put("StatsService")
        servicesArray.put("HandlerService")
        servicesArray.put("LoggerService")
        apiObject.put("services", servicesArray)
        jsonObject.put("api", apiObject)
        
        // 2. Stats section - enable stats collection
        jsonObject.put("stats", JSONObject())

        // 3. Policy section - enable stats tracking
        val policyObject = if (jsonObject.has("policy")) {
            jsonObject.getJSONObject("policy")
        } else {
            JSONObject()
        }

        // System-level stats
        val systemObject = if (policyObject.has("system")) {
            policyObject.getJSONObject("system")
        } else {
            JSONObject()
        }
        systemObject.put("statsOutboundUplink", true)
        systemObject.put("statsOutboundDownlink", true)
        systemObject.put("statsInboundUplink", true)
        systemObject.put("statsInboundDownlink", true)
        policyObject.put("system", systemObject)

        // User-level stats (level 0)
        val levelsObject = if (policyObject.has("levels")) {
            policyObject.getJSONObject("levels")
        } else {
            JSONObject()
        }
        val level0Object = if (levelsObject.has("0")) {
            levelsObject.getJSONObject("0")
        } else {
            JSONObject()
        }
        level0Object.put("statsUserUplink", true)
        level0Object.put("statsUserDownlink", true)
        levelsObject.put("0", level0Object)
        policyObject.put("levels", levelsObject)

        jsonObject.put("policy", policyObject)

        Log.d(TAG, "Stats service injected successfully with API port: ${prefs.apiPort}")
        return jsonObject.toString(2)
    }

    // Add size limit to prevent memory issues with large JSON files
    fun extractPortsFromJson(jsonContent: String): Set<Int> {
        // SEC: Limit JSON content size to prevent memory issues (10MB limit)
        if (jsonContent.length > 10 * 1024 * 1024) {
            Log.e(TAG, "JSON content too large for port extraction: ${jsonContent.length} bytes")
            return emptySet()
        }
        val ports = mutableSetOf<Int>()
        try {
            val jsonObject = JSONObject(jsonContent)
            extractPortsRecursive(jsonObject, ports)
        } catch (e: JSONException) {
            // BUG: Exception swallowed - returns empty set, caller may not know about error
            // Propagate error information via logging
            Log.e(TAG, "Error parsing JSON for port extraction: ${e.message}", e)
            // Return empty set but log the error for debugging
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during port extraction: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        Log.d(TAG, "Extracted ports: $ports")
        return ports
    }

    // Add depth limit to prevent stack overflow on deeply nested JSON
    private fun extractPortsRecursive(jsonObject: JSONObject, ports: MutableSet<Int>, depth: Int = 0) {
        // Prevent stack overflow by limiting recursion depth
        if (depth > 50) {
            Log.w(TAG, "Maximum recursion depth reached in port extraction")
            return
        }
        for (key in jsonObject.keys()) {
            when (val value = jsonObject.get(key)) {
                is Int -> {
                    // Validate port is within valid range
                    if (value in 1..65535) {
                        ports.add(value)
                    } else {
                        Log.w(TAG, "Invalid port number found: $value (must be 1-65535)")
                    }
                }

                is JSONObject -> {
                    extractPortsRecursive(value, ports, depth + 1)
                }

                is org.json.JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        if (item is JSONObject) {
                            extractPortsRecursive(item, ports, depth + 1)
                        }
                    }
                }
            }
        }
    }
}

