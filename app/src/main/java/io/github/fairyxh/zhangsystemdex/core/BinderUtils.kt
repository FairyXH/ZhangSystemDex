package io.github.fairyxh.zhangsystemdex.core

import android.os.IBinder
import java.lang.reflect.Method

object BinderUtils {
    private val serviceManagerClass by lazy { Class.forName("android.os.ServiceManager") }
    private var getServiceMethod: Method? = null

    fun getService(name: String): IBinder? {
        return try {
            if (getServiceMethod == null) {
                getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            }
            getServiceMethod?.invoke(null, name) as? IBinder
        } catch (t: Throwable) {
            Logger.e("Binder", "getService($name) failed", t)
            null
        }
    }

    fun asInterface(binder: IBinder, stubClassName: String): Any? {
        return try {
            val stub = Class.forName(stubClassName)
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (t: Throwable) {
            Logger.e("Binder", "asInterface($stubClassName) failed", t)
            null
        }
    }
}
