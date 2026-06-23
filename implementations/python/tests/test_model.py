from esque.migration.model import MigrationFile, MigrationFileContents, MigrationFileMetadata


def _file(version: str, description: str = "Test") -> MigrationFile:
    return MigrationFile(
        metadata=MigrationFileMetadata(filename=f"V{version}__{description}.yml", version=version, description=description),
        contents=MigrationFileContents(),
    )


def test_numeric_segment_ordering() -> None:
    assert _file("1.9.0") < _file("1.10.0")


def test_major_ordering() -> None:
    assert _file("1.0.0") < _file("2.0.0")


def test_minor_ordering() -> None:
    assert _file("1.0.0") < _file("1.1.0")


def test_patch_ordering() -> None:
    assert _file("1.0.0") < _file("1.0.1")


def test_unequal_segment_count() -> None:
    assert _file("1.0") < _file("1.0.1")


def test_not_less_than_self() -> None:
    assert not (_file("1.0.0") < _file("1.0.0"))


def test_sort_order() -> None:
    files = [_file("1.10.0"), _file("2.0.0"), _file("1.9.0"), _file("1.0.0")]
    assert sorted(files) == [_file("1.0.0"), _file("1.9.0"), _file("1.10.0"), _file("2.0.0")]
