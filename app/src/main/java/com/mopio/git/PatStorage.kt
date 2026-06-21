package com.mopio.git

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PatStorage(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "mopio_secrets",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun savePat(pat: String) = prefs.edit().putString(KEY_PAT, pat).apply()
    fun loadPat(): String? = prefs.getString(KEY_PAT, null)
    fun clearPat()        = prefs.edit().remove(KEY_PAT).apply()

    companion object {
        private const val KEY_PAT = "github_pat"
    }
}
