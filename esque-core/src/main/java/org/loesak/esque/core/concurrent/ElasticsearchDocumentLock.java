package org.loesak.esque.core.concurrent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.loesak.esque.core.elasticsearch.RestClientOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/*
 * Modeled after the Spring Lock implmentations for JDBC (mostly), Zookeeper, and Redis found here:
 * https://github.com/spring-projects/spring-integration/blob/master/spring-integration-jdbc/src/main/java/org/springframework/integration/jdbc/lock/JdbcLockRegistry.java#L102
 * https://github.com/spring-projects/spring-integration/blob/master/spring-integration-zookeeper/src/main/java/org/springframework/integration/zookeeper/lock/ZookeeperLockRegistry.java#L216
 * https://github.com/spring-projects/spring-integration/blob/master/spring-integration-redis/src/main/java/org/springframework/integration/redis/util/RedisLockRegistry.java#L181
 */
// TODO: log the shit out of this class
@Slf4j
@RequiredArgsConstructor
public class ElasticsearchDocumentLock implements Lock {

    private static final Duration DEFAULT_IDLE_BETWEEN_TRIES = Duration.ofMillis(100);

    private final ReentrantLock delegate = new ReentrantLock();

    private final RestClientOperations operations;
    private final Duration idleBetweenTries;

    public ElasticsearchDocumentLock(final RestClientOperations operations) {
        this(operations, DEFAULT_IDLE_BETWEEN_TRIES);
    }

    @Override
    public void lock() {
        this.delegate.lock();
        while (true) {
            try {
                while (!doLock()) {
                    Thread.sleep(this.idleBetweenTries.toMillis());
                }
                break;
            } catch (InterruptedException e) {
                /*
                 * This method must be uninterruptible so catch and ignore
                 * interrupts and only break out of the while loop when
                 * we get the lock.
                 */
            } catch (Exception e) {
                this.delegate.unlock();
                rethrowAsLockException(e);
            }
        }
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        this.delegate.lockInterruptibly();
        while (true) {
            try {
                while (!doLock()) {
                    Thread.sleep(this.idleBetweenTries.toMillis());
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                }
                break;
            } catch (InterruptedException ie) {
                this.delegate.unlock();
                Thread.currentThread().interrupt();
                throw ie;
            } catch (Exception e) {
                this.delegate.unlock();
                rethrowAsLockException(e);
            }
        }
    }

    @Override
    public boolean tryLock() {
        try {
            return tryLock(0, TimeUnit.MICROSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit timeUnit) throws InterruptedException {
        long now = System.currentTimeMillis();

        if (!this.delegate.tryLock(time, timeUnit)) {
            return false;
        }

        long expire = now + TimeUnit.MILLISECONDS.convert(time, timeUnit);

        boolean acquired;
        while (true) {
            try {
                while (!(acquired = doLock()) && System.currentTimeMillis() < expire) {
                    Thread.sleep(this.idleBetweenTries.toMillis());
                }

                if (!acquired) {
                    this.delegate.unlock();
                }

                return acquired;
            } catch (Exception e) {
                this.delegate.unlock();
                rethrowAsLockException(e);
            }
        }
    }

    @Override
    public void unlock() {
        if (!this.delegate.isHeldByCurrentThread()) {
            throw new IllegalMonitorStateException("You do not own mutex");
        }

        if (this.delegate.getHoldCount() > 1) {
            this.delegate.unlock();
            return;
        }

        try  {
            this.operations.deleteLockRecord();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to release mutex");
        } finally {
            this.delegate.unlock();
        }
    }

    @Override
    public Condition newCondition() {
        throw new UnsupportedOperationException("Conditions are not supported");
    }

    /*
     TODO:
     i'm not entirely sure what happens when there is contention with this type of request. I'm currently
     assuming that Elasticsearch will ensure that the first one will win and all others will fail. However,
     this should be tested
     */
    private boolean doLock() {
        try {
            this.operations.createLockRecord();
            return true;
        } catch (Exception e) {
            log.info("Failed to acquire lock", e);
            return false;
        }
    }

    private void rethrowAsLockException(Exception e) {
        throw new RuntimeException("Failed to lock mutex", e);
    }
}
