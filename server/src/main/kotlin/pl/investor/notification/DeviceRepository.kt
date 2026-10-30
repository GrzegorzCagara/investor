package pl.investor.notification

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import pl.investor.support.toUtc
import java.time.Instant

@Repository
class DeviceRepository(private val jdbc: JdbcClient) {

    fun register(token: String, at: Instant) {
        jdbc.sql("INSERT INTO device (token, registered_at) VALUES (:token, :at) ON CONFLICT (token) DO NOTHING")
            .param("token", token)
            .param("at", at.toUtc())
            .update()
    }

    fun tokens(): List<String> =
        jdbc.sql("SELECT token FROM device ORDER BY registered_at").query { rs, _ -> rs.getString("token") }.list()

    fun remove(token: String) {
        jdbc.sql("DELETE FROM device WHERE token = :token").param("token", token).update()
    }
}
