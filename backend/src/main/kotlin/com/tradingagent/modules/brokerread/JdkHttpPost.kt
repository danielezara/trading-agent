package com.tradingagent.modules.brokerread

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface HttpPost {
    fun post(uri: URI, headers: Map<String, String>, body: String): InboundHttp
}

class JdkHttpPost : HttpPost {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun post(uri: URI, headers: Map<String, String>, body: String): InboundHttp {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.header("User-Agent", "trading-agent-observation")
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        val retryAfter = response.headers().firstValue("retry-after").orElse(null)?.trim()?.toLongOrNull()
        val responseBody = response.body().orEmpty()
        return InboundHttp(
            status = response.statusCode(),
            retryAfterSeconds = retryAfter,
            body = if (responseBody.length > MAX_BODY_CHARS) "" else responseBody,
        )
    }

    private companion object {
        const val MAX_BODY_CHARS: Int = 2_000_000
    }
}
