package com.malt.photosync.scan

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/** 扫描系统相册（MediaStore），指纹口径见 PRD §5.2 */
class MediaScanner(private val resolver: ContentResolver) {

    data class MediaItem(
        val mediaId: Long,          // MediaStore _ID
        val name: String,           // DISPLAY_NAME
        val size: Long,
        val dateModified: Long,     // 秒
        val bucketId: String,
        val bucketName: String,
        val kind: Int               // 0=照片 1=视频
    ) {
        val isVideo: Boolean get() = kind == 1

        /** 该文件的 Content URI */
        fun uri(): Uri = ContentUris.withAppendedId(
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            mediaId
        )
    }

    data class Bucket(
        val id: String,
        val name: String,
        var photos: Int = 0,
        var videos: Int = 0
    )

    /** 查询所选目录下全部照片和视频，按时间排序（PRD §5.3 步骤 1、3） */
    fun scan(bucketIds: Set<String>): List<MediaItem> {
        if (bucketIds.isEmpty()) return emptyList()
        val out = ArrayList<MediaItem>()
        val selection = "${MediaStore.Images.Media.BUCKET_ID} IN " +
            "(${List(bucketIds.size) { "?" }.joinToString(",")})"
        val args = bucketIds.toTypedArray()
        for (kind in 0..1) {
            val uri = if (kind == 1) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            resolver.query(uri, PROJECTION, selection, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    out += MediaItem(
                        c.getLong(0), name, c.getLong(2), c.getLong(3),
                        c.getString(4) ?: "", c.getString(5) ?: "未知", kind
                    )
                }
            }
        }
        return out.sortedBy { it.dateModified }
    }

    /** 全部相册目录（供选择界面） */
    fun buckets(): List<Bucket> {
        val map = LinkedHashMap<String, Bucket>()
        for (kind in 0..1) {
            val uri = if (kind == 1) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            resolver.query(
                uri,
                arrayOf(
                    MediaStore.Images.Media.BUCKET_ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: "未知"
                    val b = map.getOrPut(id) { Bucket(id, name) }
                    if (kind == 1) b.videos++ else b.photos++
                }
            }
        }
        return map.values.sortedBy { it.name }
    }

    private companion object {
        val PROJECTION = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
    }
}
