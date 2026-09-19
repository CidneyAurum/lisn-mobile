package com.glass.lisn.data

import android.content.Context
import com.glass.lisn.engine.json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/** 搜索历史:本地持久化,最多 30 条,新词置顶去重 */
class SearchHistoryStore(context: Context) {
    private val file = File(context.filesDir, "search-history.json")
    private val _items = mutableListOf<String>()
    val items: List<String> get() = _items.toList()

    init {
        runCatching {
            val list = json.decodeFromString(ListSerializer(String.serializer()), file.readText())
            _items.addAll(list)
        }
    }

    @Synchronized
    fun record(keyword: String) {
        val k = keyword.trim()
        if (k.isEmpty()) return
        _items.removeAll { it.equals(k, ignoreCase = true) }
        _items.add(0, k)
        while (_items.size > 30) _items.removeAt(_items.size - 1)
        save()
    }

    @Synchronized
    fun remove(keyword: String) {
        _items.removeAll { it == keyword }
        save()
    }

    @Synchronized
    fun clear() {
        _items.clear()
        save()
    }

    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(ListSerializer(String.serializer()), _items))
        }
    }
}
