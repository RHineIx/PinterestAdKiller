package com.my.pinteresthook

import org.json.JSONArray
import org.json.JSONObject

object AdSanitizer {

    // Check if the JSON string contains ad-related keywords
    fun shouldSanitize(jsonString: String?): Boolean {
        if (jsonString.isNullOrEmpty()) return false
        return jsonString.contains("is_promoted") || jsonString.contains("is_third_party_ad")
    }

    // Parse JSON and remove ad items from the data array
    fun cleanFeed(jsonResponse: String): String {
        try {
            val root = JSONObject(jsonResponse)

            // We only care about the "data" field
            if (!root.has("data")) return jsonResponse

            val dataNode = root.get("data")

            // Process if "data" is an array (list of pins)
            if (dataNode is JSONArray) {
                val cleanArray = JSONArray()
                
                for (i in 0 until dataNode.length()) {
                    val item = dataNode.optJSONObject(i) ?: continue
                    
                    // Check ad flags
                    val isPromoted = item.optBoolean("is_promoted", false)
                    val isThirdParty = item.optBoolean("is_third_party_ad", false)

                    // Only add non-ad items to the new list
                    if (!isPromoted && !isThirdParty) {
                        cleanArray.put(item)
                    }
                }
                // Replace original data with clean data
                root.put("data", cleanArray)
            }
            
            return root.toString()

        } catch (e: Exception) {
            // Return original string if parsing fails
            return jsonResponse
        }
    }
}
