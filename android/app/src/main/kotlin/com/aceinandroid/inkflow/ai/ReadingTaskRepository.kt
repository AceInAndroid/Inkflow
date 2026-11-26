package com.aceinandroid.inkflow.ai

import android.content.ContentValues
import com.aceinandroid.inkflow.BookStore
import com.aceinandroid.inkflow.core.ai.*
import org.json.JSONObject
import java.util.UUID

data class StoredReadingTask(
    val id: String, val bookId: Long, val state: ReadingTaskState,
    val payload: String, val checkpoint: String, val updatedAt: Long, val error: String?,
)

/** One durable queue. Restoration is an explicit local operation and never executes a task. */
class ReadingTaskRepository(private val store: BookStore) {
    private val machine = ReadingTaskStateMachine()
    fun list(bookId: Long): List<StoredReadingTask> = ReadingSql.rows(store.readableDatabase,
        "tb_reading_tasks", "book_id = ?", arrayOf(bookId.toString())).map { row ->
        StoredReadingTask(row.getString("id"), bookId,
            ReadingTaskState(row.getString("task_type"), row.getString("status"),
                row.getString("persistence") == "durable", row.getDouble("progress"), row.getInt("can_pause") != 0),
            row.getString("payload_json"), row.getString("checkpoint_json"), row.getLong("updated_at"),
            if (row.isNull("error")) null else row.getString("error"))
    }.sortedByDescending { it.updatedAt }

    fun get(bookId: Long, id: String) = list(bookId).firstOrNull { it.id == id }

    fun create(bookId: Long, type: String, payload: JSONObject, userAuthorized: Boolean): StoredReadingTask {
        require(userAuthorized && type in ReadingTaskStateMachine.supportedTypes)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = store.writableDatabase
        ReadingSql.transaction(db) {
            require(ReadingSql.rows(db, "tb_books", "id = ? AND is_deleted = 0", arrayOf(bookId.toString())).isNotEmpty())
            require(list(bookId).none { it.state.type in ReadingTaskStateMachine.supportedTypes &&
                it.state.status in setOf("queued", "running", "paused", "failed") }) { "Resolve the existing task first" }
            db.insertOrThrow("tb_reading_tasks", null, ContentValues().apply {
                put("id", id); put("task_type", type); put("book_id", bookId); put("priority", "userInitiated")
                put("persistence", "durable"); put("status", "queued"); put("payload_json", payload.toString())
                put("checkpoint_json", "{}"); put("created_at", now); put("updated_at", now)
            })
        }
        return get(bookId, id)!!
    }

    fun restorePaused(bookId: Long) {
        ReadingSql.transaction(store.writableDatabase) {
            for (task in list(bookId)) {
                val restored = machine.restore(task.state)
                if (restored != task.state) write(task, restored, task.checkpoint, null)
            }
        }
    }

    fun transition(task: StoredReadingTask, event: ReadingTaskEvent, userAuthorized: Boolean = false): StoredReadingTask =
        write(task, machine.transition(task.state, event, userAuthorized), task.checkpoint, null)

    fun checkpoint(task: StoredReadingTask, checkpoint: JSONObject, progress: Double): StoredReadingTask {
        require(task.state.status == "running" && progress.isFinite() && progress in task.state.progress..1.0)
        return write(task, task.state.copy(progress = progress), checkpoint.toString(), null)
    }

    fun fail(task: StoredReadingTask, error: String) = write(task,
        machine.transition(task.state, ReadingTaskEvent.FAIL), task.checkpoint, error.take(500))

    private fun write(task: StoredReadingTask, state: ReadingTaskState, checkpoint: String, error: String?): StoredReadingTask {
        val now = maxOf(System.currentTimeMillis(), task.updatedAt + 1)
        val count = store.writableDatabase.update("tb_reading_tasks", ContentValues().apply {
            put("status", state.status); put("progress", state.progress); put("checkpoint_json", checkpoint)
            put("updated_at", now); if (error == null) putNull("error") else put("error", error)
            if (state.status == "running" && task.state.status != "running") put("started_at", now)
            if (state.status in setOf("completed", "cancelled")) put("finished_at", now)
        }, "id = ? AND book_id = ? AND updated_at = ? AND status = ? AND checkpoint_json = ?",
            arrayOf(task.id, task.bookId.toString(), task.updatedAt.toString(), task.state.status, task.checkpoint))
        check(count == 1) { "Task changed; reload before continuing" }
        return task.copy(state = state, checkpoint = checkpoint, updatedAt = now, error = error)
    }
}
