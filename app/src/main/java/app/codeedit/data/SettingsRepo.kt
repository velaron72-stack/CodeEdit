package app.codeedit.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.codeedit.core.util.MiniJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** All settings live in one JSON string inside DataStore: updates, imports and resets are single atomic writes. */
class SettingsRepo(private val context: Context) {
    private val key = stringPreferencesKey("settings.v1")

    val flow: Flow<Settings> = context.settingsStore.data.map { decode(it[key]) }.distinctUntilChanged()

    suspend fun current(): Settings = flow.first()

    suspend fun set(k: String, v: Any) {
        val def = Schema.byKey[k] ?: return
        val c = Schema.coerce(def, v) ?: return
        context.settingsStore.edit { p ->
            val cur = decode(p[key]).with(k, c)
            p[key] = encode(cur)
        }
    }

    suspend fun setMany(m: Map<String, Any>) {
        context.settingsStore.edit { p ->
            val cur = decode(p[key]).withAll(m)
            p[key] = encode(cur)
        }
    }

    suspend fun replaceAll(m: Map<String, Any>) {
        context.settingsStore.edit { p -> p[key] = encode(Settings(m)) }
    }

    suspend fun reset() { context.settingsStore.edit { it.remove(key) } }

    companion object {
        fun decode(json: String?): Settings {
            if (json.isNullOrEmpty()) return Settings()
            return try {
                val o = MiniJson.asObject(MiniJson.parse(json, false)) ?: return Settings()
                val out = HashMap<String, Any>()
                for ((k, v) in o) { val d = Schema.byKey[k] ?: continue; Schema.coerce(d, v)?.let { out[k] = it } }
                Settings(out)
            } catch (_: Throwable) { Settings() }
        }

        fun encode(s: Settings): String = MiniJson.write(LinkedHashMap<String, Any?>(s.values))
    }
}
