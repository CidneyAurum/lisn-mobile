package com.glass.lisn

import android.app.Application
import com.glass.lisn.data.DownloadManager
import com.glass.lisn.data.PlaylistStore
import com.glass.lisn.data.SettingsStore
import com.glass.lisn.engine.HttpSourceConfig
import com.glass.lisn.engine.SourceRegistry
import com.glass.lisn.engine.json
import kotlinx.serialization.Serializable

/** 引擎中枢:全局单例聚合注册表、设置、歌单、下载 */
object EngineHub {
    lateinit var registry: SourceRegistry
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var playlists: PlaylistStore
        private set
    lateinit var downloads: DownloadManager
        private set

    private val templatesFile by lazy { java.io.File(registryDir(), "http-templates.json") }

    private fun registryDir(): java.io.File = java.io.File(AppContextHolder.get().filesDir, "engine").apply { mkdirs() }

    @Serializable
    private data class TemplatesFile(val templates: List<HttpSourceConfig>)

    val defaultTemplate = HttpSourceConfig(
        id = "http-huibq",
        name = "Huibq 后端(HTTP 模板)",
        urlTemplate = "https://lxmusicapi.onrender.com/url/{source}/{songId}/{quality}",
        headers = mapOf("X-Request-Key" to "share-v3"),
        jsonPath = "url",
        platforms = listOf("kw", "kg", "tx", "wy", "mg"),
        qualities = listOf("320k", "128k"),
        enabled = false
    )

    fun loadTemplates(): List<HttpSourceConfig> {
        val loaded = runCatching {
            json.decodeFromString(TemplatesFile.serializer(), templatesFile.readText()).templates
        }.getOrDefault(emptyList())
        if (loaded.isEmpty()) {
            saveTemplates(listOf(defaultTemplate))
            return listOf(defaultTemplate)
        }
        return loaded
    }

    fun saveTemplates(templates: List<HttpSourceConfig>) {
        runCatching { templatesFile.writeText(json.encodeToString(TemplatesFile.serializer(), TemplatesFile(templates))) }
    }

    fun rebuild() {
        registry.rebuild(loadTemplates())
    }

    fun init(app: Application) {
        AppContextHolder.init(app)
        settings = SettingsStore(app)
        playlists = PlaylistStore(app)
        registry = SourceRegistry(app)
        registry.mf.init()
        registry.mode = settings.get().mode
        downloads = DownloadManager(app) { registry }
        rebuild()
    }
}

/** 获取全局 Context 的简单容器 */
object AppContextHolder {
    lateinit var app: Application
        private set

    fun init(app: Application) { this.app = app }

    fun get(): Application = app
}
