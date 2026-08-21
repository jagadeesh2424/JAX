package com.jax.assistant.data

import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QuerySnapshot
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Local-first Firebase mirror. Credentials, embeddings, and local agent audit records never leave the device.
class FirebaseSyncManager(private val database: AppDatabase) {
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun sync(uid: String): String {
        require(uid.isNotBlank()) { "Sign in before syncing." }
        backup(uid)
        restore(uid)
        return "Sync complete."
    }

    private suspend fun backup(uid: String) {
        val root = firestore.collection("users").document(uid)
        write(root, "tasks", database.taskDao().getAllTasksList().map { it.id to taskMap(it) })
        write(root, "facts", database.factDao().getAllFactsList().map { it.id to factMap(it) })
        write(root, "note_pages", database.noteDao().getAllPagesList().map { it.id to pageMap(it) })
        write(root, "note_blocks", database.noteDao().getAllBlocksList().map { it.id to blockMap(it) })
        write(root, "page_links", database.noteDao().getAllPageLinksList().map { it.id to linkMap(it) })
        write(root, "goals", database.goalDao().getAllGoalsList().map { it.id to goalMap(it) })
        write(root, "projects", database.projectDao().getAllProjectsList().map { it.id to projectMap(it) })
        write(root, "habits", database.habitDao().getAllHabitsList().map { it.id to habitMap(it) })
        write(root, "chat_messages", database.chatMessageDao().getAll().map { it.id to chatMap(it) })
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

    private suspend fun write(root: com.google.firebase.firestore.DocumentReference, name: String, rows: List<Pair<String, Map<String, Any?>>>) {
        rows.forEach { (id, values) -> root.collection(name).document(id).set(values).await() }
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
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
}