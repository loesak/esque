from __future__ import annotations

import sys

import click
from elasticsearch import Elasticsearch

from esque.configuration import EsqueConfiguration
from esque.esque import Esque


@click.command(name="esque", help="Run Elasticsearch migrations.")
@click.option("--es-url", required=True, help="Elasticsearch URL (e.g. http://localhost:9200)")
@click.option("--migrations-dir", required=True, help="Path to directory containing migration YAML files")
@click.option("--migration-key", required=True, help="Unique key scoping this migration set")
@click.option("--migration-user", default=None, help="User to record on each migration record")
@click.option("--lock-timeout-minutes", default=5, type=int, help="Lock acquisition timeout in minutes")
@click.option(
    "--property",
    "properties",
    multiple=True,
    help="Template substitution property as key=value (repeatable)",
)
def main(
    es_url: str,
    migrations_dir: str,
    migration_key: str,
    migration_user: str | None,
    lock_timeout_minutes: int,
    properties: tuple[str, ...],
) -> None:
    props: dict[str, str] = {}
    for p in properties:
        parts = p.split("=", 1)
        if len(parts) != 2 or not parts[0]:
            raise click.BadParameter(f"must be in key=value format, got: {p!r}", param_hint="--property")
        props[parts[0]] = parts[1]

    configuration = EsqueConfiguration(
        migration_key=migration_key,
        migration_user=migration_user,
        migration_directory=f"file:{migrations_dir}",
        lock_timeout_minutes=lock_timeout_minutes,
    )

    try:
        with Esque(
            client=Elasticsearch(es_url),
            configuration=configuration,
            properties=props,
        ) as esque:
            esque.execute()
    except Exception as exc:
        click.echo(f"Error: {exc}", err=True)
        sys.exit(1)
