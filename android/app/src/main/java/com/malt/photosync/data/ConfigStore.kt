package com.malt.photosync.data

import android.content.Context
import android.content.SharedPreferences

/** 配对与目录选择等配置（SharedPreferences） */
class ConfigStore(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("config", Context.MODE_PRIVATE)

    var host: String
        get() = sp.getString(KEY_HOST, "") ?: ""
        set(v) = sp.edit().putString(KEY_HOST, v.trim()).apply()

    var port: Int
        get() = sp.getInt(KEY_PORT, 8765)
        set(v) = sp.edit().putInt(KEY_PORT, v).apply()

    var token: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v.trim()).apply()

    /** 已勾选的相册 bucketId 集合 */
    var bucketIds: Set<String>
        get() = sp.getStringSet(KEY_BUCKETS, emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_BUCKETS, v).apply()

    /** bucketId → 显示名（存为 "id|name" 的 StringSet） */
    var bucketNames: Map<String, String>
        get() = (sp.getStringSet(KEY_BUCKET_NAMES, emptySet()) ?: emptySet())
            .mapNotNull { s ->
                val i = s.indexOf('|')
                if (i > 0) s.substring(0, i) to s.substring(i + 1) else null
            }.toMap()
        set(v) = sp.edit()
            .putStringSet(KEY_BUCKET_NAMES, v.map { "${it.key}|${it.value}" }.toSet())
            .apply()

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_TOKEN = "token"
        const val KEY_BUCKETS = "bucketIds"
        const val KEY_BUCKET_NAMES = "bucketNames"
    }
}
