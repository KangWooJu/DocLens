"""ObjectStore 한 번만 쓰기와 S3Mock 적합성 기록"""

import base64
import hashlib
from typing import Any

import pytest
from botocore.exceptions import ClientError

from collector.store import object_store
from collector.store.object_store import ObjectConflict, ObjectStore, s3_client
from tests.conftest import BUCKET


def raw_key(body: bytes) -> str:
    return f"raw/spring-boot/4.1.3/{hashlib.sha256(body).hexdigest()}.html"


def test_put_once_adopts_same_bytes_and_rejects_others(store: ObjectStore) -> None:
    body = b"<html>4.1.3</html>"
    ref = store.put_once(raw_key(body), body)

    assert ref.content_hash == "sha256:" + (
        hashlib
        .sha256(body)
        .hexdigest()
    )
    assert ref.size_bytes == len(body)
    assert store.put_once(raw_key(body), body) == ref
    assert store.get(raw_key(body)) == body

    manifest = "manifests/run/batch/envelope.json"
    store.put_once(manifest, b"{}")

    with pytest.raises(ObjectConflict):
        store.put_once(manifest, b"{\"changed\":true}")

    assert store.get(manifest) == b"{}"


def test_git_blob_raw_key_is_text(store: ObjectStore) -> None:
    body = b"version=4.1.2\n"
    key = object_store.raw_key(
        "spring-boot",
        "4.1.2",
        body,
        "git-blob",
    )

    assert key == f"raw/spring-boot/4.1.2/{hashlib.sha256(body).hexdigest()}.txt"
    assert object_store.raw_key(
        "spring-boot",
        "4.1.2",
        body,
        "html",
    ) == key.removesuffix(".txt") + ".html"
    assert store.put_once(key, body).object_key == key


@pytest.mark.parametrize(
    "key",
    [
        "packs/spring-boot/x.pack",
        "raw/spring-boot/4.1.3/" + (
            hashlib
            .sha256(b"x")
            .hexdigest()
        ) + ".kt",
        "raw/spring-boot/4.1.3/" + "0" * 64 + ".html",
        "raw/spring-boot/4.1/" + (
            hashlib
            .sha256(b"x")
            .hexdigest()
        ) + ".html",
        "manifests/../raw/x.json",
    ],
)
def test_put_once_rejects_keys(
    store: ObjectStore,
    key: str,
) -> None:
    with pytest.raises(ValueError):
        store.put_once(key, b"x")


def test_get_missing_key_is_none(store: ObjectStore) -> None:
    assert store.get("manifests/missing/ready-run.json") is None


def test_missing_bucket_is_an_error(s3_endpoint: str) -> None:
    with pytest.raises(ClientError):
        ObjectStore(s3_client(
            s3_endpoint,
            "local",
            "local",
        ), "no-such-bucket")


def test_s3_conformance(
    s3_endpoint: str,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """적합성 기록: 결과와 무관하게 put_once 시험은 통과해야 하는 항목은 출력만"""

    client: Any = s3_client(
        s3_endpoint,
        "local",
        "local",
    )
    body = b"conformance"
    checksum = (
        base64
        .b64encode(
            hashlib
            .sha256(body)
            .digest()
        )
        .decode("ascii")
    )
    results = {}

    with pytest.raises(ClientError) as missing:
        client.get_object(Bucket=BUCKET, Key="manifests/conformance/missing.json")

    assert missing.value.response["Error"]["Code"] == "NoSuchKey"
    assert missing.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404
    results["1 missing GET"] = "404 NoSuchKey"

    key = "manifests/conformance/if-none-match.json"
    client.put_object(
        Bucket=BUCKET,
        Key=key,
        Body=body,
        IfNoneMatch="*",
    )

    try:
        client.put_object(
            Bucket=BUCKET,
            Key=key,
            Body=body,
            IfNoneMatch="*",
        )
        results["2 If-None-Match"] = "second PUT accepted (not enforced)"
    except ClientError as error:
        results["2 If-None-Match"] = f"second PUT {error.response['ResponseMetadata']['HTTPStatusCode']}"

    key = "manifests/conformance/checksum.json"
    client.put_object(
        Bucket=BUCKET,
        Key=key,
        Body=body,
        ChecksumSHA256=checksum,
    )
    head = client.head_object(
        Bucket=BUCKET,
        Key=key,
        ChecksumMode="ENABLED",
    )
    results["3 HEAD checksum"] = "returned" if head.get("ChecksumSHA256") == checksum else "absent, GET fallback"

    wrong = (
        base64
        .b64encode(
            hashlib
            .sha256(b"other")
            .digest()
        )
        .decode("ascii")
    )

    try:
        client.put_object(
            Bucket=BUCKET,
            Key="manifests/conformance/wrong.json",
            Body=body,
            ChecksumSHA256=wrong,
        )
        results["4 wrong checksum"] = "accepted"
    except ClientError as error:
        results["4 wrong checksum"] = f"rejected {error.response['ResponseMetadata']['HTTPStatusCode']}"

    large = bytes(range(256)) * 4096
    client.put_object(
        Bucket=BUCKET,
        Key="manifests/conformance/large.bin",
        Body=large,
    )
    stored = client.get_object(Bucket=BUCKET, Key="manifests/conformance/large.bin")["Body"].read()

    assert stored == large
    results["5 1MiB PUT"] = "ok"

    with capsys.disabled():
        print()

        for name, result in results.items():
            print(f"s3 conformance {name}: {result}")
