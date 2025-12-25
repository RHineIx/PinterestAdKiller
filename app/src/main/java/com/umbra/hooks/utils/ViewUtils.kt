package com.umbra.hooks.utils

import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object ViewUtils {

    // إخفاء الـ View تماماً وتصفير حجمه
    fun nukeView(view: View) {
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

    // إجبار الـ View على أن يكون قياسه 0x0 دائماً إذا كان مخفياً
    // هذا يمنع ظهور مساحات بيضاء فارغة
    fun hookOnMeasure(clazz: Class<*>, hookedClasses: HashSet<String>) {
        if (hookedClasses.contains(clazz.name)) return
        hookedClasses.add(clazz.name)

        try {
            XposedHelpers.findAndHookMethod(
                clazz,
                "onMeasure",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        if (view.visibility == View.GONE) {
                            try {
                                XposedHelpers.callMethod(view, "setMeasuredDimension", 0, 0)
                            } catch (_: Throwable) {}
                            param.result = null // تخطي التنفيذ الأصلي
                        }
                    }
                })
        } catch (_: Throwable) {}
    }
}
