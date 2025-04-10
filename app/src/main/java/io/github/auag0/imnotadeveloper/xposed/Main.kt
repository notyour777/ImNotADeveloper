package io.github.auag0.imnotadeveloper.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedBridge.hookAllMethods
import de.robv.android.xposed.XposedHelpers.findClass
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.auag0.imnotadeveloper.BuildConfig
import io.github.auag0.imnotadeveloper.common.Logger.logD
import io.github.auag0.imnotadeveloper.common.Logger.logE
import io.github.auag0.imnotadeveloper.common.PrefKeys
import io.github.auag0.imnotadeveloper.common.PrefKeys.HIDE_DEBUG_PROPERTIES
import io.github.auag0.imnotadeveloper.common.PrefKeys.HIDE_DEBUG_PROPERTIES_IN_NATIVE
import io.github.auag0.imnotadeveloper.common.PrefKeys.HIDE_DEVELOPER_MODE
import io.github.auag0.imnotadeveloper.common.PrefKeys.HIDE_USB_DEBUG
import io.github.auag0.imnotadeveloper.common.PrefKeys.HIDE_WIRELESS_DEBUG
import io.github.auag0.imnotadeveloper.common.PropKeys
import io.github.auag0.imnotadeveloper.common.PropKeys.ADB_ENABLED
import io.github.auag0.imnotadeveloper.common.PropKeys.ADB_WIFI_ENABLED
import io.github.auag0.imnotadeveloper.common.PropKeys.DEVELOPMENT_SETTINGS_ENABLED
import java.lang.reflect.Method

object PrefKeys {
    const val HIDE_DEBUG_PROPERTIES = "hide_debug_properties"
    const val HIDE_DEBUG_PROPERTIES_IN_NATIVE = "hide_debug_properties_in_native"
    const val HIDE_DEVELOPER_MODE = "hide_developer_mode"
    const val HIDE_USB_DEBUG = "hide_usb_debug"
    const val HIDE_WIRELESS_DEBUG = "hide_wireless_debug"
    const val SPOOF_WORK_PROFILE = "spoof_work_profile" // New preference key
}

class Main : IXposedHookLoadPackage {
    private val prefs = XSharedPreferences(BuildConfig.APPLICATION_ID)

    val propOverrides = mapOf(
        PropKeys.SYS_USB_FFS_READY to "0",
        PropKeys.SYS_USB_CONFIG to "mtp",
        PropKeys.PERSIST_SYS_USB_CONFIG to "mtp",
        PropKeys.SYS_USB_STATE to "mtp",
        PropKeys.INIT_SVC_ADBD to "stopped"
    )

    override fun handleLoadPackage(param: LoadPackageParam) {
        hookSettingsMethods(param.classLoader)
        hookSystemPropertiesMethods(param.classLoader)
        hookProcessMethods(param.classLoader)
        hookNativeMethods()
        hookWorkProfileMethods(param.classLoader) // New hook for work profile spoofing
    }

    private fun hookNativeMethods() {
        if (!getSPBool(PrefKeys.HIDE_DEBUG_PROPERTIES_IN_NATIVE, true)) return
        try {
            System.loadLibrary("ImNotADeveloper")
            // Assuming NativeFun is part of the existing native code
            // NativeFun.setProps(propOverrides)
        } catch (e: Exception) {
            logE(e.message)
        }
    }

    private fun hookProcessMethods(classLoader: ClassLoader) {
        if (!getSPBool(PrefKeys.HIDE_DEBUG_PROPERTIES, true)) return
        val hookCmd = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                hookedLog(param)
                val cmdarray = (param.args[0] as Array<*>).filterIsInstance<String>()
                val firstCmd = cmdarray.getOrNull(0)
                val secondCmd = cmdarray.getOrNull(1)
                if (firstCmd == "getprop" && propOverrides.containsKey(secondCmd)) {
                    val writableCmdArray = ArrayList(cmdarray)
                    writableCmdArray[1] = "Dummy${System.currentTimeMillis()}"
                    val a: Array<String> = writableCmdArray.toTypedArray()
                    param.args[0] = a
                }
            }
        }
        val processImpl = findClass("java.lang.ProcessImpl", classLoader)
        hookAllMethods(processImpl, "start", hookCmd)

        val processManager = findClass("java.lang.ProcessManager", classLoader)
        hookAllMethods(processManager, "exec", hookCmd)
    }

    private fun hookSystemPropertiesMethods(classLoader: ClassLoader) {
        if (!getSPBool(PrefKeys.HIDE_DEBUG_PROPERTIES, true)) return
        val methods = arrayOf(
            "native_get",
            "native_get_int",
            "native_get_long",
            "native_get_boolean"
        )
        val systemProperties = findClass("android.os.SystemProperties", classLoader)
        methods.forEach { methodName ->
            hookAllMethods(systemProperties, methodName, object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                    if (param.args[0] !is String) return param.invokeOriginalMethod()
                    hookedLog(param)
                    val key = param.args[0] as String
                    val method = param.method as Method

                    val value = propOverrides[key]
                    if (value != null) {
                        return try {
                            when (method.returnType) {
                                String::class.java -> value
                                Int::class.java -> value.toInt()
                                Long::class.java -> value.toLong()
                                Boolean::class.java -> value.toBoolean()
                                else -> param.invokeOriginalMethod()
                            }
                        } catch (e: NumberFormatException) {
                            logE(e.message)
                        }
                    }

                    return param.invokeOriginalMethod()
                }
            })
        }
    }

    private fun hookSettingsMethods(classLoader: ClassLoader) {
        val bannedKeys = ArrayList<String>()
        if (getSPBool(PrefKeys.HIDE_DEVELOPER_MODE, true)) bannedKeys.add(DEVELOPMENT_SETTINGS_ENABLED)
        if (getSPBool(PrefKeys.HIDE_USB_DEBUG, true)) bannedKeys.add(ADB_ENABLED)
        if (getSPBool(PrefKeys.HIDE_WIRELESS_DEBUG, true)) bannedKeys.add(ADB_WIFI_ENABLED)
        if (bannedKeys.isEmpty()) return
        val settingsClassNames = arrayOf(
            "android.provider.Settings.Secure",
            "android.provider.Settings.System",
            "android.provider.Settings.Global",
            "android.provider.Settings.NameValueCache"
        )
        settingsClassNames.forEach {
            val clazz = findClass(it, classLoader)
            hookAllMethods(clazz, "getStringForUser", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                    hookedLog(param)
                    val name = param.args[1] as? String?
                    return if (bannedKeys.contains(name)) {
                        "0"
                    } else {
                        param.invokeOriginalMethod()
                    }
                }
            })
        }
    }

    private fun hookWorkProfileMethods(classLoader: ClassLoader) {
        if (!getSPBool(PrefKeys.SPOOF_WORK_PROFILE, false)) return

        try {
            val devicePolicyManager = findClass("android.app.admin.DevicePolicyManager", classLoader)
            hookAllMethods(devicePolicyManager, "isProfileOwnerApp", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                    hookedLog(param)
                    return false // Spoof that the app is not in a work profile
                }
            })

            hookAllMethods(devicePolicyManager, "isInAdminMode", object : XC_MethodReplacement() {
                override fun replaceHookedMethod(param: XC_MethodHook.MethodHookParam): Any? {
                    hookedLog(param)
                    return false // Spoof that the app is not in admin mode
                }
            })
        } catch (e: Exception) {
            logE("Failed to hook work profile methods: ${e.message}")
        }
    }

    private fun hookedLog(param: XC_MethodHook.MethodHookParam) {
        val method = param.method as Method
        val message = buildString {
            appendLine("Hooked ${method.declaringClass.name}.${method.name} -> ${method.returnType.name}")
            param.args.forEachIndexed { index, any ->
                appendLine("    $index:${any.string()}")
            }
        }
        logD(message)
    }

    private fun Any?.string(): String {
        return when (this) {
            is List<*> -> joinToString(prefix = "[", postfix = "]")
            is Array<*> -> joinToString(prefix = "[", postfix = "]")
            else -> toString()
        }
    }

    private fun String.toBoolean(): Boolean {
        return when {
            equals("true", true) || equals("1", true) -> true
            equals("false", true) || equals("0", true) -> false
            else -> throw NumberFormatException(this)
        }
    }

    private fun XC_MethodHook.MethodHookParam.invokeOriginalMethod(): Any? {
        return XposedBridge.invokeOriginalMethod(method, thisObject, args)
    }

    private fun getSPBool(key: String, def: Boolean): Boolean {
        prefs.reload()
        return prefs.getBoolean(key, def)
    }
}
