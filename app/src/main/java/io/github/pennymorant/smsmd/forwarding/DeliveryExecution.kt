package io.github.pennymorant.smsmd.forwarding

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import java.security.cert.CertificateException
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException
import org.eclipse.angus.mail.smtp.SMTPSendFailedException

internal object DeliveryExecutionGate {
    private val mutex = Mutex()

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock { block() }
}

internal fun isPermanentSmtpFailure(error: Throwable): Boolean {
    val failures = error.failureChain()
    if (failures.any { it is SSLHandshakeException || it is CertificateException }) return true

    val statusCodes = failures.mapNotNull {
        when (it) {
            is SMTPAddressFailedException -> it.returnCode
            is SMTPSendFailedException -> it.returnCode
            else -> null
        }
    }
    if (statusCodes.any { it in 400..499 }) return false
    if (statusCodes.any { it >= 500 }) return true

    return failures.any {
        it is AuthenticationFailedException || it is SendFailedException ||
            it is IllegalArgumentException
    }
}

private fun Throwable.failureChain(): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val pending = ArrayDeque<Throwable>().apply { add(this@failureChain) }
    val failures = mutableListOf<Throwable>()
    while (pending.isNotEmpty()) {
        val current = pending.removeFirst()
        if (!seen.add(current)) continue
        failures += current
        current.cause?.let(pending::addLast)
        (current as? MessagingException)?.nextException?.let(pending::addLast)
    }
    return failures
}
