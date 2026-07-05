import { setTimeout as sleep } from "node:timers/promises";
import type { RestClientOperations } from "./operations.js";

const IDLE_BETWEEN_TRIES_MS = 100;

// Thrown by unlock() when the lock is not held — expected during Esque.close() after a clean run.
export class LockNotHeldError extends Error {}

// Distributed lock via ES op_type=create. The JVM wraps this in a local ReentrantLock for
// thread safety; Node is single-threaded, so a held-flag suffices — it exists to make
// unlock() without tryLock() a detectable error, and to avoid deleting another process's
// lock document from a process that never acquired it.
export class ElasticsearchDocumentLock {
  private readonly operations: RestClientOperations;
  private held = false;

  constructor(operations: RestClientOperations) {
    this.operations = operations;
  }

  async tryLock(timeoutMinutes: number): Promise<boolean> {
    const deadline = Date.now() + timeoutMinutes * 60_000;
    for (;;) {
      if (await this.doLock()) {
        this.held = true;
        return true;
      }
      if (Date.now() >= deadline) {
        return false;
      }
      await sleep(IDLE_BETWEEN_TRIES_MS);
    }
  }

  async unlock(): Promise<void> {
    if (!this.held) {
      throw new LockNotHeldError("cannot release un-acquired lock");
    }
    this.held = false;
    try {
      await this.operations.deleteLockRecord();
    } catch (error) {
      throw new Error("Failed to release mutex", { cause: error });
    }
  }

  private async doLock(): Promise<boolean> {
    try {
      await this.operations.createLockRecord();
      return true;
    } catch {
      // TODO: differentiate ConflictError (lock exists) from other failures
      return false;
    }
  }
}
