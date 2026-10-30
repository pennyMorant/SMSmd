package io.github.pennymorant.smsmd.forwarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.pennymorant.smsmd.SmsMdApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ForwardingBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pendingResult = goAsync()
        val application = context.applicationContext as SmsMdApplication
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val container = application.container
                val enabled = container.settings.settings.first().forwardingEnabled
                ForwardingService.update(application, enabled)
                val dao = container.database.forwardDao()
                dao.trimLogsToLatest()
                dao.deleteFailedJobs()
                container.forwardRepository.recoverPendingJobs()
            } catch (error: Throwable) {
                Log.e(TAG, "Could not restore forwarding after restart", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ForwardingBootReceiver"
    }
}
