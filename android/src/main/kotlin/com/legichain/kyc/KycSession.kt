package com.legichain.kyc

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Configure once with a Legichain API token. No customer backend is needed. */
public class KycOptions(
    public val apiToken: String,
    public val baseUrl: String = "https://api.legichain.com",
    public val language: String = "tr",
    public val application: JSONObject = JSONObject(),
) {
    init {
        require(apiToken.isNotBlank()) { "apiToken is required" }
        require(language in setOf("tr","en")) { "language must be tr or en" }
        require(baseUrl.startsWith("https://")) { "HTTPS is required" }
    }
    override fun toString(): String = "KycOptions(language=$language)"
}

internal class EvidenceFailed(val step: String): Exception("Evidence processing failed")

internal class KycSession(private val options: KycOptions) {
    private var base = options.baseUrl.trimEnd('/')
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(45,TimeUnit.SECONDS).build()
    var applicationId = ""; private set
    private var token = ""
    private val operations = mutableListOf<String>()
    private val creationKey = UUID.randomUUID().toString()
    private val completed = mutableMapOf<String,String>()
    private val pending = mutableMapOf<String, Pair<String,String>>()

    suspend fun create(): JSONObject {
        val response = request("POST", "/v1/kyc/applications", options.application, creationKey)
        applicationId = response.getString("application_id")
        token = response.getString("client_token")
        return status()
    }
    suspend fun status(): JSONObject = request("GET", "/v1/kyc/applications/$applicationId/status")
    suspend fun challenge(): JSONObject = request("POST", "/v1/kyc/applications/$applicationId/liveness/challenge",
        JSONObject().put("length",3).put("ttl_seconds",120))

    suspend fun evidence(step: String, body: JSONObject, slot: String = step) {
        require(step in setOf("documents","selfie","liveness","nfc"))
        // A retry of the same capture retains its exact key and bytes. A
        // deliberate recapture gets a new key; never change a body under a key.
        val encoded = body.toString()
        val hash=java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
        if(completed[slot]==hash) return
        val previous = pending[slot]
        val key = if(previous?.second == encoded) previous.first else UUID.randomUUID().toString()
        pending[slot] = key to encoded
        val receipt = request("POST", "/v2/kyc/applications/$applicationId/$step", body,key)
        val operation = receipt.getString("operation_id")
        if(operation !in operations) operations.add(operation)
        val deadline = android.os.SystemClock.elapsedRealtime()+180_000
        while(true) {
            val status = request("GET","/v2/operations/$operation")
            when(status.getString("status")) {
                "completed" -> {
                    pending.remove(slot)
                    val result=status.optJSONObject("result")
                    val capture=result?.optJSONObject("capture") ?: result
                    if(capture?.has("iqa_passed")==true && !capture.optBoolean("iqa_passed")) throw EvidenceFailed(step)
                    completed[slot]=hash; return
                }
                "failed","expired","cancelled" -> { pending.remove(slot);throw EvidenceFailed(step) }
            }
            if(android.os.SystemClock.elapsedRealtime() >= deadline) error("processing_timeout")
            delay(1000)
        }
    }

    suspend fun submit(): JSONObject {
        // Every evidence() call already awaited durable processing. Never
        // treat the 202 upload receipt as the final application submission.
        val status = status()
        if(status.optString("state") in setOf("deciding","approved","rejected","manual_review"))
            return JSONObject().put("application_id",applicationId).put("status","submitted")
        request("POST", "/v1/kyc/applications/$applicationId/submit",JSONObject())
        return JSONObject().put("application_id",applicationId).put("status","submitted")
    }

    private suspend fun request(method: String,path: String,body: JSONObject? = null,key: String? = null): JSONObject {
        var redirected = false
        var attempts = 0
        while(true) {
            val builder = Request.Builder().url(base+path).header("Authorization","Bearer ${options.apiToken}")
                .header("Accept","application/json")
            if(token.isNotEmpty()) builder.header("X-KYC-Client-Token",token)
            if(key != null) builder.header("Idempotency-Key",key)
            builder.method(method,if(method == "GET") null else (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType()))
            val response = try { await(http.newCall(builder.build())) } catch(e: IOException) {
                // Unkeyed POSTs (especially challenge consumption/submit) are
                // reconciled by the flow instead of silently repeated.
                if((method=="GET" || key!=null) && attempts++<2) { delay(1000);continue }
                throw e
            }
            response.use {
                val raw = it.body?.string().orEmpty()
                val value = runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
                if(it.isSuccessful) return value
                if(it.code==421 && !redirected) {
                    val next=value.optString("api_base_url").trimEnd('/')
                    require(next in setOf("https://tr-api.legichain.com","https://eu-api.legichain.com")) { "invalid_region_target" }
                    base=next;redirected=true
                } else if(it.code in setOf(429,502,503,504) && (method=="GET" || key!=null) && attempts++<2) {
                    delay(((it.header("Retry-After")?.toLongOrNull() ?: 1).coerceIn(1,10))*1000)
                } else throw KYCException("HTTP ${it.code}",value.optString("code","HTTP_${it.code}"))
            }
        }
    }
    private suspend fun await(call: Call): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call,e: IOException) { if(continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call,response: Response) {
                if(continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }
    fun close() { http.dispatcher.cancelAll();token="";pending.clear();completed.clear() }
}
