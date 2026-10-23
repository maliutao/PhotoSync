package com.malt.photosync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 每个媒体文件一条记录；指纹 = mediaId + size + dateModified（PRD §5.2） */
@Entity(tableName = "records")
data class SyncRecord(
    @PrimaryKey val mediaId: Long,      // MediaStore _ID
    val kind: Int,                      // 0=照片 1=视频
    val size: Long,
    val dateModified: Long,             // 秒
    val status: Int,                    // 0=待传 1=已完成
    val syncedAt: Long?,                // 完成时刻（毫秒）
    val key: String                     // 电脑端相对路径（排查用）
) {
    companion object {
        const val PENDING = 0
        const val DONE = 1
    }
}
