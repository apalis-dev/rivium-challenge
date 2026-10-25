package io.github.kdbrian.rivapln.todoradar.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Talks to the engine's localhost API. Every request carries the per-launch [token]. */
class HttpRadarApi(baseUrl: String, token: String) : RadarApi {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client = HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            connectTimeoutMillis = 2_000
            requestTimeoutMillis = 30_000
        }
        defaultRequest {
            url(baseUrl)
            header("X-Radar-Token", token)
        }
    }

    private suspend inline fun <reified T> HttpResponse.ok(): T {
        if (status.isSuccess()) return body()
        val text = bodyAsText()
        // Our 4xx bodies are {"error": ...}; rivium-http's safe envelope uses "description".
        val message = runCatching {
            val obj = json.parseToJsonElement(text) as JsonObject
            (obj["error"] ?: obj["description"])?.jsonPrimitive?.content
        }.getOrNull() ?: status.description
        throw ApiException(status.value, message)
    }

    override suspend fun health(): Boolean =
        runCatching { client.get("health").status.isSuccess() }.getOrDefault(false)

    override suspend fun authStatus(): AuthStatus = client.get("auth/status").ok()
    override suspend fun startSignIn(): AuthStatus = client.post("auth/device").ok()
    override suspend fun signOut() {
        client.post("auth/logout").let { if (!it.status.isSuccess()) it.ok<Unit>() }
    }

    override suspend fun repos(query: String): List<Repo> =
        client.get("repos") { parameter("q", query) }.ok()

    override suspend fun startScan(owner: String, repo: String): Scan =
        client.post("scans") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("owner", owner); put("repo", repo) })
        }.ok()

    override suspend fun startZipScan(path: String): Scan =
        client.post("scans") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("zip_path", path) })
        }.ok()

    override suspend fun scans(activeOnly: Boolean): List<Scan> =
        client.get("scans") { if (activeOnly) parameter("active", true) }.ok()

    override suspend fun scan(id: Long): Scan = client.get("scans/$id").ok()

    override suspend fun todos(id: Long, onlyNew: Boolean): List<Todo> =
        client.get("scans/$id/todos") { if (onlyNew) parameter("filter", "new") }.ok()

    override suspend fun report(id: Long): String = client.get("scans/$id/report").let {
        if (it.status.isSuccess()) it.bodyAsText() else it.ok()
    }

    override suspend fun peek(id: Long, path: String, line: Long, context: Int): Peek =
        client.get("scans/$id/peek") {
            parameter("path", path)
            parameter("line", line)
            parameter("context", context)
        }.ok()

    override suspend fun keepFiles(id: Long): Scan = client.post("scans/$id/keep").ok()
    override suspend fun deleteFiles(id: Long): Scan = client.post("scans/$id/cleanup").ok()

    fun close() = client.close()
}
