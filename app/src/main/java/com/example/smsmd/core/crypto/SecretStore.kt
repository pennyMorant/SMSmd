package com.example.smsmd.core.crypto

import android.content.Context
import androidx.core.content.edit
import com.example.smsmd.core.settings.DEFAULT_PROFILE_ID

class SecretStore(context: Context, private val crypto: CryptoManager) {
    private val preferences = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)

    fun saveSmtpPassword(profileId: String, password: String) {
        preferences.edit { putString(passwordKey(profileId), crypto.encrypt(password)) }
    }

    fun smtpPassword(profileId: String): String {
        val encrypted = preferences.getString(passwordKey(profileId), null).orEmpty().ifBlank {
            if (profileId == DEFAULT_PROFILE_ID) preferences.getString(LEGACY_PASSWORD_KEY, null).orEmpty()
            else ""
        }
        return runCatching { crypto.decrypt(encrypted) }.getOrDefault("")
    }

    fun hasSmtpPassword(profileId: String): Boolean = smtpPassword(profileId).isNotBlank()

    fun deleteSmtpPassword(profileId: String) {
        preferences.edit { remove(passwordKey(profileId)) }
    }

    private fun passwordKey(profileId: String) = "smtp_password_$profileId"

    private companion object {
        const val LEGACY_PASSWORD_KEY = "smtp_password"
    }
}
