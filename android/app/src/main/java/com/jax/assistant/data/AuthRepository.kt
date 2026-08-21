package com.jax.assistant.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

// Google Sign-In -> Firebase Auth. Provides a stable uid so data survives reinstall and syncs across devices.
class AuthRepository(context: Context, webClientId: String) {

    private val auth = FirebaseAuth.getInstance()
    private val googleClient = GoogleSignIn.getClient(
        context.applicationContext,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId)
            .requestEmail()
            .build()
    )

    val currentUid: String? get() = auth.currentUser?.uid
    val currentEmail: String? get() = auth.currentUser?.email
    val isSignedIn: Boolean get() = auth.currentUser != null

    fun signInIntent(): Intent = googleClient.signInIntent

    // Completes sign-in from the Google intent result; onResult delivers the email or an error.
    fun completeSignIn(data: Intent?, onResult: (Result<String>) -> Unit) {
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken.isNullOrBlank()) {
                onResult(Result.failure(IllegalStateException("No ID token returned by Google")))
                return
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).addOnCompleteListener { task ->
                if (task.isSuccessful) onResult(Result.success(auth.currentUser?.email ?: "Signed in"))
                else onResult(Result.failure(task.exception ?: Exception("Firebase sign-in failed")))
            }
        } catch (e: Exception) {
            onResult(Result.failure(e))
        }
    }

    fun signOut(onDone: () -> Unit = {}) {
        auth.signOut()
        googleClient.signOut().addOnCompleteListener { onDone() }
    }
}
