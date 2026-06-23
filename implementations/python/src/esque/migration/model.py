from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class MigrationFileRequestDefinition:
    method: str
    path: str
    content_type: str | None = None
    params: dict[str, str] | None = None
    body: str | None = None

    def to_canonical_dict(self) -> dict[str, Any]:
        d: dict[str, Any] = {"method": self.method, "path": self.path}
        if self.body is not None:
            d["body"] = self.body
        if self.content_type is not None:
            d["contentType"] = self.content_type
        if self.params is not None:
            d["params"] = self.params
        return d


@dataclass
class MigrationFileMetadata:
    filename: str
    version: str
    description: str
    checksum: int = 0


@dataclass
class MigrationFileContents:
    requests: list[MigrationFileRequestDefinition] = field(default_factory=lambda: [])


@dataclass
class MigrationFile:
    metadata: MigrationFileMetadata
    contents: MigrationFileContents

    def _version_tuple(self) -> tuple[int, ...]:
        return tuple(int(x) for x in self.metadata.version.split("."))

    def __lt__(self, other: MigrationFile) -> bool:
        a = self._version_tuple()
        b = other._version_tuple()
        max_len = max(len(a), len(b))
        a_p = a + (0,) * (max_len - len(a))
        b_p = b + (0,) * (max_len - len(b))
        return a_p < b_p if a_p != b_p else self.metadata.description < other.metadata.description

    def __le__(self, other: MigrationFile) -> bool:
        return self == other or self < other

    def __gt__(self, other: MigrationFile) -> bool:
        return not self <= other

    def __ge__(self, other: MigrationFile) -> bool:
        return not self < other
