package com.lits.mocklocation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            val prefs = context.getSharedPreferences(MockLocationService.PREFS_NAME, Context.MODE_PRIVATE)
            val wasMocking = prefs.getBoolean(MockLocationService.KEY_IS_MOCKING, false)
            if (wasMocking) {
                val isRoute = prefs.getBoolean(MockLocationService.KEY_IS_ROUTE_MODE, false)
                val serviceIntent = Intent(context, MockLocationService::class.java).apply {
                    this.action = if (isRoute) MockLocationService.ACTION_START_ROUTE else MockLocationService.ACTION_START
                    putExtra(MockLocationService.EXTRA_LATITUDE, prefs.getFloat(MockLocationService.KEY_TARGET_LAT, 19.1989f).toDouble())
                    putExtra(MockLocationService.EXTRA_LONGITUDE, prefs.getFloat(MockLocationService.KEY_TARGET_LNG, 73.1918f).toDouble())
                }
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
