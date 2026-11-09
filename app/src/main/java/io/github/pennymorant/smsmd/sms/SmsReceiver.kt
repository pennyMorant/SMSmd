package io.github.pennymorant.smsmd.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import io.github.pennymorant.smsmd.SmsMdApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val sms = SmsParser.parse(intent) ?: return
        val pendingResult = goAsync()
        val application = context.applicationContext as SmsMdApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val jobId = application.container.forwardRepository.accept(
                    sms.sender,
                    sms.body,
                    sms.receivedAt,
                    sms.subscriptionId,
                )
                Log.i(TAG, if (jobId == null) "Incoming SMS ignored by configuration" else "Incoming SMS queued")
            } catch (error: Throwable) {
                Log.e(TAG, "Could not persist incoming SMS for forwarding", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}
