package rida.pour.les.pros.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    /** Interlocuteur par défaut d'une nouvelle ligne. */
    val defaultInterlocuteur: String = DEFAULT_INTERLOCUTEUR,
    val zoneId: String = DEFAULT_ZONE,
) {
    val zone: ZoneId get() = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.of(DEFAULT_ZONE))

    companion object {
        const val DEFAULT_INTERLOCUTEUR = "Moi"
        const val DEFAULT_ZONE = "Europe/Paris"
    }
}

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Lecture des paramètres (interface pour les tests). */
interface SettingsProvider {
    suspend fun current(): AppSettings
}

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) : SettingsProvider {
    private val store = context.settingsStore

    private object Keys {
        val INTERLOCUTEUR = stringPreferencesKey("default_interlocuteur")
        val ZONE = stringPreferencesKey("zone_id")
    }

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            defaultInterlocuteur = p[Keys.INTERLOCUTEUR]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_INTERLOCUTEUR,
            zoneId = p[Keys.ZONE]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_ZONE,
        )
    }

    override suspend fun current(): AppSettings = settings.first()

    suspend fun update(defaultInterlocuteur: String, zoneId: String) {
        require(runCatching { ZoneId.of(zoneId.trim()) }.isSuccess) { "fuseau horaire inconnu : $zoneId" }
        store.edit {
            it[Keys.INTERLOCUTEUR] = defaultInterlocuteur.trim()
            it[Keys.ZONE] = zoneId.trim()
        }
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
