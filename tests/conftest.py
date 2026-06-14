import pytest
import httpx
from testcontainers.elasticsearch import ElasticSearchContainer


ES_IMAGE = "docker.elastic.co/elasticsearch/elasticsearch:9.3.0"


@pytest.fixture(scope="session")
def es_url():
    with (
        ElasticSearchContainer(ES_IMAGE)
        .with_env("xpack.security.enabled", "false")
        .with_env("action.destructive_requires_name", "false")
    ) as es:
        host = es.get_container_host_ip()
        port = es.get_exposed_port(9200)
        yield f"http://{host}:{port}"


@pytest.fixture(autouse=True)
def clean_es(es_url):
    for pattern in ["/.esque", "/test-*"]:
        try:
            httpx.delete(f"{es_url}{pattern}", timeout=10)
        except Exception:
            pass
