package sh.haven.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * One-shot resurrector for the `restart_app` MCP verb (the self-update rung of
 * the self-hosting loop).
 *
 * It runs in its OWN process (`:restart`, declared in the manifest), which is
 * what makes a reliable restart possible: it kills the **main** app process — so
 * a staged self-update's new APK loads on relaunch — and then starts the
 * launcher activity **while this resurrector is itself foreground**. Starting the
 * activity from a live foreground component clears Android's background-activity-
 * launch (BAL) block that silently defeats a bare AlarmManager relaunch fired
 * after the main process is already dead (the foreground-service BAL exemption
 * dies with that process).
 *
 * The theme is translucent (not NoDisplay) because the kill is deferred a beat
 * so the main process can flush `restart_app`'s JSON-RPC reply first — a
 * NoDisplay activity may not outlive onResume.
 */
class RestartActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mainPid = intent.getIntExtra(EXTRA_MAIN_PID, -1)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        Handler(Looper.getMainLooper()).postDelayed({
            // Kill the old main process first so the relaunch spins up a fresh
            // one (loading any staged APK), not the singleTask instance we'd
            // otherwise just bring forward.
            if (mainPid > 0 && mainPid != android.os.Process.myPid()) {
                android.os.Process.killProcess(mainPid)
            }
            launch?.let { startActivity(it) }
            finish()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, KILL_DELAY_MS)
    }

    companion object {
        private const val EXTRA_MAIN_PID = "main_pid"
        private const val KILL_DELAY_MS = 500L

        /** Intent that starts this resurrector, tagged with the caller's (main) pid. */
        fun launchIntent(context: Context): Intent =
            Intent(context, RestartActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_MAIN_PID, android.os.Process.myPid())
            }
    }
}
