package com.jax.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestoreException
import com.jax.assistant.data.FirebaseSyncManager
import com.jax.assistant.db.AppDatabase
import kotlinx.coroutines.CancellationException

class FirebaseSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val syncManager = FirebaseSyncManager(
            AppDatabase.getDatabase(applicationContext),
            applicationContext
        )
        val signedInUid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.success()
        val pendingUid = syncManager.pendingUid()
        if (pendingUid != null && pendingUid != signedInUid) return Result.failure()

        return try {
            syncManager.flush(signedInUid)
            Result.success()
        } catch (error: FirebaseFirestoreException) {
            when (error.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED,
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
                FirebaseFirestoreException.Code.INVALID_ARGUMENT -> Result.failure()
                else -> Result.retry()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.retry()
        }
    }
}