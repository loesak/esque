import pytest

from esque.migration.model import MigrationFile, MigrationFileContents, MigrationFileMetadata, MigrationFileRequestDefinition
from esque.migration.template import MigrationTemplateResolver


def _file(*reqs: MigrationFileRequestDefinition) -> MigrationFile:
    return MigrationFile(
        metadata=MigrationFileMetadata(filename="V1.0.0__Test.yml", version="1.0.0", description="Test"),
        contents=MigrationFileContents(requests=list(reqs)),
    )


def test_validate_passes_when_all_vars_present() -> None:
    req = MigrationFileRequestDefinition(method="PUT", path="/#{indexName}")
    MigrationTemplateResolver({"indexName": "my-index"}).validate([_file(req)])


def test_validate_raises_on_missing_var() -> None:
    req = MigrationFileRequestDefinition(method="PUT", path="/#{missing}")
    with pytest.raises(ValueError, match="missing"):
        MigrationTemplateResolver({}).validate([_file(req)])


def test_validate_collects_all_missing_vars() -> None:
    req = MigrationFileRequestDefinition(method="PUT", path="/#{a}", body="#{b}")
    with pytest.raises(ValueError) as exc_info:
        MigrationTemplateResolver({}).validate([_file(req)])
    msg = str(exc_info.value)
    assert "a" in msg
    assert "b" in msg


def test_validate_checks_body_params_and_content_type() -> None:
    req = MigrationFileRequestDefinition(
        method="POST",
        path="/",
        content_type="#{ct}",
        params={"k": "#{v}"},
        body="#{body}",
    )
    with pytest.raises(ValueError) as exc_info:
        MigrationTemplateResolver({}).validate([_file(req)])
    msg = str(exc_info.value)
    assert "ct" in msg
    assert "v" in msg
    assert "body" in msg


def test_resolve_substitutes_path() -> None:
    req = MigrationFileRequestDefinition(method="PUT", path="/#{indexName}")
    result = MigrationTemplateResolver({"indexName": "my-index"}).resolve(req)
    assert result.path == "/my-index"


def test_resolve_substitutes_body() -> None:
    req = MigrationFileRequestDefinition(method="POST", path="/", body='{"index": "#{name}"}')
    result = MigrationTemplateResolver({"name": "test"}).resolve(req)
    assert result.body == '{"index": "test"}'


def test_resolve_substitutes_params() -> None:
    req = MigrationFileRequestDefinition(method="GET", path="/", params={"q": "#{query}"})
    result = MigrationTemplateResolver({"query": "value"}).resolve(req)
    assert result.params == {"q": "value"}


def test_resolve_substitutes_content_type() -> None:
    req = MigrationFileRequestDefinition(method="PUT", path="/", content_type="#{ct}")
    result = MigrationTemplateResolver({"ct": "application/json"}).resolve(req)
    assert result.content_type == "application/json"


def test_resolve_does_not_substitute_method() -> None:
    req = MigrationFileRequestDefinition(method="PUT", path="/index")
    result = MigrationTemplateResolver({}).resolve(req)
    assert result.method == "PUT"


def test_resolve_handles_no_template_vars() -> None:
    req = MigrationFileRequestDefinition(method="DELETE", path="/index", body='{"key": "value"}')
    result = MigrationTemplateResolver({}).resolve(req)
    assert result.path == "/index"
    assert result.body == '{"key": "value"}'
