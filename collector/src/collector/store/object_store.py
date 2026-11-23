"""S3 호환 객체 저장소, 한 번만 쓰기"""

import base64
import hashlib
import re
from dataclasses import dataclass
from typing import Any

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from collector.contract.canonical_json import JsonValue

OBJECT_KEY = re.compile(
    r"(?!.*(?:^|/)\.{1,2}(?:/|$))(raw|discovery|manifests)/[a-zA-Z0-9_@.-]+(?:/[a-zA-Z0-9_@.-]+)*",
)
RAW_KEY = re.compile(
    r"raw/[a-z][a-z0-9-]*/[0-9]+\.[0-9]+\.[0-9]+(?:\.RELEASE)?/(?P<hex>[0-9a-f]{64})\.html",
)
# 조건부 쓰기에 걸린 응답, 같은 key가 이미 있음
EXISTS_CODES = frozenset({"PreconditionFailed", "ConditionalRequestConflict", "412", "409"})


class ObjectConflict(Exception):
    """같은 key에 다른 bytes"""


@dataclass(frozen=True)
class ObjectRef:
    object_key: str
    content_hash: str
    size_bytes: int

    def to_json(self) -> dict[str, JsonValue]:
        return {
            "objectKey": self.object_key,
            "contentHash": self.content_hash,
            "sizeBytes": self.size_bytes,
        }


def s3_client(
    endpoint: str,
    access_key: str,
    secret_key: str,
) -> Any:
    """path-style, checksum은 직접 보내므로 SDK 자동 계산은 필요할 때만"""

    return boto3.client(
        "s3",
        endpoint_url=endpoint,
        aws_access_key_id=access_key,
        aws_secret_access_key=secret_key,
        region_name="us-east-1",
        config=Config(
            s3={"addressing_style": "path"},
            request_checksum_calculation="when_required",
            response_checksum_validation="when_required",
        ),
    )


class ObjectStore:
    """한 번만 쓰는 객체 저장, 같은 key 같은 bytes는 채택, 다른 bytes는 ObjectConflict"""

    def __init__(
        self,
        client: Any,
        bucket: str,
    ) -> None:
        # bucket이 있는 상태에서만 HEAD 404를 key 없음으로 읽음
        client.head_bucket(Bucket=bucket)
        self._client = client
        self._bucket = bucket

    def put_once(
        self,
        key: str,
        body: bytes,
    ) -> ObjectRef:
        digest = (
            hashlib
            .sha256(body)
            .digest()
        )
        _check_key(key, digest.hex())
        checksum = (
            base64
            .b64encode(digest)
            .decode("ascii")
        )
        ref = ObjectRef(
            object_key=key,
            content_hash="sha256:" + digest.hex(),
            size_bytes=len(body),
        )

        try:
            self._client.put_object(
                Bucket=self._bucket,
                Key=key,
                Body=body,
                IfNoneMatch="*",
                ChecksumSHA256=checksum,
            )
        except ClientError as error:
            if _code(error) not in EXISTS_CODES:
                raise

            if self._stored_checksum(key) != checksum:
                raise ObjectConflict(key) from error

        return ref

    def get(self, key: str) -> bytes | None:
        """NoSuchKey만 None, 그 밖의 오류는 예외"""

        try:
            response = self._client.get_object(
                Bucket=self._bucket,
                Key=key,
            )
        except ClientError as error:
            if _code(error) == "NoSuchKey":
                return None

            raise

        return response["Body"].read()

    def _stored_checksum(self, key: str) -> str:
        head = self._client.head_object(
            Bucket=self._bucket,
            Key=key,
            ChecksumMode="ENABLED",
        )
        stored = head.get("ChecksumSHA256")

        if stored is not None and "-" not in stored:
            return stored

        # 서버가 checksum을 돌려주지 않으면 bytes를 받아 직접 계산
        body = self.get(key)

        if body is None:
            raise ObjectConflict(key)

        return (
            base64
            .b64encode(
                hashlib
                .sha256(body)
                .digest()
            )
            .decode("ascii")
        )


def _check_key(
    key: str,
    hex_digest: str,
) -> None:
    if OBJECT_KEY.fullmatch(key) is None:
        raise ValueError(f"object key not allowed: {key}")

    if key.startswith("raw/"):
        match = RAW_KEY.fullmatch(key)

        if match is None or match.group("hex") != hex_digest:
            raise ValueError(f"raw key must end with the body sha256: {key}")


def _code(error: ClientError) -> str:
    return str(
        error.response
        .get("Error", {})
        .get("Code", "")
    )
