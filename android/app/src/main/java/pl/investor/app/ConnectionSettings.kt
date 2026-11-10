package pl.investor.app

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Connection(val serverUrl: String, val apiToken: String)

private val Context.connectionDataStore by preferencesDataStore(name = "connection")

class ConnectionSettings(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.connectionDataStore)

    val connection: Flow<Connection?> = store.data.map { prefs ->
        val url = prefs[SERVER_URL] ?: DEFAULT_SERVER_URL
        val token = prefs[API_TOKEN] ?: DEFAULT_API_TOKEN
        if (url.isBlank() || token.isBlank()) null else Connection(url.trim().trimEnd('/'), token.trim())
    }

    suspend fun current(): Connection? {
        val conn = connection.first()
        return conn ?: Connection(DEFAULT_SERVER_URL.trimEnd('/'), DEFAULT_API_TOKEN.trim())
    }

    suspend fun save(serverUrl: String, apiToken: String) {
        store.edit {
            it[SERVER_URL] = serverUrl.trim().trimEnd('/')
            it[API_TOKEN] = apiToken.trim()
            it.remove(REGISTERED_TOKEN)
        }
    }

    suspend fun registeredToken(): String? = store.data.first()[REGISTERED_TOKEN]

    suspend fun saveRegisteredToken(token: String) {
        store.edit { it[REGISTERED_TOKEN] = token }
    }

    private companion object {
        val SERVER_URL = stringPreferencesKey("serverUrl")
        val API_TOKEN = stringPreferencesKey("apiToken")
        val REGISTERED_TOKEN = stringPreferencesKey("registeredToken")
        const val DEFAULT_SERVER_URL = "https://debian-server.tail8124e8.ts.net/investor"
        const val DEFAULT_API_TOKEN = "3609a4e0195b5da2f066c41cadcaba1614faeeebe32bc11b1c421eaea391d092"
    }
}
