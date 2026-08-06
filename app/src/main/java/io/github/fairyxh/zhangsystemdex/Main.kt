package io.github.fairyxh.zhangsystemdex
import android.os.Process
import android.util.Log

object Main {
    @JvmStatic
    fun main(args:Array<String>) {

        Log.i(
            "ZhangSystemDex",
            "Started uid=${Process.myUid()}"
        )

        while(true){

            Thread.sleep(10000)

        }
    }
}