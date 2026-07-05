import assert from "node:assert/strict";
import { test } from "node:test";
import { ElasticsearchDocumentLock, LockNotHeldError } from "../src/elasticsearch/lock.js";
import type { RestClientOperations } from "../src/elasticsearch/operations.js";

function fakeOperations(overrides: Partial<RestClientOperations> = {}): RestClientOperations {
  return {
    createLockRecord: async () => {},
    deleteLockRecord: async () => {},
    ...overrides,
  } as RestClientOperations;
}

test("tryLock succeeds immediately when createLockRecord succeeds", async () => {
  const lock = new ElasticsearchDocumentLock(fakeOperations());
  assert.equal(await lock.tryLock(1), true);
});

test("unlock deletes the lock record after a successful tryLock", async () => {
  let deleted = false;
  const lock = new ElasticsearchDocumentLock(
    fakeOperations({
      deleteLockRecord: async () => {
        deleted = true;
      },
    }),
  );
  await lock.tryLock(1);
  await lock.unlock();
  assert.equal(deleted, true);
});

test("unlock throws LockNotHeldError when called without a prior successful tryLock", async () => {
  const lock = new ElasticsearchDocumentLock(fakeOperations());
  await assert.rejects(() => lock.unlock(), LockNotHeldError);
});

test("unlock throws LockNotHeldError again after a first successful unlock", async () => {
  const lock = new ElasticsearchDocumentLock(fakeOperations());
  await lock.tryLock(1);
  await lock.unlock();
  await assert.rejects(() => lock.unlock(), LockNotHeldError);
});

test("tryLock retries after a failed acquisition and eventually succeeds", async () => {
  let attempts = 0;
  const lock = new ElasticsearchDocumentLock(
    fakeOperations({
      createLockRecord: async () => {
        attempts += 1;
        if (attempts < 3) {
          throw new Error("lock exists");
        }
      },
    }),
  );
  const acquired = await lock.tryLock(1);
  assert.equal(acquired, true);
  assert.equal(attempts, 3);
});

test("tryLock returns false when the timeout elapses before acquisition", async () => {
  const lock = new ElasticsearchDocumentLock(
    fakeOperations({
      createLockRecord: async () => {
        throw new Error("lock exists");
      },
    }),
  );
  // timeoutMinutes=0 means the deadline is already in the past after the first failed attempt.
  const acquired = await lock.tryLock(0);
  assert.equal(acquired, false);
});

test("unlock wraps deleteLockRecord failures and still marks the lock as released", async () => {
  const lock = new ElasticsearchDocumentLock(
    fakeOperations({
      deleteLockRecord: async () => {
        throw new Error("network error");
      },
    }),
  );
  await lock.tryLock(1);
  await assert.rejects(() => lock.unlock(), /Failed to release mutex/);
  // Even though deleteLockRecord failed, the local held-flag was already cleared before the
  // attempt (matching the plan's unlock() ordering), so a second unlock() call throws
  // LockNotHeldError, not another "Failed to release mutex".
  await assert.rejects(() => lock.unlock(), LockNotHeldError);
});
