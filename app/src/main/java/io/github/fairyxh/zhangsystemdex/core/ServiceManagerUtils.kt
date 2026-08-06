package io.github.fairyxh.zhangsystemdex.core

import android.os.IBinder
import android.os.Parcel

/**
 * Binder access through ServiceManager reflection. Used for private
 * SurfaceFlinger transactions that were previously `service call`.
 */
object ServiceManagerUtils {
    fun getService(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            sm.getMethod("getService", String::class.java).invoke(null, name) as? IBinder
        } catch (t: Throwable) {
            null
        }
    }

    fun surfaceFlingerTransact(code: Int, vararg intArgs: Int): Boolean {
        val binder = getService("SurfaceFlinger") ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken("android.ui.ISurfaceComposer")
            for (arg in intArgs) data.writeInt(arg)
            binder.transact(code, data, reply, 0)
        } catch (t: Throwable) {
            Logger.w("BinderUtils", "SurfaceFlinger transact $code failed: ${t.message}")
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
