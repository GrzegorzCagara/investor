package pl.investor.extraction

import com.anthropic.errors.AnthropicException
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnexpectedStatusCodeException

fun AnthropicException.toAiFailure(otherwise: (AnthropicException) -> RuntimeException): RuntimeException = when {
    isTransient() -> AiUnavailableException(this)
    isAuthenticationFailure() -> AiAuthenticationException(this)
    else -> otherwise(this)
}

private fun AnthropicException.isTransient(): Boolean =
    this is RateLimitException || this is InternalServerException || this is AnthropicIoException ||
        this is AnthropicRetryableException || (this is UnexpectedStatusCodeException && statusCode() >= 500)

private fun AnthropicException.isAuthenticationFailure(): Boolean =
    this is UnauthorizedException || this is PermissionDeniedException
