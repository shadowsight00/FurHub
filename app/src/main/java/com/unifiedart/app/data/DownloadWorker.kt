package com.unifiedart.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URLConnection
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = LibraryDatabase.get(context).dao()
    private val accounts = Accounts.get(context)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private var bytes = 0L
    private var lastUpdate = 0L
    private var finished = 0
    private var total = 0
    private var taskKey = ""
    private val created = mutableListOf<DocumentFile>()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        taskKey = inputData.getString("task") ?: return@withContext Result.failure()
        val task = dao.download(taskKey) ?: return@withContext Result.failure()
        try {
            setForeground(notification(task.label))
            dao.progress(taskKey, "Downloading", 0, 0, 0)
            val directory = DocumentFile.fromTreeUri(applicationContext, Uri.parse(task.directory))
                ?.takeIf { it.canWrite() } ?: throw IOException("Choose a writable download folder again")
            var partial = false
            if (task.kind == "pool") {
                val pool = loadPool(task.payload, accounts)
                val ids = pool.optJSONArray("post_ids").strings()
                if (ids.isEmpty()) throw IOException("This pool has no available posts")
                total = ids.size
                val archive = newFile(directory, "e621-pool-${task.payload}.zip", "application/zip")
                created += archive
                val manifest = JSONObject().put("pool_id", task.payload).put("pool_name", pool.optString("name"))
                    .put("source_url", "https://e621.net/pools/${task.payload}")
                val entries = JSONArray()
                applicationContext.contentResolver.openOutputStream(archive.uri, "w")?.use { output ->
                    ZipOutputStream(output.buffered()).use { zip ->
                        ids.forEachIndexed { index, postId ->
                            currentCoroutineContext().ensureActive()
                            val entry = JSONObject().put("order", index + 1).put("post_id", postId)
                            var temp: File? = null
                            try {
                                val art = loadE621Post(postId, accounts)
                                if (art.blocked) throw IOException("Skipped by account blacklist")
                                val media = art.files.firstOrNull() ?: throw IOException("Original is unavailable")
                                val name = "${(index + 1).toString().padStart(5, '0')}_${postId}.${extension(media)}"
                                temp = File.createTempFile("pool-", ".part", applicationContext.cacheDir)
                                temp.outputStream().use { transfer(media.url, Source.E621, it) }
                                entry.put("archive_filename", name).put("original_filename", Uri.parse(media.url).lastPathSegment).put("status", "downloaded")
                            } catch (cancel: CancellationException) { temp?.delete(); throw cancel }
                            catch (error: IOException) { partial = true; entry.put("status", "unavailable_or_failed").put("reason", error.message ?: "Unavailable") }
                            // Only completed downloads become ZIP entries. Archive-write errors abort the whole archive.
                            try {
                                if (entry.optString("status") == "downloaded") {
                                    zip.putNextEntry(ZipEntry(entry.getString("archive_filename")))
                                    temp!!.inputStream().use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            currentCoroutineContext().ensureActive()
                                            val count = input.read(buffer); if (count < 0) break
                                            zip.write(buffer, 0, count)
                                        }
                                    }
                                    zip.closeEntry()
                                }
                            } finally { temp?.delete() }
                            entries.put(entry); finished = index + 1; updateProgress(force = true)
                        }
                        manifest.put("posts", entries).put("complete", !partial)
                        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString(2).toByteArray()); zip.closeEntry()
                    }
                } ?: throw IOException("Couldn't open ZIP destination")
            } else {
                val original = artworkFromJson(task.payload)
                val art = when (original.source) {
                    Source.E621 -> loadE621Post(original.id, accounts)
                    Source.INKBUNNY -> InkbunnySource(accounts = accounts).details(original)
                }
                if (art.blocked) throw IOException("Blocked by source/account preferences")
                val files = if (task.fileIndex >= 0) listOfNotNull(art.files.getOrNull(task.fileIndex)) else art.files
                if (files.isEmpty()) throw IOException("No original files are available to this account")
                total = files.size
                files.forEachIndexed { index, media ->
                    val name = "${art.source.label}-${art.id}-${if (task.fileIndex >= 0) task.fileIndex + 1 else index + 1}.${extension(media)}"
                    val file = newFile(directory, name, URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream")
                    created += file
                    applicationContext.contentResolver.openOutputStream(file.uri, "w")?.use { transfer(media.url, art.source, it) }
                        ?: throw IOException("Couldn't open download destination")
                    finished++; updateProgress(force = true)
                }
            }
            dao.progress(taskKey, if (partial) "Completed with skips" else "Completed", finished, total, bytes,
                if (partial) "Some posts were unavailable. See manifest.json inside the ZIP." else "Saved to your chosen folder", created.firstOrNull()?.uri.toString())
            Result.success()
        } catch (cancel: CancellationException) {
            withContext(NonCancellable) {
                val removed = created.map { runCatching { it.delete() }.getOrDefault(false) }.all { it }
                dao.progress(taskKey, "Canceled", finished, total, bytes, if (removed) "Partial output removed" else "Storage access was lost; a partial file may remain in your folder")
            }
            throw cancel
        } catch (error: Exception) {
            val removed = created.map { runCatching { it.delete() }.getOrDefault(false) }.all { it }
            dao.progress(taskKey, "Failed", finished, total, bytes, if (!removed) "Storage access was lost; a partial file may remain in your folder. Retry after choosing a folder." else if (error is IOException) "Connection or storage error. Retry from Downloads." else "Download couldn't finish; retry from Downloads")
            Result.failure()
        }
    }
    private suspend fun transfer(raw: String, source: Source, output: OutputStream) {
        val request = Request.Builder().url(accounts.mediaUrl(raw)).header("User-Agent", USER_AGENT)
            .apply { if (source == Source.INKBUNNY) header("Referer", "https://inkbunny.net") }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Media request failed (HTTP ${response.code})")
            if (response.header("Content-Type").orEmpty().substringBefore(';') in setOf("text/html", "application/json"))
                throw IOException("The site returned an error page instead of an original file")
            val body = response.body ?: throw IOException("Empty file response")
            var transferred = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count); bytes += count; transferred += count; updateProgress()
                }
            }
            if (body.contentLength() >= 0 && transferred != body.contentLength()) throw IOException("Incomplete original file")
        }
    }
    private suspend fun updateProgress(force: Boolean = false) {
        val now = System.nanoTime() / 1_000_000
        if (force || now - lastUpdate > 700) {
            dao.progress(taskKey, "Downloading", finished, total, bytes)
            lastUpdate = now
        }
    }
    private fun newFile(directory: DocumentFile, requested: String, mime: String): DocumentFile {
        val safe = requested.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val base = safe.substringBeforeLast('.', safe)
        val ext = safe.substringAfterLast('.', "")
        var name = safe; var index = 1
        while (directory.findFile(name) != null) { name = "${base}_${index++}" + if (ext.isEmpty()) "" else ".$ext" }
        return directory.createFile(mime, name) ?: throw IOException("Couldn't create file in selected folder")
    }
    private fun extension(media: MediaFile): String {
        val candidate = Uri.parse(media.url).lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return candidate.takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: media.type.substringAfter('/').takeIf { it.matches(Regex("[a-zA-Z0-9]{1,8}")) }?.lowercase() ?: "bin"
    }
    private fun notification(title: String): ForegroundInfo {
        val channel = "art_downloads"
        applicationContext.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "Artwork downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(applicationContext, channel).setContentTitle(title).setContentText("Downloading original artwork")
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else ForegroundInfo(id.hashCode(), notification)
    }
}
