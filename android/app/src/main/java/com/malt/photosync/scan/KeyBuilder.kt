package com.malt.photosync.scan

import java.time.Instant
import java.time.ZoneId

/** 构造电脑端相对路径 key：清洗(相册)/YYYY/MM/清洗(文件名)（docs/PROTOCOL.md） */
object KeyBuilder {

    private val ILLEGAL = Regex("[<>:\"/\\\\|?*\\x00-\\x1f]")

    fun sanitize(s: String): String =
        ILLEGAL.replace(s, "_").trim().trimEnd('.', ' ')
            .take(128).ifEmpty { "_" }

    fun build(item: MediaScanner.MediaItem): String {
        val zdt = Instant.ofEpochSecond(item.dateModified)
            .atZone(ZoneId.systemDefault())
        return "${sanitize(item.bucketName)}/${zdt.year}/%02d/${sanitize(item.name)}"
            .format(zdt.monthValue)
    }
}
