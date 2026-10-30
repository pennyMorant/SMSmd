package io.github.pennymorant.smsmd.forwarding

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pennymorant.smsmd.MainActivity
import io.github.pennymorant.smsmd.SmsMdApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForwardingServiceTest {
    @Test
    fun foregroundServiceFollowsForwardingState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val application = context.applicationContext as SmsMdApplication
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val originalEnabled = application.container.settings.settings.first().forwardingEnabled

        instrumentation.runOnMainSync {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        try {
            application.container.settings.setEnabled(true)
            instrumentation.runOnMainSync { ForwardingService.update(context, true) }
            assertTrue(waitUntil { activityManager.isForwardingServiceRunning() })

            application.container.settings.setEnabled(false)
            assertTrue(waitUntil { !activityManager.isForwardingServiceRunning() })
        } finally {
            application.container.settings.setEnabled(originalEnabled)
            instrumentation.runOnMainSync { ForwardingService.update(context, originalEnabled) }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean): Boolean {
        repeat(50) {
            if (condition()) return true
            delay(100)
        }
        return false
    }

    @Suppress("DEPRECATION")
    private fun ActivityManager.isForwardingServiceRunning(): Boolean =
        getRunningServices(Int.MAX_VALUE).any {
            it.service.className == ForwardingService::class.java.name
        }
}
