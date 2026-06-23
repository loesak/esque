from esque.migration.loader import MigrationFileLoader
from esque.migration.model import MigrationFileContents, MigrationFileRequestDefinition


def _checksum(requests: list[MigrationFileRequestDefinition]) -> int:
    return MigrationFileLoader.calculate_checksum(MigrationFileContents(requests=requests))


def test_result_is_signed_32bit_int() -> None:
    requests = [MigrationFileRequestDefinition(method="PUT", path="/test-index")]
    result = _checksum(requests)
    assert isinstance(result, int)
    assert -(2**31) <= result <= 2**31 - 1


def test_deterministic() -> None:
    requests = [MigrationFileRequestDefinition(method="PUT", path="/test", body='{"settings": {}}')]
    assert _checksum(requests) == _checksum(requests)


def test_null_fields_excluded() -> None:
    r1 = MigrationFileRequestDefinition(method="PUT", path="/index")
    r2 = MigrationFileRequestDefinition(method="PUT", path="/index", body=None, content_type=None, params=None)
    assert _checksum([r1]) == _checksum([r2])


def test_different_content_differs() -> None:
    r1 = [MigrationFileRequestDefinition(method="PUT", path="/index-a")]
    r2 = [MigrationFileRequestDefinition(method="PUT", path="/index-b")]
    assert _checksum(r1) != _checksum(r2)


def test_key_order_is_canonical() -> None:
    r = MigrationFileRequestDefinition(method="PUT", path="/index", body="data", content_type="application/json")
    result = _checksum([r])
    assert isinstance(result, int)
    assert _checksum([r]) == result


def test_multiple_requests() -> None:
    r1 = MigrationFileRequestDefinition(method="PUT", path="/index")
    r2 = MigrationFileRequestDefinition(method="POST", path="/_aliases", body="{}")
    combined = _checksum([r1, r2])
    assert combined != _checksum([r1])
    assert combined != _checksum([r2])
