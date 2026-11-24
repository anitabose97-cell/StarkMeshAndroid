package com.starkmesh.app

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first

private val Context.vaultDataStore by preferencesDataStore(name = "stark_vault")

class VaultManager(private val context: Context) {
    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, "stark_keyset", "stark_keyset_prefs")
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri("android-keystore://stark_master_key")
            .build().keysetHandle.getPrimitive(Aead::class.java)
    }

    suspend fun putSecret(key: String, value: String) {
        val enc = Base64.encodeToString(aead.encrypt(value.toByteArray(Charsets.UTF_8), key.toByteArray()), Base64.NO_WRAP)
        context.vaultDataStore.edit { it[stringPreferencesKey(key)] = enc }
    }

    suspend fun getSecret(key: String, default: String = ""): String {
        val enc = context.vaultDataStore.data.first()[stringPreferencesKey(key)] ?: return default
        return try { String(aead.decrypt(Base64.decode(enc, Base64.NO_WRAP), key.toByteArray()), Charsets.UTF_8) }
        catch (_: Exception) { default }
    }
}
