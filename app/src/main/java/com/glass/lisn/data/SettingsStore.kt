package com.glass.lisn.data

import android.content.Context
import com.glass.lisn.engine.json
import com.glass.lisn.model.Settings
import kotlinx.serialization.Serializable

@Serializable
private data class SettingsAll(val settings: Settings)

class SettingsStore(context: Context) {
    private val file = java.io.File(context.filesDir, "settings.json")

    var data: Settings = Settings()
        private set

    init {
        val loaded = runCatching {
            json.decodeFromString(SettingsAll.serializer(), file.readText()).settings
        }.getOrNull()
        data = loaded ?: Settings()
        save()
    }

    fun get(): Settings = data

    fun patch(p: Settings): Settings {
        data = p
        save()
        return data
    }

    private fun save() {
        runCatching { file.writeText(json.encodeToString(SettingsAll.serializer(), SettingsAll(data))) }
    }
}
