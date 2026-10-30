package pl.investor.extraction

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class AnthropicConfig {

    @Bean
    fun anthropicClient(
        @Value("\${investor.anthropic.base-url}") baseUrl: String,
        @Value("\${investor.anthropic.api-key}") apiKey: String,
    ): AnthropicClient =
        AnthropicOkHttpClient.builder()
            .baseUrl(baseUrl)
            .apiKey(apiKey)
            .timeout(Duration.ofSeconds(60))
            .build()
}
