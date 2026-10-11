package io.github.pennymorant.smsmd.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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
        val container = (context.applicationContext as SmsMdApplication).container
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                container.forwardRepository.accept(
                    sms.sender,
                    sms.body,
                    sms.receivedAt,
                    sms.subscriptionId,
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}
