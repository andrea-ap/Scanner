package com.scanner.app

import android.content.Context

enum class SortMode(val label: String) {
    RECENT("Più recenti"),
    OLDEST("Meno recenti"),
    NAME("Nome A–Z"),
    ATTACHMENTS("Più allegati"),
}

/** Impostazioni semplici dell'app, salvate sul telefono. */
object AppPrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("scanner_prefs", Context.MODE_PRIVATE)

    fun lockEnabled(c: Context) = prefs(c).getBoolean("lock", false)
    fun setLockEnabled(c: Context, v: Boolean) = prefs(c).edit().putBoolean("lock", v).apply()

    fun compact(c: Context) = prefs(c).getBoolean("compact", false)
    fun setCompact(c: Context, v: Boolean) = prefs(c).edit().putBoolean("compact", v).apply()

    fun sort(c: Context): SortMode =
        SortMode.entries.firstOrNull { it.name == prefs(c).getString("sort", null) } ?: SortMode.RECENT

    fun setSort(c: Context, v: SortMode) = prefs(c).edit().putString("sort", v.name).apply()
}
