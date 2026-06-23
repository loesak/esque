from __future__ import annotations

import logging
import threading
import time

from esque.elasticsearch.operations import RestClientOperations

log = logging.getLogger(__name__)

_IDLE_BETWEEN_TRIES = 0.1  # seconds — mirrors ElasticsearchDocumentLock.DEFAULT_IDLE_BETWEEN_TRIES


class ElasticsearchDocumentLock:
    def __init__(self, operations: RestClientOperations) -> None:
        self._operations = operations
        self._delegate = threading.RLock()

    def try_lock(self, timeout_minutes: int) -> bool:
        deadline = time.monotonic() + timeout_minutes * 60
        remaining = max(0.0, deadline - time.monotonic())

        if not self._delegate.acquire(blocking=True, timeout=remaining):
            return False

        while True:
            try:
                if self._do_lock():
                    return True
            except Exception:
                self._delegate.release()
                raise

            if time.monotonic() >= deadline:
                self._delegate.release()
                return False

            time.sleep(_IDLE_BETWEEN_TRIES)

    def unlock(self) -> None:
        try:
            self._operations.delete_lock_record()
        except Exception as e:
            raise RuntimeError("Failed to release mutex") from e
        finally:
            self._delegate.release()

    def _do_lock(self) -> bool:
        try:
            self._operations.create_lock_record()
            return True
        except Exception as e:
            # TODO: differentiate ConflictError (lock exists) from other failures
            log.debug("Failed to acquire lock: %s", e)
            return False
