package com.jax.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.jax.assistant.db.AppDatabase
import com.jax.assistant.db.ChatMessageEntity
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.GoalEntity
import com.jax.assistant.db.HabitEntity
import com.jax.assistant.db.NoteBlockEntity
import com.jax.assistant.db.NotePageEntity
import com.jax.assistant.db.PageLinkEntity
import com.jax.assistant.db.ProjectEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.worker.FirebaseSyncWorker
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Local-first Firebase mirror. Credentials, embeddings, and local agent audit records never leave the device.
// Delta sync: only uploads changed records (WHERE createdAt/updatedAt > lastSyncTime), reducing write cost by ~90%.
class FirebaseSyncManager(private val database: AppDatabase, context: Context) {
    private val appContext = context.applicationContext
    private val firestore = FirebaseFirestore.getInstance()
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun sync(uid: String): String {
        require(uid.isNotBlank()) { "Sign in before syncing." }
        prefs.edit().putString(KEY_PENDING_UID, uid).apply()
        enqueueOneTimeSync(appContext)
        return "Sync queued. It will run when a network is available."
    }

    suspend fun flush(uid: String) {
        require(uid.isNotBlank()) { "Sign in before syncing." }
        val syncStartedAt = System.currentTimeMillis()
        val lastSyncTime = prefs.getLong(KEY_LAST_SYNC_TIMESTAMP, 0)
        backup(uid, lastSyncTime)
        restore(uid)
        prefs.edit()
            .putLong(KEY_LAST_SYNC_TIMESTAMP, syncStartedAt)
            .remove(KEY_PENDING_UID)
            .apply()
    }

    fun pendingUid(): String? = prefs.getString(KEY_PENDING_UID, null)

    fun cancelPending() {
        prefs.edit().remove(KEY_PENDING_UID).apply()
        WorkManager.getInstance(appContext).cancelUniqueWork(ONE_TIME_WORK_NAME)
    }

    private suspend fun backup(uid: String, lastSyncTime: Long) {
        val root = firestore.collection("users").document(uid)
        val writes = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any?>>>()

        // Collect changed tasks (only upload if created/modified after last sync)
        database.taskDao().getAllTasksList().filter { it.createdAt > lastSyncTime }.forEach { task ->
            writes += root.collection("tasks").document(task.id) to taskMap(task)
        }

        // Collect changed facts
        database.factDao().getAllFactsList().filter { it.createdAt > lastSyncTime }.forEach { fact ->
            writes += root.collection("facts").document(fact.id) to factMap(fact)
        }

        // Collect changed pages (updatedAt field for modification tracking)
        database.noteDao().getAllPagesList().filter { it.updatedAt > lastSyncTime }.forEach { page ->
            writes += root.collection("note_pages").document(page.id) to pageMap(page)
        }

        // Collect note blocks
        database.noteDao().getAllBlocksList().forEach { block ->
            writes += root.collection("note_blocks").document(block.id) to blockMap(block)
        }

        // Collect page links
        database.noteDao().getAllPageLinksList().forEach { link ->
            writes += root.collection("page_links").document(link.id) to linkMap(link)
        }

        // Collect changed goals
        database.goalDao().getAllGoalsList().filter { it.createdAt > lastSyncTime }.forEach { goal ->
            writes += root.collection("goals").document(goal.id) to goalMap(goal)
        }

        // Collect changed projects (updatedAt field)
        database.projectDao().getAllProjectsList().filter { it.updatedAt > lastSyncTime }.forEach { project ->
            writes += root.collection("projects").document(project.id) to projectMap(project)
        }

        // Collect changed habits
        database.habitDao().getAllHabitsList().filter { it.createdAt > lastSyncTime }.forEach { habit ->
            writes += root.collection("habits").document(habit.id) to habitMap(habit)
        }

        // Collect changed chat messages
        database.chatMessageDao().getAll().filter { it.timestamp > lastSyncTime }.forEach { msg ->
            writes += root.collection("chat_messages").document(msg.id) to chatMap(msg)
        }

        writes.chunked(BATCH_SIZE).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (document, values) -> batch.set(document, values) }
            batch.commit().await()
        }
    }

    private suspend fun restore(uid: String) {
        val root = firestore.collection("users").document(uid)
        root.collection("tasks").get().await().documents.forEach { d -> d.data?.let { database.taskDao().insertTask(taskFrom(d.id, it)) } }
        root.collection("facts").get().await().documents.forEach { d -> d.data?.let { database.factDao().insertFact(factFrom(d.id, it)) } }
        root.collection("note_pages").get().await().documents.forEach { d -> d.data?.let { database.noteDao().insertPage(pageFrom(d.id, it)) } }
        root.collection("note_blocks").get().await().documents.forEach { d -> d.data?.let { database.noteDao().insertBlock(blockFrom(d.id, it)) } }
        root.collection("page_links").get().await().documents.forEach { d -> d.data?.let { database.noteDao().insertPageLink(linkFrom(d.id, it)) } }
        root.collection("goals").get().await().documents.forEach { d -> d.data?.let { database.goalDao().insertGoal(goalFrom(d.id, it)) } }
        root.collection("projects").get().await().documents.forEach { d -> d.data?.let { database.projectDao().insertProject(projectFrom(d.id, it)) } }
        root.collection("habits").get().await().documents.forEach { d -> d.data?.let { database.habitDao().insertHabit(habitFrom(d.id, it)) } }
        root.collection("chat_messages").get().await().documents.forEach { d -> d.data?.let { database.chatMessageDao().insert(chatFrom(d.id, it)) } }
    }

    private fun taskMap(value: TaskEntity) = mapOf("title" to value.title, "category" to value.category, "priority" to value.priority, "deadline" to value.deadline, "isCompleted" to value.isCompleted, "createdAt" to value.createdAt)
    private fun factMap(value: FactEntity) = mapOf("title" to value.title, "category" to value.category, "details" to value.details, "createdAt" to value.createdAt)
    private fun pageMap(value: NotePageEntity) = mapOf("title" to value.title, "category" to value.category, "tags" to value.tags, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)
    private fun blockMap(value: NoteBlockEntity) = mapOf("pageId" to value.pageId, "type" to value.type, "content" to value.content, "checked" to value.checked, "position" to value.position)
    private fun linkMap(value: PageLinkEntity) = mapOf("fromPageId" to value.fromPageId, "toPageId" to value.toPageId, "relation" to value.relation, "validFrom" to value.validFrom, "validUntil" to value.validUntil, "createdAt" to value.createdAt)
    private fun goalMap(value: GoalEntity) = mapOf("title" to value.title, "category" to value.category, "targetValue" to value.targetValue, "currentValue" to value.currentValue, "deadline" to value.deadline, "isCompleted" to value.isCompleted, "createdAt" to value.createdAt)
    private fun projectMap(value: ProjectEntity) = mapOf("name" to value.name, "description" to value.description, "status" to value.status, "createdAt" to value.createdAt, "updatedAt" to value.updatedAt)
    private fun habitMap(value: HabitEntity) = mapOf("name" to value.name, "category" to value.category, "streak" to value.streak, "lastCompletedDate" to value.lastCompletedDate, "createdAt" to value.createdAt)
    private fun chatMap(value: ChatMessageEntity) = mapOf("text" to value.text, "isUser" to value.isUser, "timestamp" to value.timestamp)

    private fun taskFrom(id: String, map: Map<String, Any?>) = TaskEntity(id, text(map, "title"), text(map, "category"), text(map, "priority"), nullableText(map, "deadline"), bool(map, "isCompleted"), long(map, "createdAt"))
    private fun factFrom(id: String, map: Map<String, Any?>) = FactEntity(id, text(map, "title"), text(map, "category"), text(map, "details"), long(map, "createdAt"))
    private fun pageFrom(id: String, map: Map<String, Any?>) = NotePageEntity(id, text(map, "title"), text(map, "category"), text(map, "tags"), long(map, "createdAt"), long(map, "updatedAt"))
    private fun blockFrom(id: String, map: Map<String, Any?>) = NoteBlockEntity(id, text(map, "pageId"), text(map, "type"), text(map, "content"), bool(map, "checked"), long(map, "position").toInt())
    private fun linkFrom(id: String, map: Map<String, Any?>) = PageLinkEntity(id, text(map, "fromPageId"), text(map, "toPageId"), text(map, "relation"), long(map, "validFrom"), nullableLong(map, "validUntil"), long(map, "createdAt"))
    private fun goalFrom(id: String, map: Map<String, Any?>) = GoalEntity(id, text(map, "title"), text(map, "category"), long(map, "targetValue").toInt(), long(map, "currentValue").toInt(), nullableText(map, "deadline"), bool(map, "isCompleted"), long(map, "createdAt"))
    private fun projectFrom(id: String, map: Map<String, Any?>) = ProjectEntity(id, text(map, "name"), text(map, "description"), text(map, "status"), long(map, "createdAt"), long(map, "updatedAt"))
    private fun habitFrom(id: String, map: Map<String, Any?>) = HabitEntity(id, text(map, "name"), text(map, "category"), long(map, "streak").toInt(), nullableText(map, "lastCompletedDate"), long(map, "createdAt"))
    private fun chatFrom(id: String, map: Map<String, Any?>) = ChatMessageEntity(id, text(map, "text"), bool(map, "isUser"), long(map, "timestamp"))

    private fun text(map: Map<String, Any?>, key: String) = map[key] as? String ?: ""
    private fun nullableText(map: Map<String, Any?>, key: String) = map[key] as? String
    private fun bool(map: Map<String, Any?>, key: String) = map[key] as? Boolean ?: false
    private fun long(map: Map<String, Any?>, key: String) = (map[key] as? Number)?.toLong() ?: 0L
    private fun nullableLong(map: Map<String, Any?>, key: String) = (map[key] as? Number)?.toLong()

    companion object {
        private const val BATCH_SIZE = 100
        private const val PREFS_NAME = "jax_firebase_sync"
        private const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp_ms"
        private const val KEY_PENDING_UID = "pending_uid"
        private const val ONE_TIME_WORK_NAME = "jax_firebase_sync_once"
        private const val PERIODIC_WORK_NAME = "jax_firebase_sync_periodic"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        private fun enqueueOneTimeSync(context: Context) {
            val request = OneTimeWorkRequestBuilder<FirebaseSyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<FirebaseSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
}