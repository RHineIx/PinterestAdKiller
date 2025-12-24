package com.umbra.hooks

import com.umbra.hooks.apps.PinterestHook
import com.umbra.hooks.apps.GboardHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        // Log to verify module is active
        if (lpparam.packageName == "com.google.android.inputmethod.latin") {
            XposedBridge.log("Umbra: Detected Gboard load -> ${lpparam.processName}")
            GboardHook().init(lpparam)
        } else if (lpparam.packageName == "com.pinterest") {
            PinterestHook().init(lpparam)
        }
    }
}