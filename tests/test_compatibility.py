import pytest

from helpers import (
    INTEGRITY_MISSING_MIGRATIONS,
    INTEGRITY_MODIFIED_MIGRATIONS,
    ORDERING_MIGRATIONS,
    SINGLE_MIGRATION,
    STANDARD_MIGRATIONS,
    TEMPLATED_MIGRATIONS,
    Implementation,
    all_implementations,
    assert_index_exists,
    get_records,
    run,
)


def implementations() -> pytest.MarkDecorator:
    return pytest.mark.parametrize(
        "impl",
        all_implementations(),
        ids=lambda i: i.name,
    )


# ---------------------------------------------------------------------------
# Initialization
# ---------------------------------------------------------------------------


@implementations()
def test_esque_management_index_created(impl: Implementation, es_url: str) -> None:
    result = run(impl, es_url, key="init-test", migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, ".esque")


# ---------------------------------------------------------------------------
# Execution
# ---------------------------------------------------------------------------


@implementations()
def test_all_migrations_run(impl: Implementation, es_url: str) -> None:
    result = run(impl, es_url, key="run-all-test", migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, "test-index-v1")
    assert_index_exists(es_url, "test-index-v2")
    assert_index_exists(es_url, "test-index-v3")


@implementations()
def test_idempotent_execution(impl: Implementation, es_url: str) -> None:
    key = "idempotent-test"

    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"First run failed:\n{result.stderr}"
    first = get_records(es_url, key)
    assert len(first) == 3

    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"Second run failed:\n{result.stderr}"
    second = get_records(es_url, key)
    assert len(second) == 3

    for a, b in zip(first, second, strict=True):
        assert a["checksum"] == b["checksum"], "Checksum changed between runs"
        assert a["installedOn"] == b["installedOn"], "installedOn changed between runs"


@implementations()
def test_different_migration_keys_are_independent(impl: Implementation, es_url: str) -> None:
    key_a = "independent-key-a"
    key_b = "independent-key-b"

    result = run(impl, es_url, key=key_a, migrations_dir=SINGLE_MIGRATION)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    assert len(get_records(es_url, key_a)) == 1
    assert len(get_records(es_url, key_b)) == 0


# ---------------------------------------------------------------------------
# Migration history metadata
# ---------------------------------------------------------------------------


@implementations()
def test_migration_history_record_count(impl: Implementation, es_url: str) -> None:
    key = "history-count-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert len(get_records(es_url, key)) == 3


@implementations()
def test_migration_history_metadata(impl: Implementation, es_url: str) -> None:
    key = "history-metadata-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    records = get_records(es_url, key)
    assert len(records) == 3

    assert records[0]["order"] == 0
    assert records[0]["filename"] == "V1.0.0__CreateFirstIndex.yml"
    assert records[0]["version"] == "1.0.0"
    assert records[0]["description"] == "CreateFirstIndex"
    assert records[0]["migrationKey"] == key

    assert records[1]["order"] == 1
    assert records[1]["filename"] == "V1.1.0__CreateSecondIndex.yml"
    assert records[1]["version"] == "1.1.0"

    assert records[2]["order"] == 2
    assert records[2]["filename"] == "V2.0.0__CreateThirdIndex.yml"
    assert records[2]["version"] == "2.0.0"


@implementations()
def test_migration_history_checksum_is_present(impl: Implementation, es_url: str) -> None:
    key = "history-checksum-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("checksum") is not None, f"checksum missing on record {record['filename']}"


@implementations()
def test_migration_history_execution_time_is_non_negative(impl: Implementation, es_url: str) -> None:
    key = "history-exectime-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record["executionTime"] >= 0, f"executionTime is negative on record {record['filename']}"


@implementations()
def test_migration_history_installed_on_is_present(impl: Implementation, es_url: str) -> None:
    key = "history-installedon-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("installedOn") is not None, f"installedOn missing on record {record['filename']}"


@implementations()
def test_migration_user_recorded_when_provided(impl: Implementation, es_url: str) -> None:
    key = "user-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS, user="test-user")
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("installedBy") == "test-user", (
            f"Expected installedBy='test-user' on {record['filename']}, got {record.get('installedBy')!r}"
        )


@implementations()
def test_migration_user_null_when_not_provided(impl: Implementation, es_url: str) -> None:
    key = "no-user-test"
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    for record in get_records(es_url, key):
        assert record.get("installedBy") is None, (
            f"Expected installedBy=null on {record['filename']}, got {record.get('installedBy')!r}"
        )


# ---------------------------------------------------------------------------
# Template variable substitution
# ---------------------------------------------------------------------------


@implementations()
def test_template_substitution_creates_correct_index(impl: Implementation, es_url: str) -> None:
    result = run(
        impl,
        es_url,
        key="template-test",
        migrations_dir=TEMPLATED_MIGRATIONS,
        properties={"indexName": "test-index-v4"},
    )
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, "test-index-v4")


@implementations()
def test_extra_template_properties_ignored(impl: Implementation, es_url: str) -> None:
    result = run(
        impl,
        es_url,
        key="extra-props-test",
        migrations_dir=STANDARD_MIGRATIONS,
        properties={"unused": "ignored", "alsoUnused": "alsoIgnored"},
    )
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"
    assert_index_exists(es_url, "test-index-v1")


@implementations()
def test_missing_template_property_fails_before_any_migration(impl: Implementation, es_url: str) -> None:
    key = "missing-var-test"
    result = run(
        impl,
        es_url,
        key=key,
        migrations_dir=TEMPLATED_MIGRATIONS,
        # no properties — #{indexName} is unresolvable
    )
    assert result.returncode != 0, "Expected esque to fail with missing template variable, but it succeeded"
    assert len(get_records(es_url, key)) == 0, "No migration records should be written when template validation fails"


# ---------------------------------------------------------------------------
# Integrity verification
# ---------------------------------------------------------------------------


@implementations()
def test_integrity_checksum_mismatch_causes_failure(impl: Implementation, es_url: str) -> None:
    key = "checksum-mismatch-test"

    # First run: apply standard migrations
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"First run failed:\n{result.stderr}"
    assert len(get_records(es_url, key)) == 3

    # Second run: same filenames, V1.0.0 has different content → checksum mismatch
    result = run(impl, es_url, key=key, migrations_dir=INTEGRITY_MODIFIED_MIGRATIONS)
    assert result.returncode != 0, "Expected esque to fail due to checksum mismatch, but it succeeded"


@implementations()
def test_integrity_fewer_files_than_records_causes_failure(impl: Implementation, es_url: str) -> None:
    key = "fewer-files-test"

    # First run: apply all 3 standard migrations
    result = run(impl, es_url, key=key, migrations_dir=STANDARD_MIGRATIONS)
    assert result.returncode == 0, f"First run failed:\n{result.stderr}"
    assert len(get_records(es_url, key)) == 3

    # Second run: only 2 migration files — 3 records but 2 files → should fail
    result = run(impl, es_url, key=key, migrations_dir=INTEGRITY_MISSING_MIGRATIONS)
    assert result.returncode != 0, "Expected esque to fail when migration records outnumber local files"


# ---------------------------------------------------------------------------
# Version ordering
# ---------------------------------------------------------------------------


@implementations()
def test_version_ordering_is_numeric_not_lexicographic(impl: Implementation, es_url: str) -> None:
    key = "ordering-test"
    result = run(impl, es_url, key=key, migrations_dir=ORDERING_MIGRATIONS)
    assert result.returncode == 0, f"esque failed:\n{result.stderr}"

    records = get_records(es_url, key)
    assert len(records) == 2

    # V1.9.0 must come before V1.10.0 (numeric), not after (lexicographic)
    assert records[0]["version"] == "1.9.0", f"Expected first record to be V1.9.0 but got V{records[0]['version']}"
    assert records[1]["version"] == "1.10.0", f"Expected second record to be V1.10.0 but got V{records[1]['version']}"
