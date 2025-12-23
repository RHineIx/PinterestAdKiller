package com.my.pinteresthook

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

class MainHook : IXposedHookLoadPackage {

    // Keywords to identify ad views in the UI layout
    private val adResourceNames = listOf(
        "sba_gma",      // Google Ads
        "native_ad",    // Native Ads
        "promoted",     // Promoted Pins
        "sponsor",      // Sponsored Content
        "ads_container" // Generic Ad Containers
    )

    // To prevent hooking the same class multiple times
    private val hookedClasses = HashSet<String>()

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only target Pinterest
        if (lpparam.packageName != "com.pinterest") return

        // 1. Block ads at the API level (JSON)
        initJsonHook()

        // 2. Hide ads at the UI level (Views)
        initUIHook()
    }

    // =============================================================
    // 1. JSON Hook: Clean data before the app reads it
    // =============================================================
    private fun initJsonHook() {
        try {
            // Hook JSONObject constructor to intercept raw JSON strings
            XposedHelpers.findAndHookConstructor(JSONObject::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val jsonString = param.args[0] as? String
                    
                    // If string contains ads, clean it
                    if (AdSanitizer.shouldSanitize(jsonString)) {
                        param.args[0] = AdSanitizer.cleanFeed(jsonString!!)
                    }
                }
            })
        } catch (_: Throwable) { }
    }

    // =============================================================
    // 2. UI Hook: Hide ad views that slip through
    // =============================================================
    private fun initUIHook() {
        try {
            // Hook LayoutInflater to detect when views are created
            XposedHelpers.findAndHookMethod(LayoutInflater::class.java, "inflate", Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.result as? View ?: return
                    val id = param.args[0] as Int
                    val context = view.context

                    try {
                        // Get resource entry name (ID name)
                        val name = try { 
                            context.resources.getResourceEntryName(id) 
                        } catch (e: Exception) { 
                            return 
                        }

                        // Check if the view ID matches any ad keywords
                        if (adResourceNames.any { name.contains(it, ignoreCase = true) }) {
                            // 1. Hide the view itself
                            nukeView(view)
                            
                            // 2. Hide parent container to remove white spaces
                            val rootGroup = param.args[1] as? ViewGroup
                            if (rootGroup != null) {
                                nukeView(rootGroup)
                                // Force parent to measure as 0x0
                                hookOnMeasure(rootGroup::class.java) 
                            }
                        }

                    } catch (_: Throwable) { }
                }
            })
        } catch (_: Throwable) { }
    }

    // =============================================================
    // Helper Methods
    // =============================================================

    // Set view visibility to GONE and size to 0
    private fun nukeView(view: View) {
        try {
            view.visibility = View.GONE
            val params = view.layoutParams
            if (params != null) {
                params.width = 0
                params.height = 0
                if (params is ViewGroup.MarginLayoutParams) {
                    params.setMargins(0, 0, 0, 0)
                }
                view.layoutParams = params
            }
        } catch (_: Throwable) {}
    }

    // Force a View class to measure itself as 0x0 if hidden
    private fun hookOnMeasure(clazz: Class<*>) {
        if (hookedClasses.contains(clazz.name)) return
        hookedClasses.add(clazz.name)
        
        try {
            XposedHelpers.findAndHookMethod(clazz, "onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as View
                    
                    if (view.visibility == View.GONE) {
                        try { 
                            // Force dimensions to 0
                            XposedHelpers.callMethod(view, "setMeasuredDimension", 0, 0) 
                        } catch (_: Throwable) {}
                        
                        // Skip original method execution
                        param.result = null 
                    }
                }
            })
        } catch (_: Throwable) { }
    }
}
