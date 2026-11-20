package com.mineygg.questmate.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mineygg.questmate.core.Redactor
import com.mineygg.questmate.core.RunBus
import com.mineygg.questmate.core.UpdateInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * All persistence. The Discord token and the webhook URL live in EncryptedSharedPreferences (Android Keystore
 * backed); settings, history and the log live in regular preferences.
 */
class Store(ctx: Context, private val secure: SharedPreferences = createSecure(ctx)) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("quest_prefs", Context.MODE_PRIVATE)

    /** The log lives in a file of its own: it is far too big for SharedPreferences. */
    private val logFile = java.io.File(ctx.filesDir, "run.log")

    private val _settings = MutableStateFlow(loadSettings())
    private val _profile = MutableStateFlow(loadProfile())
    private val _history = MutableStateFlow(QuestRecord.listFromJson(sp.getString("history", null)))
    private val _disclaimerAccepted = MutableStateFlow(sp.getBoolean("disclaimer", false))
    private val _hasWebhook = MutableStateFlow(secure.getString("webhook", null) != null)

    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val profile: StateFlow<Profile?> = _profile.asStateFlow()
    val history: StateFlow<List<QuestRecord>> = _history.asStateFlow()
    val disclaimerAccepted: StateFlow<Boolean> = _disclaimerAccepted.asStateFlow()
    val hasWebhook: StateFlow<Boolean> = _hasWebhook.asStateFlow()

    init {
        RunBus.verbose = _settings.value.verboseLog
        Redactor.register(secure.getString("token", null))
        Redactor.register(secure.getString("webhook", null))
        Redactor.register(secure.getString("qos_token", null))
    }

    val savedLog: List<String>
        get() {
            val text = runCatching { if (logFile.exists()) logFile.readText() else null }.getOrNull()
                ?: sp.getString("log", "").orEmpty() // older versions kept it in preferences
            return text.split('\n').filter { it.isNotBlank() }
        }

    // ── Settings ────────────────────────────────────────────────────────────
    private fun loadSettings(): Settings {
        val d = Settings()
        return Settings(
            theme = runCatching { ThemeMode.valueOf(sp.getString("theme", d.theme.name).orEmpty()) }.getOrDefault(d.theme),
            autoRun = sp.getBoolean("autoRun", d.autoRun),
            intervalHours = sp.getInt("intervalHours", d.intervalHours).takeIf { it in RunInterval.OPTIONS } ?: d.intervalHours,
            orbsOnly = sp.getBoolean("orbsOnly", d.orbsOnly),
            notify = sp.getBoolean("notify", d.notify),
            checkUpdates = sp.getBoolean("checkUpdates", d.checkUpdates),
            verboseLog = sp.getBoolean("verboseLog", d.verboseLog)
        )
    }

    fun saveSettings(s: Settings) {
        _settings.value = s
        sp.edit()
            .putString("theme", s.theme.name)
            .putBoolean("autoRun", s.autoRun)
            .putInt("intervalHours", s.intervalHours)
            .putBoolean("orbsOnly", s.orbsOnly)
            .putBoolean("notify", s.notify)
            .putBoolean("checkUpdates", s.checkUpdates)
            .putBoolean("verboseLog", s.verboseLog)
            .apply()
        RunBus.verbose = s.verboseLog
    }

    // ── Account ─────────────────────────────────────────────────────────────
    private fun loadProfile(): Profile? {
        val id = sp.getString("profile_id", null) ?: return null
        return Profile(id, sp.getString("profile_name", "").orEmpty())
    }

    val token: String? get() = secure.getString("token", null)

    /** The last qos_token Discord sent in READY; belongs to the signed-in account only. */
    var qosToken: String?
        get() = secure.getString("qos_token", null)
        set(v) {
            Redactor.register(v)
            secure.edit().apply { if (v.isNullOrEmpty()) remove("qos_token") else putString("qos_token", v) }.apply()
        }

    /** The installation_id Discord issued to this install; kept across sign-outs. */
    var installationId: String?
        get() = sp.getString("installation_id", null)
        set(v) {
            sp.edit().apply { if (v.isNullOrEmpty()) remove("installation_id") else putString("installation_id", v) }.apply()
        }

    fun saveAccount(token: String, profile: Profile) {
        Redactor.register(token)
        secure.edit().putString("token", token).remove("qos_token").apply()
        sp.edit().putString("profile_id", profile.id).putString("profile_name", profile.username).apply()
        _profile.value = profile
    }

    /** The token was refused by Discord (changed password, logged out everywhere...): ask for a new sign-in. */
    fun clearToken() {
        secure.edit().remove("token").remove("qos_token").apply()
        sp.edit().remove("profile_id").remove("profile_name").apply()
        _profile.value = null
    }

    // ── Disclaimer ──────────────────────────────────────────────────────────
    fun acceptDisclaimer() {
        sp.edit().putBoolean("disclaimer", true).apply()
        _disclaimerAccepted.value = true
    }

    // ── Webhook (secret: never shown again, never logged) ───────────────────
    fun webhook(): String? = secure.getString("webhook", null)

    fun setWebhook(url: String) {
        Redactor.register(url)
        secure.edit().putString("webhook", url).apply()
        _hasWebhook.value = true
    }

    fun removeWebhook() {
        secure.edit().remove("webhook").apply()
        _hasWebhook.value = false
    }

    // ── History & log ───────────────────────────────────────────────────────
    fun addRecord(r: QuestRecord) {
        val next = (listOf(r) + _history.value).take(60)
        _history.value = next
        sp.edit().putString("history", QuestRecord.listToJson(next)).apply()
    }

    fun clearHistory() {
        _history.value = emptyList()
        sp.edit().remove("history").apply()
    }

    fun saveLog(lines: List<String>) {
        runCatching { logFile.writeText(lines.takeLast(RunBus.MAX_LINES).joinToString("\n")) }
        sp.edit().remove("log").apply()
    }

    // ── Client config & device profile (see ClientConfig, DeviceProfile) ────
    val clientConfig: String? get() = sp.getString("client_config", null)
    val clientConfigCheckedAt: Long get() = sp.getLong("client_config_checked", 0L)

    fun saveClientConfig(text: String) = sp.edit().putString("client_config", text).apply()

    fun setClientConfigCheckedAt(t: Long) = sp.edit().putLong("client_config_checked", t).apply()

    val deviceProfile: String? get() = sp.getString("device_profile", null)

    fun saveDeviceProfile(v: String) = sp.edit().putString("device_profile", v).apply()

    // ── Region (Discord's /auth/location-metadata) ──────────────────────────
    val ipCountry: String? get() = sp.getString("ip_country", null)
    val ipCountryAt: Long get() = sp.getLong("ip_country_at", 0L)

    fun setIpCountry(code: String, at: Long) = sp.edit().putString("ip_country", code).putLong("ip_country_at", at).apply()

    // ── Updates ─────────────────────────────────────────────────────────────
    val lastUpdateCheck: Long get() = sp.getLong("update_checked", 0L)

    fun setLastUpdateCheck(t: Long) = sp.edit().putLong("update_checked", t).apply()

    /** The newer release found by the last check, if any. */
    val updateInfo: UpdateInfo?
        get() {
            val v = sp.getString("update_ver", null) ?: return null
            val u = sp.getString("update_url", null) ?: return null
            return UpdateInfo(v, u, sp.getLong("update_size", 0L))
        }

    fun setUpdateInfo(i: UpdateInfo?) {
        sp.edit().apply {
            if (i == null) {
                remove("update_ver").remove("update_url").remove("update_size")
            } else {
                putString("update_ver", i.version).putString("update_url", i.url).putLong("update_size", i.size)
            }
        }.apply()
    }
}

/** Secrets live in EncryptedSharedPreferences (Android Keystore backed). */
private fun createSecure(ctx: Context): SharedPreferences {
    fun build(): SharedPreferences {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        return EncryptedSharedPreferences.create(
            ctx,
            "secure_creds",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    return try {
        build()
    } catch (e: Exception) {
        // Keystore key lost or file corrupted: start clean rather than crash.
        ctx.deleteSharedPreferences("secure_creds")
        build()
    }
}
