package com.umbra.hooks.utils

import org.json.JSONArray
import org.json.JSONObject

object AdSanitizer {

    fun shouldSanitize(jsonString: String?): Boolean {
        if (jsonString.isNullOrEmpty()) return false
        return jsonString.contains("is_promoted") || jsonString.contains("is_third_party_ad")
    }

    fun cleanFeed(jsonResponse: String): String {
        try {
            val root = JSONObject(jsonResponse)
            if (!root.has("data")) return jsonResponse

            val dataNode = root.get("data")

            if (dataNode is JSONArray) {
                val cleanArray = JSONArray()
                for (i in 0 until dataNode.length()) {
                    val item = dataNode.optJSONObject(i) ?: continue
                    val isPromoted = item.optBoolean("is_promoted", false)
                    val isThirdParty = item.optBoolean("is_third_party_ad", false)

                    if (!isPromoted && !isThirdParty) {
                        cleanArray.put(item)
                    }
                }
                root.put("data", cleanArray)
            }
            return root.toString()
        } catch (e: Exception) {
            return jsonResponse
        }
    }
}
