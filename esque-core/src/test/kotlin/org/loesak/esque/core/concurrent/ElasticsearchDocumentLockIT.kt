package org.loesak.esque.core.concurrent

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.elasticsearch.client.RestClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.loesak.esque.core.AbstractElasticsearchIT
import org.loesak.esque.core.elasticsearch.RestClientOperations
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ElasticsearchDocumentLockIT : AbstractElasticsearchIT() {

    private lateinit var client: RestClient
    private lateinit var operations: RestClientOperations

    @BeforeEach
    fun setUp() {
        client = createRestClient()
        operations = RestClientOperations(client, MIGRATION_KEY)
        operations.createMigrationIndex()
    }

    @AfterEach
    fun tearDown() {
        client.close()
    }

    @Test
    fun `lock and unlock`() {
        val lock = ElasticsearchDocumentLock(operations)
        lock.lock()
        lock.unlock()
    }

    @Test
    fun `tryLock acquires lock and returns true`() {
        val lock = ElasticsearchDocumentLock(operations)
        val acquired = lock.tryLock(5, TimeUnit.SECONDS)
        assertThat(acquired).isTrue
        lock.unlock()
    }

    @Test
    fun `tryLock returns false when lock held by another instance`() {
        val lock1 = ElasticsearchDocumentLock(operations)
        lock1.lock()

        try {
            createRestClient().use { client2 ->
                val operations2 = RestClientOperations(client2, MIGRATION_KEY)
                val lock2 = ElasticsearchDocumentLock(operations2, Duration.ofMillis(50))

                val acquired = lock2.tryLock(500, TimeUnit.MILLISECONDS)
                assertThat(acquired).isFalse
            }
        } finally {
            lock1.unlock()
        }
    }

    @Test
    fun `unlock throws when not held by current thread`() {
        val lock = ElasticsearchDocumentLock(operations)
        assertThatThrownBy { lock.unlock() }
            .isInstanceOf(IllegalMonitorStateException::class.java)
    }

    @Test
    fun `lock blocks until lock released`() {
        val lock1 = ElasticsearchDocumentLock(operations, Duration.ofMillis(50))
        lock1.lock()

        val client2 = createRestClient()
        val operations2 = RestClientOperations(client2, MIGRATION_KEY)
        val lock2 = ElasticsearchDocumentLock(operations2, Duration.ofMillis(50))

        val lock2Acquired = AtomicBoolean(false)
        val lock2Started = CountDownLatch(1)
        val lock2Done = CountDownLatch(1)

        val t = Thread {
            lock2Started.countDown()
            lock2.lock()
            lock2Acquired.set(true)
            lock2.unlock()
            lock2Done.countDown()
        }
        t.start()

        lock2Started.await(5, TimeUnit.SECONDS)
        Thread.sleep(200)

        assertThat(lock2Acquired.get()).isFalse

        lock1.unlock()

        val completed = lock2Done.await(10, TimeUnit.SECONDS)
        assertThat(completed).isTrue
        assertThat(lock2Acquired.get()).isTrue

        client2.close()
    }

    companion object {
        private const val MIGRATION_KEY = "lock-test"
    }
}
