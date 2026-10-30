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
        val url = prefs[SERVER_URL]
        val token = prefs[API_TOKEN]
        if (url.isNullOrBlank() || token.isNullOrBlank()) null else Connection(url, token)
    }

    suspend fun current(): Connection? = connection.first()

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
    }
}
