package com.unifiedart.app.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "library")
data class LibraryRecord(@PrimaryKey val key: String, val kind: String, val label: String, val payload: String = "", val groupName: String = "", val created: Long = System.currentTimeMillis())
@Entity(tableName = "downloads")
data class DownloadTask(@PrimaryKey val id: String, val kind: String, val label: String, val payload: String, val directory: String,
    val fileIndex: Int = -1, val state: String = "Queued", val done: Int = 0, val total: Int = 0,
    val bytes: Long = 0, val message: String = "", val outputUri: String = "", val created: Long = System.currentTimeMillis())

@Dao
interface LibraryDao {
    @Query("SELECT * FROM library ORDER BY created DESC") fun observeRecords(): Flow<List<LibraryRecord>>
    @Query("SELECT * FROM library WHERE kind=:kind ORDER BY created DESC") suspend fun records(kind: String): List<LibraryRecord>
    @Upsert suspend fun put(record: LibraryRecord)
    @Query("DELETE FROM library WHERE `key`=:key") suspend fun remove(key: String)
    @Query("SELECT * FROM downloads ORDER BY created DESC") fun observeDownloads(): Flow<List<DownloadTask>>
    @Query("SELECT * FROM downloads WHERE id=:id") suspend fun download(id: String): DownloadTask?
    @Upsert suspend fun put(task: DownloadTask)
    @Query("UPDATE downloads SET state=:state, done=:done, total=:total, bytes=:bytes, message=:message, outputUri=:output WHERE id=:id")
    suspend fun progress(id: String, state: String, done: Int, total: Int, bytes: Long, message: String = "", output: String = "")
}
@Database(entities = [LibraryRecord::class, DownloadTask::class], version = 1, exportSchema = true)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        @Volatile private var instance: LibraryDatabase? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, LibraryDatabase::class.java, "unifiedart.db").build().also { instance = it }
        }
    }
}

fun Artwork.toJson(): String = JSONObject().put("source", source.name).put("id", id).put("title", title)
    .put("artists", JSONArray(artists)).put("tags", JSONArray(tags)).put("uploaded", uploadedAt).put("thumbnail", stripSession(thumbnail))
    .put("files", JSONArray(files.map { JSONObject().put("url", stripSession(it.url)).put("type", it.type).put("width", it.width).put("height", it.height) }))
    .put("pageCount", pageCount).put("favorite", favorite).put("pools", JSONArray(pools)).put("blocked", blocked)
    .put("format", format).put("fields", JSONObject(filterFields)).toString()
fun artworkFromJson(raw: String): Artwork {
    val j = JSONObject(raw)
    val fields = j.optJSONObject("fields") ?: JSONObject()
    return Artwork(Source.valueOf(j.getString("source")), j.getString("id"), j.optString("title"),
        j.optJSONArray("artists").strings(), j.optJSONArray("tags").strings(), j.optLong("uploaded"), j.optString("thumbnail"),
        j.optJSONArray("files").objects().map { MediaFile(it.getString("url"), it.getString("type"), it.optInt("width"), it.optInt("height")) },
        j.optInt("pageCount", 1), j.optBoolean("favorite"), j.optJSONArray("pools").strings(), j.optBoolean("blocked"),
        format = j.optString("format"), filterFields = fields.keys().asSequence().associateWith { fields.optString(it) })
}
