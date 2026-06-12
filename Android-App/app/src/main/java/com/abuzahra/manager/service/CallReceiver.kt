package com.abuzahra.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallReceiver : BroadcastReceiver() {

    companion object {
        private var _callStartTime: Long = 0
        private var _callNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                    val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"

                    // Check permission
                    if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                        Log.w("CallReceiver", "No call log permission")
                        pendingResult.finish()
                        return@launch
                    }

                    when (state) {
                        TelephonyManager.EXTRA_STATE_RINGING -> {
                            // Report incoming call
                            val data = mapOf("type" to "incoming_call", "number" to number, "timestamp" to System.currentTimeMillis())
                            com.abuzahra.manager.EventBuffer.addEvent("call", data)
                        }
                        TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                            _callStartTime = System.currentTimeMillis()
                            _callNumber = number
                        }
                        TelephonyManager.EXTRA_STATE_IDLE -> {
                            if (_callStartTime > 0) {
                                val duration = ((System.currentTimeMillis() - _callStartTime) / 1000).toInt()
                                val data = mapOf("type" to "call_ended", "number" to (_callNumber ?: ""), "duration" to duration, "timestamp" to System.currentTimeMillis())
                                com.abuzahra.manager.EventBuffer.addEvent("call", data)
                                _callStartTime = 0
                                _callNumber = null
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CallReceiver", "Error processing call event", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}