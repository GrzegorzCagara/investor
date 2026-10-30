package pl.investor.settings

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class SettingRepository(private val jdbc: JdbcClient) {

    fun find(key: String): String? =
        jdbc.sql("SELECT value FROM setting WHERE key = :key")
            .param("key", key)
            .query(String::class.java)
            .optional()
            .orElse(null)

    fun putIfAbsent(key: String, value: String): Boolean =
        jdbc.sql("INSERT INTO setting (key, value) VALUES (:key, :value) ON CONFLICT (key) DO NOTHING")
            .param("key", key)
            .param("value", value)
            .update() == 1

    fun put(key: String, value: String) {
        jdbc.sql("INSERT INTO setting (key, value) VALUES (:key, :value) ON CONFLICT (key) DO UPDATE SET value = :value")
            .param("key", key)
            .param("value", value)
            .update()
    }
}
