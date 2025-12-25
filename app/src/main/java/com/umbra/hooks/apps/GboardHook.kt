package com.umbra.hooks.apps

import android.app.Application
import android.content.Context
import android.net.Uri
import com.umbra.hooks.utils.Constants
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.util.concurrent.TimeUnit

class GboardHook {

    private val packageName = "com.umbra.hooks"
    
    // Preferences Helpers
    private fun getLimitValue(): Int = manualReadInt(Constants.PREF_GBOARD_CLIPBOARD_LIMIT, 10)
    private fun getDaysValue(): Long = manualReadLong(Constants.PREF_GBOARD_HISTORY_RETENTION, Constants.DEFAULT_RETENTION_DAYS)

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("[UMBRA] GboardHook init for ${lpparam.processName}")

        try {
            try { System.loadLibrary("dexkit") } catch (_: Throwable) {}

            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as Context
                        val classLoader = context.classLoader
                        
                        // NOTE: We pass classLoader and 'true' (HotFix mode) to DexKit
                        // This matches the original chenyue404 implementation exactly.
                        
                        applyClipboardHooks(classLoader)
                        applyConfigHooks(classLoader)
                        applyAdapterHooks(classLoader)
                    }
                }
            )

        } catch (t: Throwable) {
            XposedBridge.log("[UMBRA] Error initializing Gboard hook: $t")
        }
    }

    // --- 1. Database Hook (Standard Xposed) ---
    private fun applyClipboardHooks(classLoader: ClassLoader) {
        val className = "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider"
        try {
            XposedHelpers.findAndHookMethod(
                className, classLoader, "query",
                Uri::class.java, Array<String>::class.java, String::class.java, Array<String>::class.java, String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val limit = getLimitValue()
                        val days = getDaysValue()
                        val selection = param.args[2] as? String ?: ""
                        val sortOrder = param.args[4] as? String

                        if (selection.contains("timestamp >= ?")) {
                            val args = param.args[3] as? Array<String>
                            if (args != null && args.isNotEmpty()) {
                                var placeholderIndex = 0
                                for (i in 0 until selection.indexOf("timestamp >= ?")) {
                                    if (selection[i] == '?') placeholderIndex++
                                }
                                if (placeholderIndex < args.size) {
                                    val retentionMillis = TimeUnit.DAYS.toMillis(days)
                                    args[placeholderIndex] = (System.currentTimeMillis() - retentionMillis).toString()
                                    param.args[3] = args
                                }
                            }
                        }

                        if (sortOrder != null) {
                            if (sortOrder.contains("limit", ignoreCase = true)) {
                                val newSort = sortOrder.replace(Regex("(?i)limit\\s+\\d+"), "limit $limit")
                                if (newSort == sortOrder && !sortOrder.contains("limit $limit")) {
                                     param.args[4] = "$sortOrder LIMIT $limit"
                                } else {
                                     param.args[4] = newSort
                                }
                                XposedBridge.log("[UMBRA] DB Limit Patched: $limit")
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[UMBRA] Failed to hook ClipboardContentProvider: $t")
        }
    }

    // --- 2. Config Hook (Using DexKit to find flags) ---
    private fun applyConfigHooks(classLoader: ClassLoader) {
        // Use create(classLoader, true) as in original project
        DexKitBridge.create(classLoader, true).use { bridge ->
            val configMethod = bridge.findMethod {
                matcher {
                    usingStrings("Invalid flag: ")
                    returnType("java.lang.Object")
                }
            }.firstOrNull()?.toDexMethod()

            if (configMethod != null) {
                try {
                    XposedHelpers.findAndHookMethod(
                        configMethod.className, classLoader, configMethod.name,
                        object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val flagName = XposedHelpers.getObjectField(param.thisObject, "a").toString()
                                if (flagName == "enable_clipboard_entity_extraction" || 
                                    flagName == "enable_clipboard_query_refactoring") {
                                    param.result = false
                                    XposedBridge.log("[UMBRA] Flag Disabled: $flagName")
                                }
                            }
                        }
                    )
                } catch (t: Throwable) { }
            }
        }
    }

    // --- 3. UI Adapter Hook (EXACT ORIGINAL LOGIC) ---
    private fun applyAdapterHooks(classLoader: ClassLoader) {
        DexKitBridge.create(classLoader, true).use { bridge ->
            
            // 1. Find Adapter Class
            val adapterClassData = bridge.findClass {
                matcher {
                    usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter")
                }
            }.firstOrNull()

            if (adapterClassData != null) {
                val className = adapterClassData.name // e.g. "Lcom/abc/d;"
                XposedBridge.log("[UMBRA] Adapter Found: $className")

                // 2. Find Limiter Method (Contains number 5 or 10)
                // We convert DexClassData to simple string to use with Xposed later if needed,
                // but DexKit needs the Bridge to find methods inside it.
                
                var targetMethod = adapterClassData.findMethod {
                    matcher { usingNumbers(10) }
                }.firstOrNull()

                if (targetMethod == null) {
                    XposedBridge.log("[UMBRA] '10' not found, trying '5'...")
                    targetMethod = adapterClassData.findMethod {
                        matcher { usingNumbers(5) }
                    }.firstOrNull()
                }

                if (targetMethod != null) {
                    val methodInfo = targetMethod.toDexMethod()
                    // Fix class name for Xposed (remove L and ;)
                    val cleanClassName = if (methodInfo.className.startsWith("L") && methodInfo.className.endsWith(";")) {
                        methodInfo.className.substring(1, methodInfo.className.length - 1).replace('/', '.')
                    } else {
                        methodInfo.className
                    }

                    XposedBridge.log("[UMBRA] Hooking Method: ${methodInfo.name} in $cleanClassName")

                    try {
                        XposedHelpers.findAndHookMethod(
                            cleanClassName,
                            classLoader,
                            methodInfo.name,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    // FORCE NULL RESULT - This disables the limit check logic
                                    param.result = null
                                    XposedBridge.log("[UMBRA] UI Limit Bypassed!")
                                }
                            }
                        )
                    } catch (e: Exception) {
                        XposedBridge.log("[UMBRA] Hook Failed: $e")
                    }
                } else {
                    XposedBridge.log("[UMBRA] CRITICAL: No limiter method found (checked 5 & 10)")
                }

            } else {
                XposedBridge.log("[UMBRA] CRITICAL: Adapter Class NOT found")
            }
        }
    }

    // --- XML Utils ---
    private fun manualReadInt(key: String, default: Int): Int {
        val content = readPrefsFile() ?: return default
        val regex1 = Regex("name=\"$key\"[^>]*value=\"(\\d+)\"")
        val regex2 = Regex("value=\"(\\d+)\"[^>]*name=\"$key\"")
        val match = regex1.find(content) ?: regex2.find(content)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: default
    }

    private fun manualReadLong(key: String, default: Long): Long {
        val content = readPrefsFile() ?: return default
        val regex1 = Regex("name=\"$key\"[^>]*value=\"(\\d+)\"")
        val regex2 = Regex("value=\"(\\d+)\"[^>]*name=\"$key\"")
        val match = regex1.find(content) ?: regex2.find(content)
        return match?.groupValues?.get(1)?.toLongOrNull() ?: default
    }

    private fun readPrefsFile(): String? {
        val path = "/data/data/$packageName/shared_prefs/${Constants.PREFS_FILE}.xml"
        val file = File(path)
        if (!file.exists() || !file.canRead()) return null
        return try { BufferedReader(FileReader(file)).use { it.readText() } } catch (e: Exception) { null }
    }
}
