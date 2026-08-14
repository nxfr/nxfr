package com.nxfr.android.transfer

import android.content.Context
import org.json.JSONArray

object RecentNodesRepository {
    private const val PREFS_NAME = "nxfr_manual_connect_prefs"
    private const val KEY_RECENT_NODES = "recent_nodes"
    private const val MAX_RECENT = 5

    fun getRecentNodes(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_RECENT_NODES, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until jsonArray.length()) {
                val item = jsonArray.optString(i)
                if (!item.isNullOrBlank()) {
                    list.add(item)
                }
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addRecentNode(context: Context, address: String) {
        val trimmed = address.trim()
        if (trimmed.isBlank()) return

        val current = getRecentNodes(context).toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)

        val trimmedList = if (current.size > MAX_RECENT) current.take(MAX_RECENT) else current
        val jsonArray = JSONArray()
        trimmedList.forEach { jsonArray.put(it) }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECENT_NODES, jsonArray.toString()).apply()
    }

    fun removeRecentNode(context: Context, address: String) {
        val current = getRecentNodes(context).toMutableList()
        current.remove(address.trim())

        val jsonArray = JSONArray()
        current.forEach { jsonArray.put(it) }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECENT_NODES, jsonArray.toString()).apply()
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_RECENT_NODES).apply()
    }
}
