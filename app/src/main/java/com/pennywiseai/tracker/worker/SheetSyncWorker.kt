package com.pennywiseai.tracker.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pennywiseai.tracker.BuildConfig
import com.pennywiseai.tracker.data.database.dao.TransactionDao
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * AB fork — Feature 1: push every saved transaction to a Google Sheet.
 *
 * Design choices, so one failure can't break anything else:
 *  - Runs separately from SMS parsing. Saving a transaction never waits on the network.
 *  - Remembers the last row id the Sheet accepted. If the phone is offline or Google
 *    is down, nothing is lost: the next run sends everything after that id.
 *  - The Sheet side de-duplicates on transactionHash, so a re-send is harmless.
 *  - The raw SMS body is never sent.
 */
@HiltWorker
class SheetSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val transactionDao: TransactionDao
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val url = BuildConfig.SHEET_WEBHOOK_URL
        if (url.isBlank()) return Result.success() // sync not configured in this build

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var lastId = prefs.getLong(KEY_LAST_ID, 0L)

        return try {
            while (true) {
                val batch = transactionDao.getTransactionsAfterId(lastId, BATCH_SIZE)
                if (batch.isEmpty()) break
                if (!post(url, batch)) return Result.retry()
                lastId = batch.last().id
                prefs.edit().putLong(KEY_LAST_ID, lastId).apply()
                Log.i(TAG, "Synced ${batch.size} rows, last id $lastId")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Sheet sync failed: ${e.message}")
            Result.retry()
        }
    }

    private suspend fun post(url: String, rows: List<TransactionEntity>): Boolean =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("token", BuildConfig.SHEET_TOKEN)
                .put("rows", JSONArray().apply { rows.forEach { put(it.toJson()) } })
                .toString()
                .toByteArray(Charsets.UTF_8)

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 30_000
                // Apps Script answers a POST with a 302 to its result page. The script
                // has already run by then, so we don't follow it; we read the result.
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                when (code) {
                    200 -> readOk(conn.inputStream.bufferedReader().readText())
                    301, 302, 303 -> {
                        val location = conn.getHeaderField("Location") ?: return@withContext false
                        val result = URL(location).openConnection() as HttpURLConnection
                        try {
                            result.responseCode == 200 &&
                                readOk(result.inputStream.bufferedReader().readText())
                        } finally {
                            result.disconnect()
                        }
                    }
                    else -> {
                        Log.w(TAG, "Sheet returned HTTP $code")
                        false
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    private fun readOk(text: String): Boolean = try {
        val ok = JSONObject(text).optBoolean("ok", false)
        if (!ok) Log.w(TAG, "Sheet rejected batch: $text")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "Unexpected Sheet reply: ${text.take(200)}")
        false
    }

    private fun TransactionEntity.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("hash", transactionHash)
        .put("dateTime", dateTime.toString())
        .put("type", transactionType.name)
        .put("amount", amount.toPlainString())
        .put("currency", currency)
        .put("merchant", merchantName)
        .put("category", category)
        .put("bank", bankName ?: "")
        .put("account", accountNumber ?: "")
        .put("toAccount", toAccount ?: "")
        .put("balanceAfter", balanceAfter?.toPlainString() ?: "")
        .put("description", description ?: "")
        .put("deleted", isDeleted)

    companion object {
        private const val TAG = "SheetSyncWorker"
        private const val PREFS = "ab_sheet_sync"
        private const val KEY_LAST_ID = "last_synced_id"
        private const val BATCH_SIZE = 200
        private const val WORK_NOW = "ab_sheet_sync_now"
        private const val WORK_PERIODIC = "ab_sheet_sync_periodic"

        private val networkConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Call after new transactions are saved. Runs as soon as there's internet. */
        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SheetSyncWorker>()
                .setConstraints(networkConstraint)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // APPEND_OR_REPLACE: a burst of SMS queues one follow-up run instead of racing.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** Safety net in case a run was missed (phone off, app killed). */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SheetSyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(networkConstraint)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
