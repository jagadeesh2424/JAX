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
import com.google.firebase.firestore.DocumentReference
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
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Local-first Firebase mirror. Credentials, embeddings, and local agent audit records never leave the device.
//
// Sync is a three-way merge per record: local vs. remote vs. the content fingerprint both sides
// agreed on at the last successful sync ("base"). That detects edits and deletions on either side
// without needing updatedAt columns on every table:
//   only local changed  -> push (upload or delete remote)
//   only remote changed -> pull (write or delete local)
//   both changed        -> the local device wins, unless it deleted a record the other side edited
class FirebaseSyncManager(private val database: AppDatabase, context: Context) {
    private val appContext = context.applicationContext
    private val firestore = FirebaseFirestore.getInstance()
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val collections: List<SyncCollection<*>> = listOf(
        SyncCollection("tasks", { database.taskDao().getAllTasksList() }, { it.id }, this::taskMap, this::taskFrom,
            { database.taskDao().insertTask(it) }, { database.taskDao().deleteTask(it) }),
        SyncCollection("facts", { database.factDao().getAllFactsList() }, { it.id }, this::factMap, this::factFrom,
            { database.factDao().insertFact(it) }, { database.factDao().deleteFact(it) }),
        SyncCollection("note_pages", { database.noteDao().getAllPagesList() }, { it.id }, this::pageMap, this::pageFrom,
            { database.noteDao().insertPage(it) }, { database.noteDao().deletePage(it) }),
        SyncCollection("note_blocks", { database.noteDao().getAllBlocksList() }, { it.id }, this::blockMap, this::blockFrom,
            { database.noteDao().insertBlock(it) }, { database.noteDao().deleteBlock(it) }),
        SyncCollection("page_links", { database.noteDao().getAllPageLinksList() }, { it.id }, this::linkMap, this::linkFrom,
            { database.noteDao().insertPageLink(it) }, { database.noteDao().deleteLinkBetween(it.fromPageId, it.toPageId) }),
        SyncCollection("goals", { database.goalDao().getAllGoalsList() }, { it.id }, this::goalMap, this::goalFrom,
            { database.goalDao().insertGoal(it) }, { database.goalDao().deleteGoal(it) }),
        SyncCollection("projects", { database.projectDao().getAllProjectsList() }, { it.id }, this::projectMap, this::projectFrom,
            { database.projectDao().insertProject(it) }, { database.projectDao().deleteProject(it) }),
        SyncCollection("habits", { database.habitDao().getAllHabitsList() }, { it.id }, this::habitMap, this::habitFrom,
            { database.habitDao().insertHabit(it) }, { database.habitDao().deleteHabit(it) }),
        SyncCollection("chat_messages", { database.chatMessageDao().getAll() }, { it.id }, this::chatMap, this::chatFrom,
            { database.chatMessageDao().insert(it) }, { database.chatMessageDao().delete(it.id) })
    )

    fun sync(uid: String): String {
        require(uid.isNotBlank()) { "Sign in before syncing." }
        prefs.edit().putString(KEY_PENDING_UID, uid).apply()
        enqueueOneTimeSync(appContext)
        return "Sync queued. It will run when a network is available."
    }

    suspend fun flush(uid: String) {
        require(uid.isNotBlank()) { "Sign in before syncing." }
        val root = firestore.collection("users").document(uid)
        collections.forEach { it.merge(root, uid) }
        prefs.edit().remove(KEY_PENDING_UID).apply()
    }

    fun pendingUid(): String? = prefs.getString(KEY_PENDING_UID, null)

    fun cancelPending() {
        prefs.edit().remove(KEY_PENDING_UID).apply()
        WorkManager.getInstance(appContext).cancelUniqueWork(ONE_TIME_WORK_NAME)
    }

    private inner class SyncCollection<T>(
        private val name: String,
        private val loadLocal: suspend () -> List<T>,
        private val idOf: (T) -> String,
        private val toMap: (T) -> Map<String, Any?>,
        private val fromMap: (String, Map<String, Any?>) -> T,
        private val upsertLocal: suspend (T) -> Unit,
        private val deleteLocal: suspend (T) -> Unit
    ) {
        suspend fun merge(root: DocumentReference, uid: String) {
            val remoteRef = root.collection(name)
            val local = loadLocal().associateBy(idOf)
            val remote = remoteRef.get().await().documents
                .mapNotNull { doc -> doc.data?.let { doc.id to fromMap(doc.id, it) } }
                .toMap()
            val base = loadBase(uid)
            val nextBase = HashMap<String, String>()
            val remoteWrites = mutableListOf<Pair<String, T?>>()

            for (id in local.keys + remote.keys + base.keys) {
                val l = local[id]
                val r = remote[id]
                val lHash = l?.let { fingerprint(toMap(it)) }
                val rHash = r?.let { fingerprint(toMap(it)) }
                val b = base[id]
                val keepLocal = when {
                    lHash == rHash -> null
                    rHash == b -> true
                    lHash == b -> false
                    else -> l != null || r == null
                }
                when (keepLocal) {
                    true -> remoteWrites += id to l
                    false -> if (r != null) upsertLocal(r) else l?.let { deleteLocal(it) }
                    null -> Unit
                }
                val winner = when (keepLocal) { true -> lHash; false -> rHash; null -> lHash }
                if (winner != null) nextBase[id] = winner
            }

            remoteWrites.chunked(BATCH_SIZE).forEach { chunk ->
                val batch = firestore.batch()
                chunk.forEach { (id, value) ->
                    val doc = remoteRef.document(id)
                    if (value == null) batch.delete(doc) else batch.set(doc, toMap(value))
                }
                batch.commit().await()
            }
            saveBase(uid, nextBase)
        }

        private fun baseKey(uid: String) = "$KEY_BASE_PREFIX$uid:$name"

        private fun loadBase(uid: String): Map<String, String> {
            val json = prefs.getString(baseKey(uid), null) ?: return emptyMap()
            return runCatching {
                val obj = JSONObject(json)
                obj.keys().asSequence().associateWith { obj.getString(it) }
            }.getOrDefault(emptyMap())
        }

        private fun saveBase(uid: String, base: Map<String, String>) {
            prefs.edit().putString(baseKey(uid), JSONObject(base).toString()).apply()
        }
    }

    // Stable content fingerprint: independent of map ordering and of Firestore's Long-vs-Int widening.
    private fun fingerprint(values: Map<String, Any?>): String {
        val canonical = values.toSortedMap().entries.joinToString("\u001F") { (key, value) ->
            "$key=" + when (value) {
                is Number -> value.toLong().toString()
                else -> value.toString()
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
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
        // Firestore allows 500 writes per batch; stay safely below it.
        private const val BATCH_SIZE = 400
        private const val PREFS_NAME = "jax_firebase_sync"
        private const val KEY_BASE_PREFIX = "sync_base:"
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