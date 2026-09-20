package com.legichain.kyc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.tech.IsoDep
import android.util.Base64
import kotlinx.coroutines.*
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.icao.COMFile
import org.jmrtd.lds.icao.DG15File
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Real ISO-DEP + JMRTD BAC/PACE. Raw TLV bytes, including tags, go to Legichain. */
internal class ChipReader(private val activity: Activity) {
    @Volatile private var connection: IsoDep? = null
    private val adapter get() = NfcAdapter.getDefaultAdapter(activity)

    fun available(): Boolean = adapter?.isEnabled == true
    fun cancel() {
        runCatching { connection?.close() }
        connection = null
        activity.runOnUiThread { adapter?.disableReaderMode(activity) }
    }

    suspend fun read(mrz: Mrz, can: String? = null, progress: (String) -> Unit): JSONObject = withTimeout(60_000) {
        require(available()) { "nfc_unavailable" }
        try {
            suspendCancellableCoroutine { continuation ->
                val claimed = AtomicBoolean(false)
                continuation.invokeOnCancellation { cancel() }
                val callback = NfcAdapter.ReaderCallback { tag ->
                    if (claimed.compareAndSet(false, true)) {
                        CoroutineScope(continuation.context).launch(Dispatchers.IO) {
                            try {
                                val dep = IsoDep.get(tag) ?: error("unsupported_chip")
                                connection = dep
                                dep.timeout = 15_000
                                dep.connect()
                                val result = readChip(dep, mrz, can, progress)
                                if (continuation.isActive) continuation.resume(result)
                            } catch (error: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            }
                        }
                    }
                }
                activity.runOnUiThread {
                    if (continuation.isActive) {
                        adapter?.enableReaderMode(activity, callback,
                            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
                                NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
                    }
                }
            }
        } finally { cancel() }
    }

    private fun readChip(dep: IsoDep, mrz: Mrz, can: String?, progress: (String) -> Unit): JSONObject {
        val service = PassportService(CardService.getInstance(dep), 256, 224, false, true)
        try {
            service.open()
            val bac = BACKey(mrz.documentNumber, mrz.birth, mrz.expiry)
            val pace = try {
                CardAccessFile(service.getInputStream(PassportService.EF_CARD_ACCESS))
                    .securityInfos.filterIsInstance<PACEInfo>().firstOrNull()
            } catch (e: CardServiceException) {
                // File-not-found means a BAC-only chip. Transport/auth failures
                // must be retried, never silently downgraded to a different key.
                if (e.sw == 0x6A82 || e.sw == 0x6A86) null else throw e
            }
            progress("authenticating")
            val protocol: String
            if (pace != null) {
                val key = if (can != null) PACEKeySpec.createCANKey(can) else PACEKeySpec.createMRZKey(bac)
                service.doPACE(key, pace.objectIdentifier, PACEInfo.toParameterSpec(pace.parameterId), pace.parameterId)
                service.sendSelectApplet(true)
                protocol = "PACE"
            } else {
                require(can == null) { "can_requires_pace" }
                service.sendSelectApplet(false)
                service.doBAC(bac)
                protocol = "BAC"
            }
            val out = JSONObject().put("protocol", protocol).put("key_derivation", if(can == null) "MRZ" else "CAN")
            fun read(fid: Short): ByteArray = service.getInputStream(fid).use { stream ->
                val buffer=ByteArray(4096);val out=java.io.ByteArrayOutputStream()
                while(true) { val n=stream.read(buffer);if(n<0) break;require(out.size()+n<=1_000_000) { "invalid_chip_file" };out.write(buffer,0,n) }
                val bytes = out.toByteArray()
                require(bytes.isNotEmpty() && bytes.size <= 1_000_000) { "invalid_chip_file" }
                bytes
            }
            progress("sod")
            out.put("sod_b64", Base64.encodeToString(read(PassportService.EF_SOD),Base64.NO_WRAP))
            val present = COMFile(read(PassportService.EF_COM).inputStream()).tagList.toSet()
            val groups = listOf(Triple(1,0x61,PassportService.EF_DG1),Triple(2,0x75,PassportService.EF_DG2),
                Triple(7,0x67,PassportService.EF_DG7),Triple(11,0x6B,PassportService.EF_DG11),
                Triple(12,0x6C,PassportService.EF_DG12),Triple(13,0x6D,PassportService.EF_DG13),
                Triple(14,0x6E,PassportService.EF_DG14),Triple(15,0x6F,PassportService.EF_DG15))
            for ((number,tag,fid) in groups) {
                if (number <= 2 || tag in present) {
                    progress("dg$number")
                    out.put("dg${number}_b64",Base64.encodeToString(read(fid),Base64.NO_WRAP))
                }
            }
            if(out.has("dg15_b64")) {
                progress("active_authentication")
                val publicKey=DG15File(Base64.decode(out.getString("dg15_b64"),Base64.NO_WRAP).inputStream()).publicKey
                val challenge=ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
                val response=service.doAA(publicKey,null,null,challenge).response
                out.put("active_authentication_b64",Base64.encodeToString(response,Base64.NO_WRAP))
                out.put("device_attestation",JSONObject().put("aa_challenge_b64",Base64.encodeToString(challenge,Base64.NO_WRAP)))
            }
            return out
        } finally { runCatching { service.close() } }
    }
}
