package com.example.volumify.data

import android.content.Context
import android.content.SharedPreferences

object AppPreferences {
    private const val PREFS_NAME = "volumify_prefs"
    const val KEY_ORBIT_INTERVAL_DP = "orbit_interval_dp"
    const val KEY_HUB_EDGE_OFFSET_DP = "hub_edge_offset_dp"
    const val DEFAULT_ORBIT_INTERVAL_DP = 64
    const val MIN_ORBIT_INTERVAL_DP = 44
    const val MAX_ORBIT_INTERVAL_DP = 96
    const val DEFAULT_HUB_EDGE_OFFSET_DP = 20
    const val MIN_HUB_EDGE_OFFSET_DP = 0
    const val MAX_HUB_EDGE_OFFSET_DP = 80

    fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getOrbitIntervalDp(context: Context): Int {
        val sp = getSharedPreferences(context)
        return sp.getInt(KEY_ORBIT_INTERVAL_DP, DEFAULT_ORBIT_INTERVAL_DP)
    }

    fun setOrbitIntervalDp(context: Context, intervalDp: Int) {
        val sp = getSharedPreferences(context)
        sp.edit().putInt(KEY_ORBIT_INTERVAL_DP, intervalDp.coerceIn(MIN_ORBIT_INTERVAL_DP, MAX_ORBIT_INTERVAL_DP)).apply()
    }

    fun getHubEdgeOffsetDp(context: Context): Int {
        val sp = getSharedPreferences(context)
        return sp.getInt(KEY_HUB_EDGE_OFFSET_DP, DEFAULT_HUB_EDGE_OFFSET_DP)
    }

    fun setHubEdgeOffsetDp(context: Context, offsetDp: Int) {
        val sp = getSharedPreferences(context)
        sp.edit().putInt(
            KEY_HUB_EDGE_OFFSET_DP,
            offsetDp.coerceIn(MIN_HUB_EDGE_OFFSET_DP, MAX_HUB_EDGE_OFFSET_DP)
        ).apply()
    }
}
