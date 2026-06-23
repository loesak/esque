import dataclasses
from datetime import UTC, datetime
from unittest.mock import MagicMock

import pytest

from esque.configuration import EsqueConfiguration
from esque.elasticsearch.documents import MigrationRecord
from esque.esque import Esque
from esque.migration.model import MigrationFile, MigrationFileContents, MigrationFileMetadata

_SENTINEL_DATE = datetime(2026, 1, 1, tzinfo=UTC)


def _esque(migration_key: str = "test-key") -> Esque:
    return Esque(
        client=MagicMock(),
        configuration=EsqueConfiguration(migration_key=migration_key),
    )


def _file(version: str, description: str = "Test", checksum: int = 42) -> MigrationFile:
    return MigrationFile(
        metadata=MigrationFileMetadata(
            filename=f"V{version}__{description}.yml",
            version=version,
            description=description,
            checksum=checksum,
        ),
        contents=MigrationFileContents(),
    )


def _record(file: MigrationFile, order: int, migration_key: str = "test-key") -> MigrationRecord:
    return MigrationRecord(
        migration_key=migration_key,
        order=order,
        filename=file.metadata.filename,
        version=file.metadata.version,
        description=file.metadata.description,
        checksum=file.metadata.checksum,
        installed_by=None,
        installed_on=_SENTINEL_DATE,
        execution_time=0,
    )


def test_passes_with_no_history() -> None:
    _esque()._verify_state_integrity([_file("1.0.0"), _file("1.1.0")], [])


def test_passes_with_complete_matching_history() -> None:
    f1, f2 = _file("1.0.0"), _file("1.1.0")
    _esque()._verify_state_integrity([f1, f2], [_record(f1, 0), _record(f2, 1)])


def test_passes_with_partial_history() -> None:
    f1, f2 = _file("1.0.0"), _file("1.1.0")
    _esque()._verify_state_integrity([f1, f2], [_record(f1, 0)])


def test_raises_when_more_records_than_files() -> None:
    f = _file("1.0.0")
    with pytest.raises(RuntimeError, match="more migrations"):
        _esque()._verify_state_integrity([], [_record(f, 0)])


def test_raises_on_gap_in_history() -> None:
    f1, f2, f3 = _file("1.0.0"), _file("1.1.0"), _file("1.2.0")
    gap_record = dataclasses.replace(_record(f3, 2))
    with pytest.raises(RuntimeError, match="corrupt"):
        _esque()._verify_state_integrity([f1, f2, f3], [_record(f1, 0), gap_record])


def test_raises_on_checksum_mismatch() -> None:
    f = _file("1.0.0", checksum=42)
    bad_record = dataclasses.replace(_record(f, 0), checksum=999)
    with pytest.raises(RuntimeError, match="integrity"):
        _esque()._verify_state_integrity([f], [bad_record])


def test_raises_on_version_mismatch() -> None:
    f = _file("1.0.0")
    bad_record = dataclasses.replace(_record(f, 0), version="9.9.9")
    with pytest.raises(RuntimeError, match="integrity"):
        _esque()._verify_state_integrity([f], [bad_record])


def test_raises_on_description_mismatch() -> None:
    f = _file("1.0.0", description="Original")
    bad_record = dataclasses.replace(_record(f, 0), description="Modified")
    with pytest.raises(RuntimeError, match="integrity"):
        _esque()._verify_state_integrity([f], [bad_record])


def test_raises_when_file_missing_for_record() -> None:
    f = _file("1.0.0")
    orphan = _record(_file("1.0.0", description="Ghost"), 0)
    with pytest.raises(RuntimeError, match="could not find"):
        _esque()._verify_state_integrity([f], [orphan])
