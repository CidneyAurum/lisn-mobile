package com.glass.lisn

import com.glass.lisn.engine.SourceProvider
import com.glass.lisn.engine.searchProviders
import com.glass.lisn.model.Song
import com.glass.lisn.model.SongOrigin
import com.glass.lisn.model.SourceCaps
import com.glass.lisn.model.SourceHealth
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SearchProvidersTest {
    private class Provider(
        override val id: String = "test",
        private val block: suspend (String, Int) -> List<Song>
    ) : SourceProvider {
        override val name = id
        override val kind = "test"
        override val caps = SourceCaps(listOf("kw"), listOf("128k"), true)
        override val health = SourceHealth(status = "ok")
        override suspend fun search(keyword: String, page: Int) = block(keyword, page)
        override suspend fun resolveUrl(origin: SongOrigin, quality: String): String = error("unused")
    }

    @Test fun `推荐和分页请求不会共享上次搜索的结果`() = runBlocking {
        val provider = Provider { keyword, page -> listOf(Song("", "$keyword$page", "artist")) }
        val first = searchProviders(listOf(provider), "A", 1)
        searchProviders(listOf(provider), "B", 1)
        val second = searchProviders(listOf(provider), "A", 2)
        assertEquals(listOf("A1"), first.songs.map { it.name })
        assertEquals(listOf("A2"), second.songs.map { it.name })
        assertEquals(2, second.page)
    }

    @Test fun `取消搜索会停止子请求而不转成空成功`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val failures = AtomicInteger()
        val provider = Provider { _, _ -> entered.complete(Unit); awaitCancellation() }
        val request = launch {
            searchProviders(listOf(provider), "A", 1) { _, _ -> failures.incrementAndGet() }
            fail("Cancelled search must not return results")
        }
        withTimeout(3000) { entered.await() }
        request.cancelAndJoin()
        assertTrue(request.isCancelled)
        assertEquals(0, failures.get())
    }

    @Test fun `单源超时不丢弃其他源的结果`() = runBlocking {
        val slow = Provider("slow") { _, _ -> delay(10_000); emptyList() }
        val good = Provider("good") { _, _ -> listOf(Song("", "available", "artist")) }
        val failures = AtomicInteger()
        val result = withTimeout(3000) {
            searchProviders(listOf(slow, good), "A", 1, timeoutMs = 50) { _, _ -> failures.incrementAndGet() }
        }
        assertEquals(listOf("available"), result.songs.map { it.name })
        assertEquals(1, failures.get())
    }

    @Test fun `请求离开调用线程并限制最多四源并发`() = runBlocking {
        val callingThread = Thread.currentThread()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val providers = (1..9).map { index ->
            Provider("p$index") { _, _ ->
                assertNotSame(callingThread, Thread.currentThread())
                val count = active.incrementAndGet()
                peak.updateAndGet { maxOf(it, count) }
                try {
                    delay(30)
                    listOf(Song("", "song$index", "artist"))
                } finally { active.decrementAndGet() }
            }
        }
        val result = withTimeout(3000) { searchProviders(providers, "A", 1) }
        assertEquals(9, result.songs.size)
        assertTrue(peak.get() in 2..4)
    }
}
