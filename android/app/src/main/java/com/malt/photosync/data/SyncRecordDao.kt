package com.malt.photosync.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncRecordDao {

    @Query("SELECT * FROM records")
    suspend fun all(): List<SyncRecord>

    /** 扫描阶段写入/更新指纹（保留已完成状态由引擎控制是否调用） */
    @Upsert
    suspend fun upsert(record: SyncRecord)

    /** 每个文件上传成功后立即落库（PRD §5.3 步骤 4）；返回受影响行数 */
    @Query(
        "UPDATE records SET status = 1, syncedAt = :now, key = :key " +
            "WHERE mediaId = :mediaId AND size = :size AND dateModified = :dateModified"
    )
    suspend fun markDone(mediaId: Long, size: Long, dateModified: Long, now: Long, key: String): Int

    /** 已完成计数（status=1 的历史累计，离线可见） */
    @Query("SELECT kind, COUNT(*) AS count FROM records WHERE status = 1 GROUP BY kind")
    fun stats(): Flow<List<KindCount>>
}

data class KindCount(val kind: Int, val count: Int)
