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
import org.luckypray.dexkit.wrap.DexMethod

class GboardHook {

    companion object {
        private const val TAG = "[UMBRA-GBOARD]"
        private const val PREFS_CACHE = "umbra_gboard_cache"
        private const val KEY_VERSION = "gboard_version"
        private const val KEY_METHOD_CONFIG = "method_read_config"
    }

    init {
        try {
            System.loadLibrary("dexkit")
        } catch (_: Throwable) {}
    }

    private fun getPrefs(): XSharedPreferences? {
    val pref = XSharedPreferences(
        "com.umbra.hooks",
        Constants.PREFS_FILE
    )

    // 🔑 مهم جداً: القراءة من Device Protected Storage
    pref.makeWorldReadable()
    pref.reload()

    return if (pref.file.exists() && pref.file.canRead()) {
        pref
    } else {
        XposedBridge.log("$TAG prefs not readable: ${pref.file.absolutePath}")
        null
    }
}

    private val clipboardLimit: Int
        get() = getPrefs()
            ?.getInt(Constants.KEY_GBOARD_LIMIT, Constants.DEFAULT_GBOARD_LIMIT)
            ?: Constants.DEFAULT_GBOARD_LIMIT

    private val retentionMs: Long
        get() {
            val days = getPrefs()
                ?.getInt(
                    Constants.KEY_GBOARD_RETENTION_DAYS,
                    Constants.DEFAULT_GBOARD_RETENTION
                )
                ?: Constants.DEFAULT_GBOARD_RETENTION
            return days * 24L * 60 * 60 * 1000
        }

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        val classLoader = lpparam.classLoader
                        hookClipboardProvider(classLoader)
                        setupDynamicHooks(param.args[0] as Context, classLoader)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG attach error: $t")
                    }
                }
            }
        )
    }

    private fun hookClipboardProvider(classLoader: ClassLoader) {

        val providerClass =
            "com.google.android.apps.inputmethod.libs.clipboard.ClipboardContentProvider"

        try {
            XposedHelpers.findAndHookMethod(
                providerClass,
                classLoader,
                "query",
                Uri::class.java,
                Array<String>::class.java,
                String::class.java,
                Array<String>::class.java,
                String::class.java,
                object : XC_MethodHook() {

                    override fun beforeHookedMethod(param: MethodHookParam) {

                        val limit = clipboardLimit
                        val cutoffTime = System.currentTimeMillis() - retentionMs

                        val selection = param.args[2]?.toString() ?: ""
                        val selectionArgs = param.args[3] as? Array<String>
                        val sortOrder = param.args[4]?.toString() ?: ""

                        // ===== Time condition =====
                        if (selection.contains("timestamp >= ?") && selectionArgs != null) {
                            var index = 0
                            for (i in 0 until selection.indexOf("timestamp >= ?")) {
                                if (selection[i] == '?') index++
                            }
                            if (index < selectionArgs.size) {
                                selectionArgs[index] = cutoffTime.toString()
                                param.args[3] = selectionArgs
                            }
                        }

                        // ===== Limit condition =====
                        if (sortOrder.contains("limit", ignoreCase = true)) {
                            val newOrder = sortOrder.replace(
                                Regex("limit\\s+\\d+", RegexOption.IGNORE_CASE),
                                "limit $limit"
                            )
                            param.args[4] = newOrder
                        }

                        XposedBridge.log(
                            "$TAG limit=$limit retentionMs=$retentionMs file=${getPrefs()?.file?.absolutePath}"
                        )
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Clipboard hook failed: $t")
        }
    }

    /**
     * 🔧 DexKit logic (كما هو بدون تغيير)
     */
    private fun setupDynamicHooks(context: Context, classLoader: ClassLoader) {

        val cachePrefs = context.getSharedPreferences(PREFS_CACHE, Context.MODE_PRIVATE)
        val versionCode = context.packageManager
            .getPackageInfo(context.packageName, 0).versionCode

        val cachedVersion = cachePrefs.getInt(KEY_VERSION, -1)
        var methodName: String? =
            if (versionCode == cachedVersion)
                cachePrefs.getString(KEY_METHOD_CONFIG, null)
            else null

        if (methodName == null) {
            try {
                DexKitBridge.create(classLoader, true).use { bridge ->
                    val method = bridge.findMethod {
                        matcher {
                            usingStrings("Invalid flag: ")
                            returnType("java.lang.Object")
                        }
                    }.singleOrNull()

                    if (method != null) {
                        methodName = method.toDexMethod().serialize()
                        cachePrefs.edit()
                            .putInt(KEY_VERSION, versionCode)
                            .putString(KEY_METHOD_CONFIG, methodName)
                            .apply()
                    }
                }
            } catch (_: Throwable) {}
        }

        if (methodName != null) {
            try {
                val dexMethod = DexMethod(methodName!!)
                XposedHelpers.findAndHookMethod(
                    dexMethod.className,
                    classLoader,
                    dexMethod.name,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val name =
                                XposedHelpers.getObjectField(param.thisObject, "a").toString()
                            if (
                                name == "enable_clipboard_entity_extraction" ||
                                name == "enable_clipboard_query_refactoring"
                            ) {
                                param.result = false
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
                cachePrefs.edit().remove(KEY_METHOD_CONFIG).apply()
            }
        }
    }
}