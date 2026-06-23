from __future__ import annotations

import re

from esque.migration.model import MigrationFile, MigrationFileContents, MigrationFileRequestDefinition

_PLACEHOLDER_PATTERN = re.compile(r"#\{([a-zA-Z0-9._\-]+)}")


class MigrationTemplateResolver:
    def __init__(self, properties: dict[str, str]) -> None:
        self._properties = properties

    def validate(self, files: list[MigrationFile]) -> None:
        missing: set[str] = set()
        for file in files:
            for req in file.contents.requests:
                texts = [req.path, req.content_type or "", req.body or "", *(req.params or {}).values()]
                for text in texts:
                    for match in _PLACEHOLDER_PATTERN.finditer(text):
                        key = match.group(1)
                        if key not in self._properties:
                            missing.add(key)
        if missing:
            raise ValueError(f"migration files reference template variables with no matching properties: {missing}")

    def resolve(self, definition: MigrationFileRequestDefinition) -> MigrationFileRequestDefinition:
        return MigrationFileRequestDefinition(
            method=definition.method,
            path=self._substitute(definition.path),
            content_type=self._substitute(definition.content_type) if definition.content_type else None,
            params={k: self._substitute(v) for k, v in definition.params.items()} if definition.params else None,
            body=self._substitute(definition.body) if definition.body else None,
        )

    def resolve_contents(self, contents: MigrationFileContents) -> MigrationFileContents:
        return MigrationFileContents(requests=[self.resolve(r) for r in contents.requests])

    def _substitute(self, text: str) -> str:
        def replace(match: re.Match[str]) -> str:
            key = match.group(1)
            if key not in self._properties:
                raise ValueError(f"unresolved template variable '#{{{key}}}' — was validate() called?")
            return self._properties[key]

        return _PLACEHOLDER_PATTERN.sub(replace, text)
