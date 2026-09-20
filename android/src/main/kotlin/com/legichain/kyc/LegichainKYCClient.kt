// LegichainKYCClient.kt
//
// Faz G-7.5 — Android reference client for the Legichain KYC pipeline.
//
// Mirrors the iOS Swift skeleton; uses Google ML Kit for pre-flight
// quality / MRZ detection and OkHttp + Kotlin coroutines for the HTTP
// layer. The actual on-device OCR / quality gate is shown as a pure
// reference — production hosts will plug in their own camera pipeline.

package com.legichain.kyc

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class KYCException(message: String, val code: String? = null) : Exception(message)

/**
 * Pre-flight quality gate. Returns null when the bitmap is good enough
 * to upload; otherwise a short rejection reason the SDK host can show
 * the user.
 */
object ImageQualityGate {
    private const val BLUR_THRESHOLD = 100.0

    fun evaluate(bitmap: Bitmap): String? {
        if (bitmap.width < 800 || bitmap.height < 600) {
            return "image_too_small"
        }
        val laplacianVariance = computeLaplacianVariance(bitmap)
        if (laplacianVariance < BLUR_THRESHOLD) {
            return "image_blurred"
        }
        return null
    }

    /**
     * Reference Laplacian variance — production hosts should use
     * RenderScript / GPU shader for per-frame throughput. This pure-
     * Kotlin impl samples 512 random pixels and computes variance
     * across a 3x3 Laplacian neighbourhood.
     */
    private fun computeLaplacianVariance(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        val samples = mutableListOf<Double>()
        val rng = java.util.Random()
        repeat(512) {
            val x = 1 + rng.nextInt(w - 2)
            val y = 1 + rng.nextInt(h - 2)
            val center = luma(bitmap.getPixel(x, y))
            val sumNeighbours = (
                luma(bitmap.getPixel(x - 1, y)) +
                    luma(bitmap.getPixel(x + 1, y)) +
                    luma(bitmap.getPixel(x, y - 1)) +
                    luma(bitmap.getPixel(x, y + 1))
                )
            val lap = 4 * center - sumNeighbours
            samples.add(lap)
        }
        val mean = samples.average()
        return samples.sumOf { (it - mean) * (it - mean) } / samples.size
    }

    private fun luma(rgb: Int): Double {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}

/**
 * MRZ detector — surfaces concatenated MRZ lines when the bitmap
 * contains a TD1 (3×30) or TD2/TD3 (2×36 / 2×44) block of OCR-B chars.
 */
object MrzDetector {
    private val recognizer = TextRecognition.getClient(
        TextRecognizerOptions.DEFAULT_OPTIONS,
    )

    suspend fun detect(bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = recognizer.process(image).await()
        val mrzLines = text.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val stripped = line.text.replace(" ", "")
                if (stripped.length < 30) return@mapNotNull null
                if (!stripped.all { it.isUpperCase() || it.isDigit() || it == '<' }) {
                    return@mapNotNull null
                }
                stripped
            }
        if (mrzLines.size >= 2) mrzLines.joinToString("\n") else null
    }
}

data class KYCConfig(
    val baseUrl: String,
    val tenantBearer: String,
    val httpClient: OkHttpClient = OkHttpClient(),
)

data class CreateApplicationRequest(
    val documentTypeAllowed: List<String>,
    val nfcRequired: Boolean = false,
    val subjectExternalId: String? = null,
    val personaId: String? = null,
    val intent: String = "onboarding",
    val callbackUrl: String? = null,
    val claimedFullName: String? = null,
    val claimedPersonalNumber: String? = null,
    val claimedBirthDate: String? = null,
    val claimedExpiryDate: String? = null,
    val claimedDocumentNumber: String? = null,
    val claimedNationality: String? = null,    // alpha-3
    val claimedIssuingCountry: String? = null,
    val claimedSex: String? = null,            // "M" | "F"
    val claimedDocumentType: String? = null,
)

data class CreateApplicationResponse(
    val applicationId: String,
    val personaId: String,
    val personaCreated: Boolean,
    val clientToken: String,
    val state: String,
    val nextSteps: List<String>,
)

data class StatusResponse(
    val state: String,
    val currentStep: String,
    val retryAvailable: Boolean,
    val currentAttempt: Int,
    val maxAttempts: Int,
    val riskScore: Double?,
)

/**
 * Top-level KYC client. Single-use — instantiate per KYC session.
 */
class LegichainKYCClient(private val config: KYCConfig) {

    var applicationId: String? = null
        private set
    var clientToken: String? = null
        private set

    private val json = "application/json; charset=utf-8".toMediaType()
    private val isoFmt = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }

    // ── 1. Create application ──────────────────────────────────

    suspend fun createApplication(
        req: CreateApplicationRequest,
    ): CreateApplicationResponse = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("document_type_allowed", req.documentTypeAllowed)
            put("nfc_required", req.nfcRequired)
            put("intent", req.intent)
            req.subjectExternalId?.let { put("subject_external_id", it) }
            req.personaId?.let { put("persona_id", it) }
            req.callbackUrl?.let { put("callback_url", it) }
            req.claimedFullName?.let { put("claimed_full_name", it) }
            req.claimedPersonalNumber?.let { put("claimed_personal_number", it) }
            req.claimedBirthDate?.let { put("claimed_birth_date", it) }
            req.claimedExpiryDate?.let { put("claimed_expiry_date", it) }
            req.claimedDocumentNumber?.let { put("claimed_document_number", it) }
            req.claimedNationality?.let { put("claimed_nationality", it) }
            req.claimedIssuingCountry?.let { put("claimed_issuing_country", it) }
            req.claimedSex?.let { put("claimed_sex", it) }
            req.claimedDocumentType?.let { put("claimed_document_type", it) }
        }
        val resp = post("/v1/kyc/applications", body, useClientToken = false)
        val r = CreateApplicationResponse(
            applicationId = resp.getString("application_id"),
            personaId = resp.getString("persona_id"),
            personaCreated = resp.getBoolean("persona_created"),
            clientToken = resp.getString("client_token"),
            state = resp.getString("state"),
            nextSteps = (0 until resp.getJSONArray("next_steps").length())
                .map { resp.getJSONArray("next_steps").getString(it) },
        )
        applicationId = r.applicationId
        clientToken = r.clientToken
        r
    }

    // ── 2. Upload document ─────────────────────────────────────

    suspend fun uploadDocument(
        bitmap: Bitmap,
        side: String,
        documentType: String,
    ): JSONObject = withContext(Dispatchers.IO) {
        ImageQualityGate.evaluate(bitmap)?.let { reason ->
            throw KYCException("capture quality rejected: $reason",
                code = "iqa_fail")
        }
        if (side != "back" && documentType != "tr_id_card"
            && documentType != "driver_license"
            && MrzDetector.detect(bitmap) == null) {
            throw KYCException("MRZ not detected in image",
                code = "mrz_missing")
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val b64 = android.util.Base64.encodeToString(
            stream.toByteArray(), android.util.Base64.NO_WRAP,
        )
        val body = JSONObject().apply {
            put("document_type", documentType)
            put("side", side)
            put("mime_type", "image/jpeg")
            put("image_b64", b64)
            put("captured_at_client", isoFmt.format(Date()))
        }
        post(
            "/v1/kyc/applications/${requireApp()}/documents",
            body, useClientToken = true,
        )
    }

    // ── 3. Selfie + liveness (abbreviated; see MOBILE-SDK-GUIDE.md) ─

    suspend fun uploadSelfie(bitmap: Bitmap): JSONObject =
        withContext(Dispatchers.IO) {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            val b64 = android.util.Base64.encodeToString(
                stream.toByteArray(), android.util.Base64.NO_WRAP,
            )
            val body = JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("image_b64", b64)
                put("is_video", false)
            }
            post(
                "/v1/kyc/applications/${requireApp()}/selfie",
                body, useClientToken = true,
            )
        }

    // ── 4. Submit ──────────────────────────────────────────────

    suspend fun submit(): JSONObject = withContext(Dispatchers.IO) {
        post(
            "/v1/kyc/applications/${requireApp()}/submit",
            JSONObject(), useClientToken = true,
        )
    }

    // ── 5. Retry ───────────────────────────────────────────────

    suspend fun retry(reason: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                reason?.let { put("reason", it) }
            }
            post(
                "/v1/kyc/applications/${requireApp()}/retry",
                body, useClientToken = true,
            )
        }

    // ── 6. Status (polling) ────────────────────────────────────

    suspend fun status(): StatusResponse = withContext(Dispatchers.IO) {
        val obj = get("/v1/kyc/applications/${requireApp()}/status",
            useClientToken = false)
        StatusResponse(
            state = obj.getString("state"),
            currentStep = obj.getString("current_step"),
            retryAvailable = obj.getBoolean("retry_available"),
            currentAttempt = obj.getInt("current_attempt"),
            maxAttempts = obj.getInt("max_attempts"),
            riskScore = if (obj.isNull("risk_score")) null
                       else obj.getDouble("risk_score"),
        )
    }

    // ── 7. SSE event stream ────────────────────────────────────

    fun events(): Flow<StatusResponse> = callbackFlow {
        val factory = EventSources.createFactory(config.httpClient)
        val req = Request.Builder()
            .url(buildUrl("/v1/kyc/applications/${requireApp()}/events"))
            .headers(authHeaders(useClientToken = true))
            .header("Accept", "text/event-stream")
            .build()
        val source = factory.newEventSource(req, object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource, id: String?,
                type: String?, data: String,
            ) {
                runCatching {
                    val obj = JSONObject(data)
                    trySend(StatusResponse(
                        state = obj.getString("state"),
                        currentStep = obj.getString("current_step"),
                        retryAvailable = obj.getBoolean("retry_available"),
                        currentAttempt = obj.getInt("current_attempt"),
                        maxAttempts = obj.getInt("max_attempts"),
                        riskScore = if (obj.isNull("risk_score")) null
                                   else obj.getDouble("risk_score"),
                    ))
                }
            }
            override fun onClosed(eventSource: EventSource) { close() }
        })
        awaitClose { source.cancel() }
    }

    // ── HTTP plumbing ──────────────────────────────────────────

    private fun requireApp(): String =
        applicationId ?: throw KYCException(
            "Application not started", code = "missing_application",
        )

    private fun authHeaders(useClientToken: Boolean): Headers {
        val builder = Headers.Builder()
            .add("Authorization", "Bearer ${config.tenantBearer}")
        if (useClientToken) {
            clientToken?.let { builder.add("X-KYC-Client-Token", it) }
        }
        return builder.build()
    }

    private fun buildUrl(path: String): HttpUrl {
        return (config.baseUrl.trimEnd('/') + path).toHttpUrlOrNull()
            ?: throw KYCException("bad url")
    }

    private fun post(
        path: String, body: JSONObject, useClientToken: Boolean,
    ): JSONObject {
        val req = Request.Builder()
            .url(buildUrl(path))
            .headers(authHeaders(useClientToken))
            .post(body.toString().toRequestBody(json))
            .build()
        config.httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw KYCException("HTTP ${resp.code}: $text",
                    code = "http_${resp.code}")
            }
            return JSONObject(text)
        }
    }

    private fun get(path: String, useClientToken: Boolean): JSONObject {
        val req = Request.Builder()
            .url(buildUrl(path))
            .headers(authHeaders(useClientToken))
            .get()
            .build()
        config.httpClient.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw KYCException("HTTP ${resp.code}: $text",
                    code = "http_${resp.code}")
            }
            return JSONObject(text)
        }
    }
}
