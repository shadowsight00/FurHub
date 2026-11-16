package com.unifiedart.app.data

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class FollowingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val local = LocalState(applicationContext)
        if (local.followedArtists.isEmpty()) return Result.success()
        return try {
            val accounts = Accounts.get(applicationContext)
            val scope = accounts.eId.ifBlank { "public" }
            val page = E621Source(accounts = accounts, follows = { local.followedArtists }).search(SearchQuery(mode = GalleryMode.FOLLOWING), 1)
            val dao = LibraryDatabase.get(applicationContext).dao()
            page.items.forEach { dao.put(LibraryRecord("feed:$scope:${it.key}", "feed", it.title, it.toJson(), scope)) }
            dao.records("feed").filter { it.groupName == scope }.drop(1000).forEach { dao.remove(it.key) }
            Result.success()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
    companion object {
        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<FollowingWorker>(4, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("following-refresh", ExistingPeriodicWorkPolicy.KEEP, work)
        }
    }
}
