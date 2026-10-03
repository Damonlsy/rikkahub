package me.rerere.rikkahub.data.ai

import me.rerere.common.android.LogEntry
import me.rerere.common.android.Logging
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.Response

class RequestLoggingInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!Logging.isRequestLoggingEnabled()) {
            return chain.proceed(chain.request())
        }

        val request = chain.request()
        val startTime = System.currentTimeMillis()

        val requestHeaders = request.headers.toMap()
        val requestBody = request.body?.let { body ->
            val bytes = runCatching { body.contentLength() }.getOrDefault(-1L)
            if (bytes >= 0) "<omitted; $bytes bytes>" else "<omitted>"
        }

        val response: Response
        var error: String? = null

        try {
            response = chain.proceed(request)
        } catch (e: Exception) {
            error = e.message
            Logging.logRequest(
                LogEntry.RequestLog(
                    tag = "HTTP",
                    url = request.url.redactedUrl(),
                    method = request.method,
                    requestHeaders = requestHeaders,
                    requestBody = requestBody,
                    error = error
                )
            )
            throw e
        }

        val durationMs = System.currentTimeMillis() - startTime
        val responseHeaders = response.headers.toMap()

        Logging.logRequest(
            LogEntry.RequestLog(
                tag = "HTTP",
                url = request.url.redactedUrl(),
                method = request.method,
                requestHeaders = requestHeaders,
                requestBody = requestBody,
                responseCode = response.code,
                responseHeaders = responseHeaders,
                durationMs = durationMs,
                error = error
            )
        )

        return response
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> {
        return names().associateWith { name ->
            if (name.equals("Authorization", ignoreCase = true) ||
                name.equals("Proxy-Authorization", ignoreCase = true) ||
                name.equals("X-Api-Key", ignoreCase = true) ||
                name.equals("Api-Key", ignoreCase = true) ||
                name.equals("x-goog-api-key", ignoreCase = true) ||
                name.equals("X-Subscription-Token", ignoreCase = true) ||
                name.equals("Cookie", ignoreCase = true) ||
                name.equals("Set-Cookie", ignoreCase = true)
            ) {
                "██"
            } else {
                get(name) ?: ""
            }
        }
    }

    private fun HttpUrl.redactedUrl(): String {
        val sensitiveNames = setOf("key", "api_key", "apikey", "access_token", "token")
        val builder = newBuilder()
        queryParameterNames
            .filter { it.lowercase() in sensitiveNames }
            .forEach { builder.setQueryParameter(it, "██") }
        return builder.build().toString()
    }
}
