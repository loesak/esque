from __future__ import annotations

import hashlib
import json
import re
import struct
from pathlib import Path
from typing import Any, cast

import yaml

from esque.migration.model import (
    MigrationFile,
    MigrationFileContents,
    MigrationFileMetadata,
    MigrationFileRequestDefinition,
)
from esque.migration.template import MigrationTemplateResolver

_FILE_NAME_PATTERN = re.compile(r"^V((\d+\.?)+)__(\w+)\.yml$")


class MigrationFileLoader:
    def __init__(self, migration_directory: str, template_resolver: MigrationTemplateResolver) -> None:
        self._migration_directory = migration_directory
        self._template_resolver = template_resolver

    def load(self) -> list[MigrationFile]:
        path = self._resolve_path(self._migration_directory)
        raw_files: list[MigrationFile] = []
        for file_path in path.iterdir():
            if not file_path.is_file():
                continue
            match = _FILE_NAME_PATTERN.match(file_path.name)
            if not match:
                continue
            raw_files.append(self._read_raw(file_path, match))

        raw_files.sort()
        self._template_resolver.validate(raw_files)
        return [self._resolve(f) for f in raw_files]

    def _resolve(self, file: MigrationFile) -> MigrationFile:
        resolved_contents = self._template_resolver.resolve_contents(file.contents)
        return MigrationFile(
            metadata=MigrationFileMetadata(
                filename=file.metadata.filename,
                version=file.metadata.version,
                description=file.metadata.description,
                checksum=self.calculate_checksum(resolved_contents),
            ),
            contents=resolved_contents,
        )

    @staticmethod
    def _resolve_path(directory: str) -> Path:
        if directory.startswith("file:"):
            return Path(directory.removeprefix("file:"))
        raise ValueError(f"unsupported migration directory scheme in '{directory}'. supported schemes: 'file:'")

    @staticmethod
    def _read_raw(path: Path, match: re.Match[str]) -> MigrationFile:
        data: dict[str, Any] = yaml.safe_load(path.read_text())
        return MigrationFile(
            metadata=MigrationFileMetadata(
                filename=path.name,
                version=match.group(1),
                description=match.group(3),
                checksum=0,
            ),
            contents=MigrationFileContents(requests=[MigrationFileLoader._parse_request(r) for r in data["requests"]]),
        )

    @staticmethod
    def _parse_request(raw: dict[str, Any]) -> MigrationFileRequestDefinition:
        return MigrationFileRequestDefinition(
            method=str(raw["method"]),
            path=str(raw["path"]),
            content_type=str(raw["contentType"]) if "contentType" in raw else None,
            params={str(k): str(v) for k, v in raw["params"].items()} if "params" in raw else None,
            body=str(raw["body"]) if "body" in raw else None,
        )

    @staticmethod
    def _remove_nulls(obj: Any) -> Any:
        if isinstance(obj, dict):
            d = cast(dict[str, Any], obj)
            return {k: MigrationFileLoader._remove_nulls(v) for k, v in d.items() if v is not None}
        if isinstance(obj, list):
            lst = cast(list[Any], obj)
            return [MigrationFileLoader._remove_nulls(item) for item in lst]
        return obj

    @staticmethod
    def calculate_checksum(contents: MigrationFileContents) -> int:
        data = {"requests": [r.to_canonical_dict() for r in contents.requests]}
        canonical = json.dumps(
            MigrationFileLoader._remove_nulls(data),
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        ).encode("utf-8")
        digest = hashlib.md5(canonical, usedforsecurity=False).digest()
        return struct.unpack(">i", digest[:4])[0]
