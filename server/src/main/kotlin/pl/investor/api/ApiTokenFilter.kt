package pl.investor.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.util.UrlPathHelper
import java.security.MessageDigest

@Component
class ApiTokenFilter(@Value("\${investor.api-token:}") token: String) : OncePerRequestFilter() {

    private val expected = token.also { check(it.isNotBlank()) { "INVESTOR_API_TOKEN must be set" } }.toByteArray()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = UrlPathHelper.defaultInstance.getPathWithinApplication(request).isHealthEndpoint()

    private fun String.isHealthEndpoint(): Boolean =
        !contains("..") && (this == HEALTH_PATH || startsWith("$HEALTH_PATH/"))

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val provided = request.getHeader(HEADER)?.toByteArray()
        if (provided != null && MessageDigest.isEqual(provided, expected)) {
            chain.doFilter(request, response)
        } else {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
        }
    }

    private companion object {
        const val HEADER = "X-Investor-Token"
        const val HEALTH_PATH = "/actuator/health"
    }
}
