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
<<<<<<< HEAD
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
=======
import java.lang.reflect.Modifier

class GboardHook {

    private val packageName = "com.umbra.hooks"
    
    // إعدادات القراءة
    private fun getLimitValue(): Int = manualReadInt(Constants.PREF_GBOARD_CLIPBOARD_LIMIT, 10)
    private fun getDaysValue(): Long = manualReadLong(Constants.PREF_GBOARD_HISTORY_RETENTION, Constants.DEFAULT_RETENTION_DAYS)

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("[UMBRA] Init: ${lpparam.processName}")

        try {
            try { System.loadLibrary("dexkit") } catch (_: Throwable) {}

            XposedHelpers.findAndHookMethod(
                Application::class.java, "attach", Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as Context
                        val classLoader = context.classLoader
                        val apkPath = context.applicationInfo.sourceDir
                        
                        applyClipboardHooks(classLoader) // DB Fix
                        applyConfigHooks(classLoader, apkPath) // Config Fix
                        applyAdapterHooks(classLoader, apkPath) // UI Fix (Blind Hook)
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[UMBRA] Error: $t")
        }
    }

    // 1. DB Hook (يعمل جيداً حسب السجلات السابقة)
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
                                var idx = 0
                                for (i in 0 until selection.indexOf("timestamp >= ?")) { if (selection[i] == '?') idx++ }
                                if (idx < args.size) {
                                    val retention = java.util.concurrent.TimeUnit.DAYS.toMillis(days)
                                    args[idx] = (System.currentTimeMillis() - retention).toString()
                                    param.args[3] = args
                                }
                            }
                        }
                        if (sortOrder != null && sortOrder.contains("limit", ignoreCase = true)) {
                            param.args[4] = sortOrder.replace(Regex("(?i)limit\\s+\\d+"), "limit $limit")
                        }
                    }
                }
            )
        } catch (t: Throwable) { XposedBridge.log("[UMBRA] DB Hook Failed: $t") }
    }

    // 2. Config Hook (مهم جداً لتعطيل القيود الجانبية)
    private fun applyConfigHooks(classLoader: ClassLoader, apkPath: String) {
        DexKitBridge.create(apkPath).use { bridge ->
            val m = bridge.findMethod { matcher { usingStrings("Invalid flag: "); returnType("java.lang.Object") } }.firstOrNull()?.toDexMethod()
            if (m != null) {
                try {
                    XposedHelpers.findAndHookMethod(m.className, classLoader, m.name, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val f = XposedHelpers.getObjectField(param.thisObject, "a").toString()
                            if (f == "enable_clipboard_entity_extraction" || f == "enable_clipboard_query_refactoring") param.result = false
                        }
                    })
                } catch (_: Throwable) { }
            }
        }
    }

    // 3. UI Adapter Hook (Blind Signature Search)
    private fun applyAdapterHooks(classLoader: ClassLoader, apkPath: String) {
        DexKitBridge.create(apkPath).use { bridge ->
            // 1. Find Class by String
            val classData = bridge.findClass {
                matcher { usingStrings("com/google/android/apps/inputmethod/libs/clipboard/ClipboardAdapter") }
            }.firstOrNull()

            if (classData != null) {
                // Convert Descriptor to Java Class Name (Lcom/a/b; -> com.a.b)
                var className = classData.name
                if (className.startsWith("L") && className.endsWith(";")) {
                    className = className.substring(1, className.length - 1).replace('/', '.')
                }
                
                XposedBridge.log("[UMBRA] Adapter Class: $className")

                try {
                    val clazz = XposedHelpers.findClass(className, classLoader)
                    
                    // 2. Find ALL methods: public int xxx() (No args)
                    val candidates = clazz.declaredMethods.filter { 
                        it.returnType == Int::class.javaPrimitiveType && 
                        it.parameterTypes.isEmpty() &&
                        Modifier.isPublic(it.modifiers) // Usually getItemCount is public
                    }

                    if (candidates.isEmpty()) {
                        XposedBridge.log("[UMBRA] No 'public int get()' methods found. Trying non-public...")
                        // Fallback: try non-public too just in case
                         val allCandidates = clazz.declaredMethods.filter { 
                            it.returnType == Int::class.javaPrimitiveType && it.parameterTypes.isEmpty()
                        }
                        hookCandidates(allCandidates, clazz)
                    } else {
                        hookCandidates(candidates, clazz)
                    }

                } catch (e: Exception) {
                    XposedBridge.log("[UMBRA] Adapter Reflection Error: $e")
                }
            } else {
                XposedBridge.log("[UMBRA] CRITICAL: Adapter Class NOT found")
            }
        }
    }

    private fun hookCandidates(methods: List<java.lang.reflect.Method>, clazz: Class<*>) {
        if (methods.isEmpty()) {
            XposedBridge.log("[UMBRA] CRITICAL: No methods match signature 'int get()'")
            return
        }

        for (method in methods) {
            // Skip common object methods
            if (method.name == "hashCode") continue

            XposedBridge.log("[UMBRA] Hooking candidate: ${method.name}")
            try {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as Int
                        val userLimit = getLimitValue()

                        // If it returns a constrained number (like 5 or 10), it's suspicious
                        if (result > 0 && result <= 20) {
                            val realSize = findRealListSize(param.thisObject)
                            
                            // If real data is larger than what the method returns, override it!
                            if (realSize > result) {
                                param.result = realSize
                                XposedBridge.log("[UMBRA] SUCCESS: Overriding ${method.name}: $result -> $realSize")
                            }
                        }
                    }
                })
            } catch (e: Throwable) {
                XposedBridge.log("[UMBRA] Failed to hook ${method.name}: $e")
            }
        }
    }

    // Helper: Find largest list in object via Reflection
    private fun findRealListSize(obj: Any): Int {
        var max = 0
        try {
            var cls: Class<*>? = obj.javaClass
            while (cls != null && cls != Object::class.java) {
                for (f in cls.declaredFields) {
                    f.isAccessible = true
                    try {
                        val v = f.get(obj)
                        if (v is List<*>) { if (v.size > max) max = v.size }
                        else if (v is Array<*>) { if (v.size > max) max = v.size }
                    } catch (_: Throwable) {}
                }
                cls = cls.superclass
            }
        } catch (_: Throwable) {}
        return max
    }

    // Utils
    private fun manualReadInt(key: String, def: Int): Int {
        val s = readPrefs() ?: return def
        return Regex("name=\"$key\"[^>]*value=\"(\\d+)\"").find(s)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("value=\"(\\d+)\"[^>]*name=\"$key\"").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: def
    }
    private fun manualReadLong(key: String, def: Long): Long {
        val s = readPrefs() ?: return def
        return Regex("name=\"$key\"[^>]*value=\"(\\d+)\"").find(s)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("value=\"(\\d+)\"[^>]*name=\"$key\"").find(s)?.groupValues?.get(1)?.toLongOrNull() ?: def
    }
    private fun readPrefs(): String? {
        val f = File("/data/data/$packageName/shared_prefs/${Constants.PREFS_FILE}.xml")
        return if (f.exists() && f.canRead()) BufferedReader(FileReader(f)).use { it.readText() } else null
>>>>>>> branch 'main' of https://github.com/RHineIx/Umbra.git
    }
}
