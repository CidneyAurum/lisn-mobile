package com.glass.lisn.model

import kotlinx.serialization.Serializable

/** 平台侧歌曲定位信息,一个 Song 可携带多个平台 origin(聚合搜索合并) */
@Serializable
data class SongOrigin(
    val providerId: String,
    val platform: String,
    val songId: String,
    val hash: String? = null,
    val copyrightId: String? = null,
    val extra: Map<String, String> = emptyMap()
)

/** 聚合后的统一歌曲模型 */
@Serializable
data class Song(
    val key: String,
    val name: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long? = null,
    val origins: List<SongOrigin> = emptyList(),
    val picUrl: String? = null
)

@Serializable
data class SearchPage(
    val songs: List<Song>,
    val hasMore: Boolean,
    val page: Int
)

@Serializable
data class SourceHealth(
    var status: String = "loading",
    var latencyMs: Long? = null,
    var okCount: Int = 0,
    var failCount: Int = 0,
    var lastError: String? = null,
    var lastOkAt: Long? = null
)

@Serializable
data class SourceCaps(
    val platforms: List<String>,
    val qualities: List<String>,
    val supportsSearch: Boolean
)

@Serializable
data class ProviderSnapshot(
    val id: String,
    val name: String,
    val kind: String,
    val caps: SourceCaps,
    val health: SourceHealth
)

@Serializable
data class MfEntrySnap(
    val id: String,
    val name: String,
    val platform: String,
    val enabled: Boolean,
    val repo: String? = null,
    val version: String? = null,
    val remoteDate: String? = null
)

@Serializable
data class HttpTemplateSnap(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val enabled: Boolean? = null
)

@Serializable
data class LxEntrySnap(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val platforms: List<String> = emptyList(),
    val qualities: List<String> = emptyList(),
    val remoteDate: String? = null
)

@Serializable
data class SourcesSnapshot(
    val providers: List<ProviderSnapshot>,
    val mode: String,
    val mfEntries: List<MfEntrySnap>,
    val templates: List<HttpTemplateSnap>,
    val lxEntries: List<LxEntrySnap> = emptyList()
)

enum class DownloadStatus { WAITING, RESOLVING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

@Serializable
data class DownloadItem(
    val id: String,
    val name: String,
    val artist: String,
    val album: String? = null,
    val quality: String,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val progress: Float = 0f,
    val received: Long = 0,
    val total: Long = 0,
    val filePath: String? = null,
    val error: String? = null,
    val sourceId: String? = null,
    val platform: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Settings(
    val quality: String = "320k",
    val mode: String = "auto",
    val intervalMs: Long = 2500,
    val autoCheckUpdates: Boolean = true,
    val playMode: String = "loop"
)

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val keyword: String? = null,
    val songs: List<Song> = emptyList()
)

@Serializable
data class LibraryFile(
    val uri: String,
    val name: String,
    val artist: String,
    val isFlac: Boolean,
    val size: Long,
    val mtime: Long
)

fun normalizeKey(name: String, artist: String): String {
    val firstArtist = artist.split("/").firstOrNull()?.trim() ?: ""
    return (name.trim() + "|" + firstArtist).lowercase()
}
