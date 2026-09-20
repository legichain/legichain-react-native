package com.legichain.react

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.*
import com.facebook.react.ReactPackage
import com.facebook.react.uimanager.ViewManager
import com.legichain.kyc.KycOptions
import com.legichain.kyc.LegichainKyc
import org.json.JSONObject

class LegichainKycModule(private val context: ReactApplicationContext): ReactContextBaseJavaModule(context),ActivityEventListener {
    private var pending: Promise? = null
    init { context.addActivityEventListener(this) }
    override fun getName() = "LegichainKyc"
    @ReactMethod fun start(options: ReadableMap,promise: Promise) {
        val activity=currentActivity ?: run { promise.reject("NO_ACTIVITY","A foreground activity is required");return }
        if(pending!=null) { promise.reject("BUSY","A KYC flow is already running");return }
        try {
            val config=KycOptions(options.getString("apiToken").orEmpty(),options.getString("baseUrl") ?: "https://api.legichain.com",
                options.getString("language") ?: "tr",JSONObject(options.getMap("application")?.toHashMap() ?: emptyMap<String,Any>()))
            pending=promise
            activity.runOnUiThread {
                try { activity.startActivityForResult(LegichainKyc.intent(activity,config),REQUEST) }
                catch(e: Exception) { pending=null;promise.reject("START_FAILED","Could not open KYC") }
            }
        } catch(e: Exception) { pending=null;promise.reject("INVALID_OPTIONS","Invalid KYC options") }
    }
    override fun onActivityResult(activity: Activity,requestCode: Int,resultCode: Int,data: Intent?) {
        if(requestCode!=REQUEST) return
        val promise=pending ?: return;pending=null
        val value=JSONObject(LegichainKyc.result(data) ?: "{\"status\":\"cancelled\",\"application_id\":\"\"}")
        promise.resolve(Arguments.createMap().apply { putString("status",value.getString("status"));putString("application_id",value.optString("application_id")) })
    }
    override fun onNewIntent(intent: Intent) {}
    override fun invalidate() { pending?.reject("CANCELLED","React context closed");pending=null;context.removeActivityEventListener(this);super.invalidate() }
    companion object { const val REQUEST=46721 }
}

class LegichainKycPackage: ReactPackage {
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> = listOf(LegichainKycModule(context))
    override fun createViewManagers(context: ReactApplicationContext): List<ViewManager<*,*>> = emptyList()
}
