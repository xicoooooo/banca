package com.banca.players

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Says whose account a sign-in token is for, or null if it is nobody's. */
fun interface AccountVerifier {
    suspend fun verify(accessToken: String): Account?
}

/**
 * What signing in needs. [url] and [publicKey] are given to the browser, which
 * uses them to send the player to the provider; both are meant to be public.
 */
class SignInConfig(val url: String, val publicKey: String, val verifier: AccountVerifier)

/**
 * Accounts held by Supabase Auth. The browser signs in there and brings back
 * an access token, and the token is checked by asking Supabase whose it is.
 * That costs one request per sign-in and means no password, and no key that
 * could forge a token, ever reaches this server.
 */
class SupabaseAccounts(
    private val url: String,
    private val publicKey: String,
    private val http: HttpClient = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    },
) : AccountVerifier {

    override suspend fun verify(accessToken: String): Account? {
        val response = http.get("${url.trimEnd('/')}/auth/v1/user") {
            header("apikey", publicKey)
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        if (!response.status.isSuccess()) return null

        val user = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val id = user["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val details = user["user_metadata"] as? kotlinx.serialization.json.JsonObject
        val name = listOf("full_name", "name").firstNotNullOfOrNull { details?.get(it)?.jsonPrimitive?.contentOrNull }
        return Account(id, name)
    }
}
