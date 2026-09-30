package rida.pour.les.pros.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    /** Interlocuteur par défaut d'une nouvelle ligne. */
    val defaultInterlocuteur: String = DEFAULT_INTERLOCUTEUR,
    val zoneId: String = DEFAULT_ZONE,
    /** Modèle Claude utilisé par l'agent. */
    val model: String = DEFAULT_MODEL,
    /** Nombre d'échanges conservés en mémoire de conversation. */
    val memoryExchanges: Int = 10,
    /** Destinataires par défaut des extraits RIDA. */
    val mailTo: List<String> = emptyList(),
    val mailCc: List<String> = emptyList(),
    val mailSubject: String = DEFAULT_SUBJECT,
    val digestEnabled: Boolean = true,
    val digestTime: String = "07:45",
    /** Jours du digest (1 = lundi … 7 = dimanche). */
    val digestDays: Set<Int> = setOf(1, 2, 3, 4, 5),
    /** Dernière exécution du job quotidien (AAAA-MM-JJ). */
    val lastDailyRun: String? = null,
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))
    val digestLocalTime: LocalTime get() = runCatching { LocalTime.parse(digestTime) }.getOrDefault(LocalTime.of(7, 45))
    fun isDigestDay(date: LocalDate) = date.dayOfWeek.value in digestDays

    companion object {
        const val DEFAULT_INTERLOCUTEUR = "Moi"
        const val DEFAULT_ZONE = "Europe/Paris"
        const val DEFAULT_MODEL = "claude-sonnet-5"
        const val DEFAULT_SUBJECT = "Extrait RIDA du {date}"
        val DAY_LABELS = DayOfWeek.entries.associate { it.value to listOf("Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim")[it.value - 1] }
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Lecture des paramètres (interface pour les tests). */
interface SettingsProvider {
    suspend fun current(): AppSettings
}

private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

fun parseEmails(raw: String): List<String> {
    val list = raw.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    val bad = list.filterNot { EMAIL.matches(it) }
    require(bad.isEmpty()) { "Adresse(s) invalide(s) : ${bad.joinToString(", ")}" }
    return list
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) : SettingsProvider {
    private val store = context.settingsStore

    private object Keys {
        val INTERLOCUTEUR = stringPreferencesKey("default_interlocuteur")
        val ZONE = stringPreferencesKey("zone_id")
        val MODEL = stringPreferencesKey("model")
        val MEMORY = intPreferencesKey("memory_exchanges")
        val MAIL_TO = stringPreferencesKey("mail_to")
        val MAIL_CC = stringPreferencesKey("mail_cc")
        val MAIL_SUBJECT = stringPreferencesKey("mail_subject")
        val DIGEST_ENABLED = booleanPreferencesKey("digest_enabled")
        val DIGEST_TIME = stringPreferencesKey("digest_time")
        val DIGEST_DAYS = stringPreferencesKey("digest_days")
        val LAST_DAILY = stringPreferencesKey("last_daily_run")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            defaultInterlocuteur = p[Keys.INTERLOCUTEUR]?.takeIf { it.isNotBlank() } ?: d.defaultInterlocuteur,
            zoneId = p[Keys.ZONE]?.takeIf { it.isNotBlank() } ?: d.zoneId,
            model = p[Keys.MODEL]?.takeIf { it.isNotBlank() } ?: d.model,
            memoryExchanges = p[Keys.MEMORY] ?: d.memoryExchanges,
            mailTo = p[Keys.MAIL_TO]?.split(',')?.filter { it.isNotBlank() } ?: d.mailTo,
            mailCc = p[Keys.MAIL_CC]?.split(',')?.filter { it.isNotBlank() } ?: d.mailCc,
            mailSubject = p[Keys.MAIL_SUBJECT]?.takeIf { it.isNotBlank() } ?: d.mailSubject,
            digestEnabled = p[Keys.DIGEST_ENABLED] ?: d.digestEnabled,
            digestTime = p[Keys.DIGEST_TIME] ?: d.digestTime,
            digestDays = p[Keys.DIGEST_DAYS]?.split(',')?.mapNotNull { it.toIntOrNull() }?.toSet() ?: d.digestDays,
            lastDailyRun = p[Keys.LAST_DAILY],
        )
    }

    override suspend fun current(): AppSettings = settings.first()

    suspend fun updateGeneral(defaultInterlocuteur: String, zoneId: String) {
        require(runCatching { ZoneId.of(zoneId.trim()) }.isSuccess) { "Fuseau horaire inconnu : $zoneId" }
        store.edit {
            it[Keys.INTERLOCUTEUR] = defaultInterlocuteur.trim()
            it[Keys.ZONE] = zoneId.trim()
        }
    }

    suspend fun updateAgent(model: String, memoryExchanges: Int) {
        require(model.isNotBlank()) { "Modèle vide." }
        store.edit {
            it[Keys.MODEL] = model.trim()
            it[Keys.MEMORY] = memoryExchanges.coerceIn(0, 30)
        }
    }

    suspend fun updateMail(to: String, cc: String, subject: String) {
        val toList = parseEmails(to)
        val ccList = parseEmails(cc)
        store.edit {
            it[Keys.MAIL_TO] = toList.joinToString(",")
            it[Keys.MAIL_CC] = ccList.joinToString(",")
            it[Keys.MAIL_SUBJECT] = subject.trim().ifEmpty { AppSettings.DEFAULT_SUBJECT }
        }
    }

    suspend fun updateDigest(enabled: Boolean, time: String, days: Set<Int>) {
        require(runCatching { LocalTime.parse(time.trim()) }.isSuccess) { "Heure invalide (format HH:mm) : $time" }
        store.edit {
            it[Keys.DIGEST_ENABLED] = enabled
            it[Keys.DIGEST_TIME] = LocalTime.parse(time.trim()).toString()
            it[Keys.DIGEST_DAYS] = days.sorted().joinToString(",")
        }
    }

    suspend fun setLastDailyRun(date: LocalDate) {
        store.edit { it[Keys.LAST_DAILY] = date.toString() }
    }
}

/** Horloge injectable (tests). La date du jour est toujours calculée par le code, jamais par l'agent. */
interface RidaClock {
    fun today(zone: ZoneId): LocalDate
    fun nowMillis(): Long
}

class SystemRidaClock @Inject constructor() : RidaClock {
    override fun today(zone: ZoneId): LocalDate = LocalDate.now(zone)
    override fun nowMillis(): Long = System.currentTimeMillis()
}
