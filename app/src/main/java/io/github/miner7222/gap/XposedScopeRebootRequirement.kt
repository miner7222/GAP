package io.github.miner7222.gap

import android.content.Context
import android.os.SystemClock

object XposedScopeRebootRequirement {
    private const val SYSTEM_SCOPE = "system"
    private const val PREFS_NAME = "xposed_scope_reboot"
    private const val KEY_PENDING_AT_ELAPSED_REALTIME = "pending_at_elapsed_realtime"

    fun requiresRebootForApprovedScopes(approvedScopes: List<String>): Boolean {
        return approvedScopes.any { it == SYSTEM_SCOPE }
    }

    fun markPending(context: Context, now: Long = SystemClock.elapsedRealtime()) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PENDING_AT_ELAPSED_REALTIME, now)
            .apply()
    }

    fun isPending(context: Context, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pendingAt = prefs.getLong(KEY_PENDING_AT_ELAPSED_REALTIME, -1L)
        val pending = isPendingForBoot(pendingAtElapsedRealtime = pendingAt, now = now)
        if (!pending && pendingAt >= 0L) {
            prefs.edit().remove(KEY_PENDING_AT_ELAPSED_REALTIME).apply()
        }
        return pending
    }

    fun isPendingForBoot(pendingAtElapsedRealtime: Long, now: Long): Boolean {
        return pendingAtElapsedRealtime >= 0L && now >= pendingAtElapsedRealtime
    }
}
