package org.loesak.esque.core.concurrent;

import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.loesak.esque.core.AbstractElasticsearchIT;
import org.loesak.esque.core.elasticsearch.RestClientOperations;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElasticsearchDocumentLockIT extends AbstractElasticsearchIT {

    private static final String MIGRATION_KEY = "lock-test";

    private RestClient client;
    private RestClientOperations operations;

    @BeforeEach
    void setUp() {
        client = createRestClient();
        operations = new RestClientOperations(client, MIGRATION_KEY);
        operations.createMigrationIndex();
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
    }

    @Test
    void lockAndUnlock() {
        ElasticsearchDocumentLock lock = new ElasticsearchDocumentLock(operations);

        lock.lock();
        // should be able to unlock without error
        lock.unlock();
    }

    @Test
    void tryLock_acquiresLockAndReturnsTrue() throws InterruptedException {
        ElasticsearchDocumentLock lock = new ElasticsearchDocumentLock(operations);

        boolean acquired = lock.tryLock(5, TimeUnit.SECONDS);

        assertThat(acquired).isTrue();
        lock.unlock();
    }

    @Test
    void tryLock_returnsFalseWhenLockHeldByAnotherInstance() throws Exception {
        // first instance acquires the lock
        ElasticsearchDocumentLock lock1 = new ElasticsearchDocumentLock(operations);
        lock1.lock();

        // second instance with separate RestClient and operations (simulates another process)
        try (RestClient client2 = createRestClient()) {
            RestClientOperations operations2 = new RestClientOperations(client2, MIGRATION_KEY);
            ElasticsearchDocumentLock lock2 = new ElasticsearchDocumentLock(operations2, Duration.ofMillis(50));

            // should timeout quickly since lock1 holds the lock
            boolean acquired = lock2.tryLock(500, TimeUnit.MILLISECONDS);
            assertThat(acquired).isFalse();
        } finally {
            lock1.unlock();
        }
    }

    @Test
    void unlock_throwsWhenNotHeldByCurrentThread() {
        ElasticsearchDocumentLock lock = new ElasticsearchDocumentLock(operations);

        assertThatThrownBy(lock::unlock)
                .isInstanceOf(IllegalMonitorStateException.class);
    }

    @Test
    void lock_blocksUntilLockReleased() throws Exception {
        ElasticsearchDocumentLock lock1 = new ElasticsearchDocumentLock(operations, Duration.ofMillis(50));
        lock1.lock();

        // use separate client for second lock to simulate another process
        RestClient client2 = createRestClient();
        RestClientOperations operations2 = new RestClientOperations(client2, MIGRATION_KEY);
        ElasticsearchDocumentLock lock2 = new ElasticsearchDocumentLock(operations2, Duration.ofMillis(50));

        AtomicBoolean lock2Acquired = new AtomicBoolean(false);
        CountDownLatch lock2Started = new CountDownLatch(1);
        CountDownLatch lock2Done = new CountDownLatch(1);

        Thread t = new Thread(() -> {
            lock2Started.countDown();
            lock2.lock();
            lock2Acquired.set(true);
            lock2.unlock();
            lock2Done.countDown();
        });
        t.start();

        // wait for the thread to start attempting the lock
        lock2Started.await(5, TimeUnit.SECONDS);
        Thread.sleep(200); // give it time to actually attempt

        // lock2 should still be blocked
        assertThat(lock2Acquired.get()).isFalse();

        // release lock1
        lock1.unlock();

        // lock2 should now acquire
        boolean completed = lock2Done.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(lock2Acquired.get()).isTrue();

        client2.close();
    }
}
