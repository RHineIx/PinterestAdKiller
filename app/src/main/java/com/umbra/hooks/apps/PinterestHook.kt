package com.umbra.hooks.apps

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.umbra.hooks.utils.AdSanitizer
import com.umbra.hooks.utils.ViewUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject

class PinterestHook {

    private val adResourceNames = listOf(
        "sba_gma",      // Google Ads
        "native_ad",    // Native Ads
        "promoted",     // Promoted Pins
        "sponsor",      // Sponsored Content
        "ads_container" // Generic Ad Containers
    )
    
    private val hookedClasses = HashSet<String>()

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        initJsonHook()
        initUIHook()
    }

    private fun initJsonHook() {
        try {
            XposedHelpers.findAndHookConstructor(JSONObject::class.java, String::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val jsonString = param.args[0] as? String
                    if (AdSanitizer.shouldSanitize(jsonString)) {
                        param.args[0] = AdSanitizer.cleanFeed(jsonString!!)
                    }
                }
            })
        } catch (_: Throwable) { }
    }

    private fun initUIHook() {
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater::class.java, "inflate", Int::class.javaPrimitiveType, ViewGroup::class.java, Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.result as? View ?: return
                    val id = param.args[0] as Int
                    val context = view.context

                    try {
                        val name = try {
                            context.resources.getResourceEntryName(id)
                        } catch (e: Exception) { return }

                        if (adResourceNames.any { name.contains(it, ignoreCase = true) }) {
                            ViewUtils.nukeView(view)
                            
                            val rootGroup = param.args[1] as? ViewGroup
                            if (rootGroup != null) {
                                ViewUtils.nukeView(rootGroup)
                                ViewUtils.hookOnMeasure(rootGroup::class.java, hookedClasses)
                            }
                        }
                    } catch (_: Throwable) { }
                }
            })
        } catch (_: Throwable) { }
    }
}
