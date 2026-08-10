package sh.haven.core.data

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import org.json.JSONObject
import java.io.File

private const val TAG = "NativeCrashLog"

/** One past native crash, as the system recorded it. */
data class NativeCrashRecord(
    val timestampMs: Long,
    /** SIGSEGV, SIGABRT, … as a human-readable summary from the system. */
    val description: String,
    val signal: Int,
    /** The tombstone, when the system kept one. Null is common and not an error. */
    val trace: String?,
) {
    /**
     * The signal as a name — `SIGABRT (6)` — or just the number when unknown (#526).
     *
     * This is the one field that survives when no tombstone was kept, and on a device
     * that keeps none it is the *only* thing distinguishing one crash from another.
     * SIGABRT means the runtime deliberately aborted (a failed JNI check, say),
     * SIGSEGV means a bad access; they point at completely different causes, and a
     * reporter staring at ten identical lines reading "crash" can tell me neither.
     */
    val signalName: String
        get() = when (signal) {
            4 -> "SIGILL"
            6 -> "SIGABRT"
            7 -> "SIGBUS"
            8 -> "SIGFPE"
            11 -> "SIGSEGV"
            else -> null
        }?.let { "$it ($signal)" } ?: "signal $signal"

    fun toJson(): String = JSONObject().apply {
        put("timestampMs", timestampMs)
        put("description", description)
        put("signal", signal)
        put("trace", trace ?: JSONObject.NULL)
    }.toString()

    companion object {
        fun fromJson(line: String): NativeCrashRecord? = try {
            val o = JSONObject(line)
            NativeCrashRecord(
                timestampMs = o.getLong("timestampMs"),
                description = o.optString("description"),
                signal = o.optInt("signal"),
                trace = if (o.isNull("trace")) null else o.optString("trace"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "dropping unparseable crash record: ${e.message}")
            null
        }
    }
}

/**
 * Native crashes, recovered after the fact from the system.
 *
 * #509 and #517 are both native crashes, and both stalled at the same point: the
 * reporter's log ends at `Fatal signal …` with no backtrace, so the function that
 * died is unknown. That is a flaw in Haven, not in how either of them captured
 * it. Haven records its logcat from inside its own process, so when the process
 * dies of a native signal the recorder dies with it, and the tombstone the system
 * writes lands after Haven is gone. **The one log that identifies a native crash
 * is the one that design structurally cannot capture.**
 *
 * `ApplicationExitInfo` closes that hole: on the next launch the system will hand
 * back why the previous process died and, for a native crash, the tombstone
 * itself. Asking a user to reproduce under `adb logcat -b crash` stops being the
 * only route to a backtrace.
 *
 * Deliberately file-backed rather than a Room table. A new table means a schema
 * version bump, which is a one-way door — Room refuses to open a database newer
 * than the app, so a downgrade bricks the install. A crash log is not worth that,
 * and a few JSON lines in app-private storage do the job.
 *
 * API 30+. On 26–29 this reports nothing rather than pretending: there is no
 * equivalent API, and inventing a partial one would produce records that look
 * like evidence and aren't.
 */
class NativeCrashLog(private val context: Context) {

    private val file: File get() = File(context.filesDir, "native_crashes.jsonl")

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Fold any native crashes the system knows about into the log.
     *
     * Safe to call on every launch: records are keyed by the system's own
     * timestamp, so a crash already recorded is not duplicated.
     *
     * @return the records that were new this call.
     */
    fun refresh(): List<NativeCrashRecord> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val existing = records()
        val known = existing.map { it.timestampMs }.toSet()

        val fresh = try {
            systemCrashes().filter { it.timestampMs !in known }
        } catch (e: Exception) {
            // Diagnostics must never be the reason the app fails to start.
            Log.w(TAG, "could not read exit reasons: ${e.message}")
            return emptyList()
        }
        if (fresh.isEmpty()) return emptyList()

        val merged = merge(existing, fresh)
        try {
            file.writeText(merged.joinToString("\n") { it.toJson() } + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "could not persist crash records: ${e.message}")
            return emptyList()
        }
        for (r in fresh) {
            Log.w(TAG, "recovered native crash from ${r.timestampMs}: ${r.description}")
        }
        return fresh
    }

    /** Every recorded crash, oldest first. */
    fun records(): List<NativeCrashRecord> = try {
        if (!file.exists()) {
            emptyList()
        } else {
            file.readLines().filter { it.isNotBlank() }.mapNotNull { NativeCrashRecord.fromJson(it) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "could not read crash records: ${e.message}")
        emptyList()
    }

    val latest: NativeCrashRecord? get() = records().maxByOrNull { it.timestampMs }

    fun clear() {
        runCatching { file.delete() }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun systemCrashes(): List<NativeCrashRecord> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return emptyList()
        val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_RECORDS)
        return reasons
            .filter { it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE }
            .map { info ->
                NativeCrashRecord(
                    timestampMs = info.timestamp,
                    description = info.description ?: "native crash",
                    signal = info.status,
                    // The tombstone is the whole point, but it is not always
                    // kept — read failures degrade to a record without one
                    // rather than losing the fact that a crash happened.
                    trace = try {
                        info.traceInputStream?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        Log.w(TAG, "no tombstone for crash at ${info.timestamp}: ${e.message}")
                        null
                    },
                )
            }
    }

    internal companion object {
        const val MAX_RECORDS = 10

        /**
         * Combine stored and newly-seen crashes into what should be written back.
         *
         * Pure, and separate from [refresh], because this is where the behaviour
         * that matters lives: the system re-reports the same crash on every
         * launch, so without dedup by timestamp a single crash would accumulate a
         * copy per app start, and the cap would then evict the *real* history to
         * make room for duplicates of one event.
         *
         * Oldest first, so the file reads chronologically and `takeLast` drops the
         * oldest rather than the newest.
         */
        internal fun merge(
            existing: List<NativeCrashRecord>,
            fresh: List<NativeCrashRecord>,
            max: Int = MAX_RECORDS,
        ): List<NativeCrashRecord> {
            val byTimestamp = LinkedHashMap<Long, NativeCrashRecord>()
            for (r in existing + fresh) byTimestamp[r.timestampMs] = r
            return byTimestamp.values.sortedBy { it.timestampMs }.takeLast(max)
        }
    }
}
