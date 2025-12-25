package com.umbra.hooks

import com.umbra.hooks.apps.PinterestHook
import com.umbra.hooks.apps.GboardHook
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        
        when (lpparam.packageName) {
            "com.pinterest" -> {
                PinterestHook().init(lpparam)
            }
            "com.google.android.inputmethod.latin" -> {
                GboardHook().init(lpparam)
            }
        }
    }
}
