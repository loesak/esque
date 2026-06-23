from collections.abc import Iterator

import httpx
import pytest
from testcontainers.elasticsearch import ElasticSearchContainer

ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.3.0"


@pytest.fixture(scope="session")
def es_url() -> Iterator[str]:
    with (
        ElasticSearchContainer(ES_IMAGE)
        .with_env("xpack.security.enabled", "false")
        .with_env("action.destructive_requires_name", "false")
        .with_env("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
        .with_env("xpack.ml.enabled", "false")
        .with_env("node.store.allow_mmap", "false")
    ) as es:
        host = es.get_container_host_ip()
        port = es.get_exposed_port(9200)
        yield f"http://{host}:{port}"


@pytest.fixture(autouse=True)
def clean_es(es_url: str) -> None:
    for pattern in ["/.esque", "/test-*"]:
        try:
            httpx.delete(f"{es_url}{pattern}", timeout=10)
        except Exception:
            pass
