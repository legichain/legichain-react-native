package com.legichain.kyc

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/** Ready-to-use native KYC. Result is submitted/cancelled, never an approval. */
public object LegichainKyc {
    public fun intent(context: Context, options: KycOptions): Intent = Intent(context,KycActivity::class.java)
        .putExtra("apiToken",options.apiToken).putExtra("baseUrl",options.baseUrl)
        .putExtra("language",options.language).putExtra("application",options.application.toString())
    public fun result(data: Intent?): String? = data?.getStringExtra("result")
}

public class KycActivity: ComponentActivity() {
    private lateinit var session: KycSession
    private lateinit var text: KycText
    private lateinit var title: TextView
    private lateinit var hint: TextView
    private lateinit var button: Button
    private lateinit var panel: LinearLayout
    private lateinit var preview: PreviewView
    private lateinit var guide: GuideView
    private lateinit var cameraBox: FrameLayout
    private lateinit var chip: ChipReader
    private val cameraExecutor=Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider?=null
    private val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val detector=FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).enableTracking().build())
    private var state="welcome"
    private var busy=false
    private var analysing=false
    private var frontCamera=false
    private var latest: Bitmap?=null
    private var mrz: Mrz?=null
    private var stableSince=0L
    private var stableMrz=""
    private var document="tr_id_card"
    private var side="front"
    private var config=JSONObject()
    private var challenge: JSONObject?=null
    private var active: ActiveChallenge?=null
    private var liveStart=0L
    private var trackedFace: Int?=null
    private val frames=mutableMapOf<Int,MutableMap<Int,JSONObject>>()
    private var selfieBody: JSONObject?=null
    private var lastSample=0L
    private var job: Job?=null
    private val permission: androidx.activity.result.ActivityResultLauncher<String> = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if(granted) chooseDocument() else errorScreen("permission") { requestCameraPermission() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options=KycOptions(intent.getStringExtra("apiToken").orEmpty(),
            intent.getStringExtra("baseUrl") ?: "https://api.legichain.com",
            intent.getStringExtra("language") ?: "tr",JSONObject(intent.getStringExtra("application") ?: "{}"))
        text=KycText(options.language);session=KycSession(options);chip=ChipReader(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        buildScreen()
        onBackPressedDispatcher.addCallback(this) { finishWith("cancelled") }
        if(savedInstanceState!=null) {
            setResult(Activity.RESULT_CANCELED,Intent().putExtra("result",JSONObject().put("status","cancelled")
                .put("application_id",savedInstanceState.getString("applicationId","")).toString()))
            finish();return
        }
        title.text=text["welcome"];hint.text=text["intro"]
        button.text=text["start"];button.setOnClickListener { begin() }
    }

    private fun buildScreen() {
        val density=resources.displayMetrics.density
        fun dp(value:Int)=(value*density).toInt()
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;isFocusableInTouchMode=true;setPadding(dp(24),dp(16),dp(24),dp(12));setBackgroundColor(Color.rgb(13,23,39)) }
        val brand=TextView(this).apply { text="LEGICHAIN";setTextColor(Color.rgb(116,224,197));textSize=14f;letterSpacing=.2f }
        title=TextView(this).apply { setTextColor(Color.WHITE);textSize=26f;setPadding(0,20,0,12) }
        hint=TextView(this).apply { setTextColor(Color.rgb(191,205,222));textSize=17f;minHeight=110;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
        cameraBox=FrameLayout(this)
        preview=PreviewView(this).apply { scaleType=PreviewView.ScaleType.FILL_CENTER }
        guide=GuideView(this)
        cameraBox.addView(preview,FrameLayout.LayoutParams(-1,-1));cameraBox.addView(guide,FrameLayout.LayoutParams(-1,-1))
        cameraBox.visibility=View.GONE
        panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER }
        button=Button(this).apply {
            minHeight=dp(52);textSize=17f;isAllCaps=false;setTextColor(Color.rgb(13,23,39))
            background=android.graphics.drawable.GradientDrawable().apply { setColor(Color.rgb(116,224,197));cornerRadius=dp(14).toFloat() }
        }
        val cancel=Button(this).apply { isAllCaps=false;setTextColor(Color.rgb(191,205,222));setBackgroundColor(Color.TRANSPARENT);text=this@KycActivity.text["cancel"];setOnClickListener { finishWith("cancelled") } }
        root.addView(brand);root.addView(title);root.addView(hint)
        root.addView(cameraBox,LinearLayout.LayoutParams(-1,0,1f));root.addView(panel,LinearLayout.LayoutParams(-1,-2))
        root.addView(button,LinearLayout.LayoutParams(-1,-2));root.addView(cancel,LinearLayout.LayoutParams(-1,-2))
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(dp(24)+bars.left,dp(16)+bars.top,dp(24)+bars.right,dp(12)+bars.bottom)
            insets
        }
        setContentView(root)
        root.requestFocus()
    }

    private fun begin() { runStep({ begin() }) { config=session.create();permission.launch(Manifest.permission.CAMERA) } }
    private fun requestCameraPermission() { permission.launch(Manifest.permission.CAMERA) }
    private fun chooseDocument() {
        state="select";busy=false;cameraBox.visibility=View.GONE;panel.removeAllViews()
        title.text=text["select"];hint.text="";button.visibility=View.GONE
        val allowed=config.getJSONArray("document_type_allowed")
        for(i in 0 until allowed.length()) {
            val kind=allowed.getString(i)
            panel.addView(Button(this).apply { text=this@KycActivity.text[kind];setOnClickListener {
                document=kind;mrz=null;side=if(kind in setOf("passport","uk_passport")) "single" else "front";documentScreen()
            } })
        }
    }
    private fun documentScreen() {
        state="document";busy=false;latest=null;stableSince=0;stableMrz="";panel.removeAllViews()
        title.text=text[side];hint.text=text["frame"];cameraBox.visibility=View.VISIBLE
        guide.mode="document";guide.invalidate();button.visibility=View.VISIBLE;button.isEnabled=false;button.text=text["capture"]
        button.setOnClickListener { latest?.let { bitmap ->
            // For chip-required documents, a checksum-valid MRZ is mandatory
            // before leaving the back/passport capture step.
            if(side!="front" && config.optBoolean("nfc_required") && mrz==null) return@setOnClickListener
            uploadDocument(bitmap)
        } }
        camera(false)
    }
    private fun uploadDocument(bitmap: Bitmap) {
        if(busy) return
        val body=JSONObject().put("document_type",document).put("side",side).put("mime_type","image/jpeg").put("image_b64",jpeg(bitmap,88))
        runStep({ uploadDocument(bitmap) }) {
            session.evidence("documents",body,"document:$side")
            if(side=="front") { side="back";documentScreen() } else if(config.optBoolean("nfc_required")) nfcScreen() else afterChip()
        }
    }
    private fun nfcScreen() {
        state="nfc";busy=false;provider?.unbindAll();preview.visibility=View.GONE;cameraBox.visibility=View.VISIBLE
        guide.mode="nfc";guide.invalidate();title.text=text["nfc"];hint.text=text["nfc_hint"]
        panel.removeAllViews();val can=EditText(this).apply { setTextColor(Color.WHITE);setHintTextColor(Color.GRAY);hint=this@KycActivity.text["can"];inputType=2 }
        panel.addView(can);button.visibility=View.VISIBLE;button.isEnabled=true;button.text=text["nfc_read"]
        button.setOnClickListener {
            if(!chip.available()) { hint.text=text["nfc_missing"];return@setOnClickListener }
            val parsed=mrz ?: run { documentScreen();return@setOnClickListener }
            val canText=can.text.toString().trim().ifEmpty { null }
            if(canText!=null && !canText.matches(Regex("[0-9]{6}"))) { can.error=text["can"];return@setOnClickListener }
            runStep({ nfcScreen() }) {
                hint.text=text["nfc_busy"]
                val body=chip.read(parsed,canText) { }
                uploadNfc(body)
            }
        }
    }
    private fun uploadNfc(body: JSONObject) { runStep({ uploadNfc(body) }) { session.evidence("nfc",body);afterChip() } }
    private fun uploadLiveness(body: JSONObject) { runStep({ uploadLiveness(body) }) { session.evidence("liveness",body);frames.clear();submitScreen() } }
    private fun afterChip() {
        if(config.optBoolean("liveness_required",true) || config.optBoolean("face_match_required",true)) selfieScreen()
        else submitScreen()
    }
    private fun selfieScreen() {
        state="selfie";busy=false;stableSince=0;panel.removeAllViews();preview.visibility=View.VISIBLE
        cameraBox.visibility=View.VISIBLE;guide.mode="face";guide.invalidate()
        title.text=text["selfie"];hint.text=text["neutral"];button.visibility=View.GONE;camera(true)
    }
    private fun uploadSelfie(bitmap: Bitmap) {
        if(busy) return
        selfieBody=JSONObject().put("mime_type","image/jpeg").put("image_b64",jpeg(bitmap,88))
        runStep({ uploadSelfie(bitmap) }) {
            session.evidence("selfie",selfieBody!!)
            if(config.optBoolean("liveness_required",true)) beginActive() else {
                // Existing backend requires a liveness entry for the
                // face-match-only path; passive evidence supplies that entry.
                session.evidence("liveness",JSONObject().put("mode","passive")
                    .put("frame_b64",selfieBody!!.getString("image_b64")).put("frame_mime_type","image/jpeg"))
                submitScreen()
            }
        }
    }
    private suspend fun beginActive() {
        challenge=session.challenge();val sequence=challenge!!.getJSONArray("sequence")
        liveStart=SystemClock.elapsedRealtime();active=ActiveChallenge((0 until sequence.length()).map { sequence.getString(it) },liveStart)
        frames.clear();trackedFace=null;lastSample=0;state="active";busy=false
        button.visibility=View.GONE;title.text=text["active"];hint.text=text["prepare"]
    }
    private fun submitScreen() {
        runStep({ submitScreen() }) { session.submit();state="submitted";provider?.unbindAll();cameraBox.visibility=View.GONE;panel.removeAllViews()
            busy=false;title.text=text["submitted"];hint.text=text["submitted_hint"];button.visibility=View.VISIBLE;button.text=text["done"];button.isEnabled=true
            button.setOnClickListener { finishWith("submitted") }
        }
    }

    @Suppress("DEPRECATION")
    private fun camera(front: Boolean) {
        frontCamera=front;preview.visibility=View.VISIBLE
        val future=ProcessCameraProvider.getInstance(this)
        future.addListener({
            val viewport=preview.viewPort ?: run { preview.post { if(state in setOf("document","selfie","active")) camera(front) };return@addListener }
            provider=future.get();provider!!.unbindAll()
            val cameraPreview=Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysis=ImageAnalysis.Builder().setTargetResolution(Size(1280,960))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(cameraExecutor) { frame ->
                if(busy || analysing || state !in setOf("document","selfie","active")) { frame.close();return@setAnalyzer }
                analysing=true
                val bitmap=try {
                    val raw=frame.toBitmap();val crop=frame.cropRect
                    val matrix=Matrix().apply { postRotate(frame.imageInfo.rotationDegrees.toFloat());if(front) postScale(-1f,1f) }
                    Bitmap.createBitmap(raw,crop.left,crop.top,crop.width(),crop.height(),matrix,true)
                } catch(e: Exception) { frame.close();analysing=false;return@setAnalyzer }
                frame.close()
                lifecycleScope.launch {
                    try { analyse(bitmap) } catch(e: CancellationException) { throw e }
                    catch(e: Exception) { hint.text=text["error"] } finally { analysing=false }
                }
            }
            val group=UseCaseGroup.Builder().setViewPort(viewport).addUseCase(cameraPreview).addUseCase(analysis).build()
            try { provider!!.bindToLifecycle(this,if(front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,group) }
            catch(e: Exception) { errorScreen("permission") { camera(front) } }
        },ContextCompat.getMainExecutor(this))
    }

    private suspend fun analyse(bitmap: Bitmap) {
        if(state=="document") {
            val result=recognizer.process(InputImage.fromBitmap(bitmap,0)).await()
            if(busy || state!="document") return
            val parsed=Mrz.parse(result.text)
            val detected=withContext(Dispatchers.Default) { if(captureQuality(bitmap)) DocumentBounds.detect(bitmap) else null }
            // Convert the visible FILL_CENTER frame to image pixels; MRZ text
            // must be wholly inside it, with padding to avoid clipped glyphs.
            val scale=maxOf(preview.width.toFloat()/bitmap.width,preview.height.toFloat()/bitmap.height)
            val dx=(preview.width-bitmap.width*scale)/2;val dy=(preview.height-bitmap.height*scale)/2
            val box=guide.rect();val mrzLines=result.textBlocks.flatMap { it.lines }.filter { it.text.replace(" ","").length>=30 }
            val documentInside=detected?.let { r -> box.contains(r.left*scale+dx-4,r.top*scale+dy-4,r.right*scale+dx+4,r.bottom*scale+dy+4) && r.width()*scale>box.width()*.6 } == true
            val quality=documentInside
            latest=if(quality) bitmap else null;button.isEnabled=quality
            val inside=mrzLines.isNotEmpty() && mrzLines.all { line -> line.boundingBox?.let { r ->
                box.contains(r.left*scale+dx-4,r.top*scale+dy-4,r.right*scale+dx+4,r.bottom*scale+dy+4)
            } ?: false }
            if(parsed!=null && quality && inside) {
                if(stableMrz!=parsed.text) { stableMrz=parsed.text;stableSince=SystemClock.elapsedRealtime() }
                mrz=parsed;hint.text=text["steady"]
                if(SystemClock.elapsedRealtime()-stableSince>900) uploadDocument(bitmap)
            } else { stableMrz="";stableSince=0;hint.text=text["frame"] }
            return
        }
        val faces=detector.process(InputImage.fromBitmap(bitmap,0)).await()
        if(busy || state !in setOf("selfie","active")) return
        val face=faces.singleOrNull()
        val scale=maxOf(preview.width.toFloat()/bitmap.width,preview.height.toFloat()/bitmap.height)
        val dx=(preview.width-bitmap.width*scale)/2;val dy=(preview.height-bitmap.height*scale)/2
        val faceInside=face?.boundingBox?.let { r -> guide.rect().contains(r.left*scale+dx,r.top*scale+dy,r.right*scale+dx,r.bottom*scale+dy) && r.width()>bitmap.width*.25 } == true
        val observation=face?.takeIf { faceInside }?.let { ActiveChallenge.Face(it.headEulerAngleY,it.headEulerAngleX,it.smilingProbability,it.leftEyeOpenProbability,it.rightEyeOpenProbability) }
        val now=SystemClock.elapsedRealtime()
        if(state=="selfie") {
            if(observation?.neutral()==true && face.boundingBox.width()>bitmap.width*.25) {
                if(stableSince==0L) stableSince=now
                if(now-stableSince>1000) uploadSelfie(bitmap)
            } else stableSince=0
            return
        }
        val engine=active ?: return
        val before=engine.phase;val index=engine.index
        if(face!=null && trackedFace==null) trackedFace=face.trackingId
        val sameFace=face!=null && (trackedFace==null || face.trackingId==trackedFace)
        engine.update(now,if(sameFace) observation else null)
        val samples=frames.getOrPut(index) { mutableMapOf() }
        val changed=before!=engine.phase
        if(sameFace && (changed || now-lastSample>600) && (before in setOf(ActiveChallenge.Phase.PERFORM,ActiveChallenge.Phase.RETURN) || engine.phase==ActiveChallenge.Phase.PERFORM)) {
            val sample=JSONObject().put("image_b64",jpeg(bitmap,75,640)).put("timestamp_ms",now-liveStart)
            // Reserve slots for baseline, peak and final return so a slow
            // action cannot evict the only frame showing the actual motion.
            val slot=when {
                engine.phase==ActiveChallenge.Phase.PERFORM && changed -> 0
                engine.phase==ActiveChallenge.Phase.RETURN && changed -> 3
                before==ActiveChallenge.Phase.RETURN && changed -> 5
                engine.phase==ActiveChallenge.Phase.RETURN -> 4
                samples.containsKey(1) -> 2
                else -> 1
            }
            samples[slot]=sample
            lastSample=now
        }
        title.text="${minOf(index+1,3)} / 3 · ${text[engine.action]}"
        hint.text=when(engine.phase) {
            ActiveChallenge.Phase.INSTRUCTION -> text["prepare"]
            ActiveChallenge.Phase.READY -> text["neutral"]
            ActiveChallenge.Phase.PERFORM -> text["go"]
            ActiveChallenge.Phase.RETURN -> text["return"]
            else -> text["busy"]
        }
        if(changed && engine.phase==ActiveChallenge.Phase.PERFORM) guide.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        if(engine.phase==ActiveChallenge.Phase.FAILED) {
            errorScreen("retry_live") { runStep({ selfieScreen() }) { beginActive() } }
        } else if(engine.phase==ActiveChallenge.Phase.COMPLETE) {
            val actions=JSONArray(engine.records.map { JSONObject().put("action",it.action).put("started_at_ms",it.startedAtMs).put("ended_at_ms",it.endedAtMs) })
            val all=frames.values.flatMap { it.values }.sortedBy { it.getLong("timestamp_ms") }
            val body=JSONObject().put("mode","active").put("frame_b64",selfieBody!!.getString("image_b64"))
                .put("frame_mime_type","image/jpeg").put("challenge_token",challenge!!.getString("challenge_token"))
                .put("completed_actions",actions).put("frames",JSONArray(all))
            uploadLiveness(body)
        }
    }
    private fun captureQuality(bitmap: Bitmap): Boolean {
        // Deterministic sharpness/brightness sampling; never upload a black,
        // badly exposed or obviously blurred frame via manual capture.
        var sum=0.0;var squared=0.0;var light=0.0;var n=0
        fun luma(x:Int,y:Int): Double { val c=bitmap.getPixel(x,y);return Color.red(c)*.299+Color.green(c)*.587+Color.blue(c)*.114 }
        for(y in bitmap.height/3 until bitmap.height*2/3 step 8) for(x in bitmap.width/12 until bitmap.width*11/12 step 8) {
            val c=luma(x,y);val d=4*c-luma(x-1,y)-luma(x+1,y)-luma(x,y-1)-luma(x,y+1)
            sum+=d;squared+=d*d;light+=c;n++
        }
        return n>0 && light/n in 40.0..235.0 && squared/n-(sum/n)*(sum/n)>65
    }
    private fun jpeg(bitmap: Bitmap, quality: Int, maxWidth: Int=1600): String {
        val scaled=if(bitmap.width>maxWidth) Bitmap.createScaledBitmap(bitmap,maxWidth,bitmap.height*maxWidth/bitmap.width,true) else bitmap
        return ByteArrayOutputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG,quality,out);Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP) }
    }
    private fun runStep(retry: ()->Unit, work: suspend ()->Unit) {
        busy=true;button.isEnabled=false;hint.text=text["busy"]
        job=lifecycleScope.launch {
            try { work() } catch(e: CancellationException) { throw e }
            catch(e: EvidenceFailed) { errorScreen("error") {
                when(e.step) { "documents" -> documentScreen(); "selfie" -> selfieScreen(); "nfc" -> nfcScreen()
                    else -> if(config.optBoolean("liveness_required",true)) runStep({ selfieScreen() }) { beginActive() } else selfieScreen() }
            } }
            catch(e: Exception) { errorScreen("error",retry) }
        }
    }
    private fun errorScreen(key: String,retry: ()->Unit) {
        state="error";busy=false;hint.text=text[key];button.visibility=View.VISIBLE;button.isEnabled=true;button.text=text["retry"]
        button.setOnClickListener { retry() }
    }
    private fun finishWith(status: String) {
        setResult(if(status=="submitted") Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Intent().putExtra("result",JSONObject().put("status",status).put("application_id",session.applicationId).toString()))
        finish()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("applicationId",session.applicationId);super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        super.onStop();chip.cancel()
        if(state=="active") { active=null;frames.clear();errorScreen("retry_live") { runStep({ selfieScreen() }) { beginActive() } } }
    }
    override fun onDestroy() {
        job?.cancel();provider?.unbindAll();chip.cancel();session.close();recognizer.close();detector.close();cameraExecutor.shutdown()
        latest=null;selfieBody=null;frames.clear();super.onDestroy()
    }
}
