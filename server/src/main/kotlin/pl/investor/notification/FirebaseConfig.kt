package pl.investor.notification

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream

@Configuration
class FirebaseConfig {

    @Bean
    @ConditionalOnExpression("!'\${investor.firebase.credentials-path:}'.isEmpty()")
    fun firebaseMessaging(@Value("\${investor.firebase.credentials-path}") credentialsPath: String): FirebaseMessaging {
        val options = FileInputStream(credentialsPath).use { credentials ->
            FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(credentials)).build()
        }
        return FirebaseMessaging.getInstance(FirebaseApp.initializeApp(options))
    }
}
