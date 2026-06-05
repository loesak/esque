package org.loesak.esque.core.concurrent

import io.github.oshai.kotlinlogging.KotlinLogging
import org.loesak.esque.core.elasticsearch.RestClientOperations
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock

/*
 * Modeled after the Spring Lock implementation for JDBC (mostly), Zookeeper, and Redis found here:
 * https://github.com/spring-projects/spring-integration/blob/master/spring-integration-jdbc/src/main/java/org/springframework/integration/jdbc/lock/JdbcLockRegistry.java#L102
 * https://github.com/spring-projects/spring-integration/blob/master/spring-integration-zookeeper/src/main/java/org/springframework/integration/zookeeper/lock/ZookeeperLockRegistry.java#L216
 * https://github.com/spring-projects/spring-integration/blob/master/spring-integration-redis/src/main/java/org/springframework/integration/redis/util/RedisLockRegistry.java#L181
 */
private val log = KotlinLogging.logger {}

class ElasticsearchDocumentLock(
    private val operations: RestClientOperations,
    private val idleBetweenTries: Duration = DEFAULT_IDLE_BETWEEN_TRIES,
) : Lock {

    private val delegate = ReentrantLock()

    override fun lock() {
        delegate.lock()
        while (true) {
            try {
                while (!doLock()) {
                    Thread.sleep(idleBetweenTries.toMillis())
                }
                break
            } catch (e: InterruptedException) {
                // must be uninterruptible — catch and ignore, only break when lock acquired
            } catch (e: Exception) {
                delegate.unlock()
                rethrowAsLockException(e)
            }
        }
    }

    override fun lockInterruptibly() {
        delegate.lockInterruptibly()
        while (true) {
            try {
                while (!doLock()) {
                    Thread.sleep(idleBetweenTries.toMillis())
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                }
                break
            } catch (e: InterruptedException) {
                delegate.unlock()
                Thread.currentThread().interrupt()
                throw e
            } catch (e: Exception) {
                delegate.unlock()
                rethrowAsLockException(e)
            }
        }
    }

    override fun tryLock(): Boolean {
        return try {
            tryLock(0, TimeUnit.MICROSECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    override fun tryLock(time: Long, timeUnit: TimeUnit): Boolean {
        val now = System.currentTimeMillis()

        if (!delegate.tryLock(time, timeUnit)) return false

        val expire = now + TimeUnit.MILLISECONDS.convert(time, timeUnit)

        while (true) {
            try {
                var acquired: Boolean
                while (!doLock().also { acquired = it } && System.currentTimeMillis() < expire) {
                    Thread.sleep(idleBetweenTries.toMillis())
                }
                if (!acquired) delegate.unlock()
                return acquired
            } catch (e: Exception) {
                delegate.unlock()
                rethrowAsLockException(e)
            }
        }
    }

    override fun unlock() {
        if (!delegate.isHeldByCurrentThread) throw IllegalMonitorStateException("You do not own mutex")

        if (delegate.holdCount > 1) {
            delegate.unlock()
            return
        }

        try {
            operations.deleteLockRecord()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to release mutex")
        } finally {
            delegate.unlock()
        }
    }

    override fun newCondition(): Condition {
        throw UnsupportedOperationException("Conditions are not supported")
    }

    /*
     i'm not entirely sure what happens when there is contention with this type of request. I'm currently
     assuming that Elasticsearch will ensure that the first one will win and all others will fail. However,
     this should be tested
     */
    private fun doLock(): Boolean {
        return try {
            operations.createLockRecord()
            true
        } catch (e: Exception) {
            // TODO: should look at the exception type to see if the exception is due to the lock already existing or some other non-recoverable exception
            log.debug(e) { "Failed to acquire lock" }
            false
        }
    }

    private fun rethrowAsLockException(e: Exception): Nothing {
        throw RuntimeException("Failed to lock mutex", e)
    }

    companion object {
        private val DEFAULT_IDLE_BETWEEN_TRIES = Duration.ofMillis(100)
    }
}
