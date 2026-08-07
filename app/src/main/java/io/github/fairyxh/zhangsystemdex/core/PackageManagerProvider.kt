package io.github.fairyxh.zhangsystemdex.core

import android.content.pm.PackageInfo

@Suppress("PrivateApi")
object PackageManagerProvider {

    /**
     * 获取系统 IPackageManager
     */
    private fun getIPackageManager(): Any? {

        return try {

            val appGlobals =
                Class.forName(
                    "android.app.AppGlobals"
                )


            appGlobals
                .getMethod(
                    "getPackageManager"
                )
                .invoke(null)


        } catch (t: Throwable) {

            Logger.w(
                "PackageManagerProvider",
                "获取IPackageManager失败: ${t.message}"
            )

            null
        }
    }


    /**
     * 获取所有已安装应用
     *
     * Android 11+
     * IPackageManager.getInstalledPackages(long,int)
     */
    fun getInstalledPackages(): List<PackageInfo> {

        return try {

            val ipm =
                getIPackageManager()
                    ?: return emptyList()


            val method =
                ipm.javaClass
                    .getMethod(
                        "getInstalledPackages",
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )


            /*
             * flags:
             * 0 = 基础PackageInfo
             *
             * userId:
             * 0 = 主用户
             */
            val slice =
                method.invoke(
                    ipm,
                    0L,
                    0
                )
                    ?: return emptyList()


            /*
             * Android Framework:
             *
             * ParceledListSlice
             *
             * 内部:
             * private final List<T> mList;
             */
            val field =
                slice.javaClass
                    .getDeclaredField(
                        "mList"
                    )


            field.isAccessible = true


            @Suppress("UNCHECKED_CAST")
            return field.get(slice)
                    as? List<PackageInfo>
                ?: emptyList()


        } catch (t: Throwable) {

            Logger.w(
                "PackageManagerProvider",
                "获取已安装包失败: ${t.message}"
            )

            emptyList()
        }
    }
}