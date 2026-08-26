package com.jax.assistant

import android.app.Application
import android.util.Log
import com.jax.assistant.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/** Application bootstrap for Firebase services used by the Android client. */
class JaxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        // Debug builds need the debug provider so Firebase AI Logic requests pass
        // App Check enforcement during local development. Register the printed
        // debug token in Firebase Console > App Check when first running the app.
        val appCheck = FirebaseAppCheck.getInstance()
        appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())

        // Trigger the SDK's own debug-secret log during debug startup. The public
        // Firebase API intentionally does not expose the generated secret to app
        // code; DebugAppCheckProvider logs it under its own tag. Nothing is stored
        // or logged by JAX, and this block is removed from release builds.
        if (BuildConfig.DEBUG) {
            appCheck.getAppCheckToken(false)
                .addOnSuccessListener {
                    Log.d("JAX-AppCheck", "Debug provider initialized; see DebugAppCheckProvider for the debug secret.")
                }
                .addOnFailureListener { error ->
                    Log.w("JAX-AppCheck", "Debug App Check token request failed: ${error.message}")
                }
        }
    }
}
