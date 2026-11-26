package io.github.kangwooju.doclens.pipeline.pack.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.global.store.ObjectStore;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRow;
import io.github.kangwooju.doclens.pipeline.pack.repository.BuildRepository;
import lombok.RequiredArgsConstructor;

/**
 * 발행한 Build의 publication manifest 작성과 한 번만 쓰는 게시
 * manifest bytes는 builds 행과 원본 snapshot 목록만의 함수라 같은 행은 늘 같은 객체
 */
@Service
@RequiredArgsConstructor
public class PublicationService {

    // 초 아래 마이크로초까지 고정 자릿수 UTC
    private static final DateTimeFormatter PUBLISHED_AT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private final BuildRepository buildRepository;
    private final ObjectStore objectStore;

    /** 발행한 Build의 manifest를 Pack 디렉터리 아래 publications/발행 번호.json에 게시, 이미 같은 bytes가 있으면 채택 */
    public ObjectRef ensureManifest(BuildRow published) {

        String manifest = manifest(published);
        byte[] bytes = manifest
                .getBytes(StandardCharsets.UTF_8);
        String objectKey = objectKey(published);
        String checksum = CanonicalJson
                .sha256(manifest);

        try {
            Path file = Files
                    .createTempFile(
                            "publication-",
                            ".json"
                    );

            try {
                Files
                        .write(
                                file,
                                bytes
                        );
                objectStore
                        .publish(
                                objectKey,
                                file,
                                checksum
                        );
            } finally {
                Files
                        .deleteIfExists(file);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        return new ObjectRef(
                objectKey,
                checksum,
                bytes.length
        );
    }

    /** 발행한 Build의 contracts publication-manifest canonical JSON, 발행 번호가 없으면 IllegalArgumentException */
    public String manifest(BuildRow published) {

        if (published
                .publicationSequence() == null) {
            throw new IllegalArgumentException("build " + published
                    .buildId() + " has no publication sequence");
        }

        Map<String, Object> manifest = new HashMap<>();
        manifest
                .put(
                        "schemaVersion",
                        1
                );
        manifest
                .put(
                        "channelKey",
                        "document:" + published
                                .project() + ":" + published
                                        .exactVersion()
                );
        manifest
                .put(
                        "publicationSequence",
                        published
                                .publicationSequence()
                );
        manifest
                .put(
                        "packId",
                        published
                                .packId()
                );
        manifest
                .put(
                        "packType",
                        "DOCUMENT"
                );
        manifest
                .put(
                        "project",
                        published
                                .project()
                );
        manifest
                .put(
                        "exactVersion",
                        published
                                .exactVersion()
                );
        manifest
                .put(
                        "supportedVersions",
                        List
                                .of(published
                                        .exactVersion())
                );
        manifest
                .put(
                        "buildId",
                        published
                                .buildId()
                                .toString()
                );
        manifest
                .put(
                        "packSchemaVersion",
                        1
                );
        manifest
                .put(
                        "objectKey",
                        published
                                .pack()
                                .objectKey()
                );
        manifest
                .put(
                        "checksum",
                        published
                                .pack()
                                .checksum()
                );
        manifest
                .put(
                        "sizeBytes",
                        published
                                .pack()
                                .sizeBytes()
                );
        manifest
                .put(
                        "embedding",
                        null
                );
        manifest
                .put(
                        "inputSnapshotHash",
                        published
                                .inputSnapshotHash()
                );
        manifest
                .put(
                        "processingConfigHash",
                        published
                                .processingConfigHash()
                );
        manifest
                .put(
                        "sourceSnapshotIds",
                        buildRepository
                                .sourceSnapshotIds(published
                                        .buildId())
                );
        manifest
                .put(
                        "releaseSet",
                        List
                                .of()
                );
        manifest
                .put(
                        "publishedAt",
                        PUBLISHED_AT
                                .format(published
                                        .publishedAt())
                );

        return CanonicalJson
                .write(manifest);
    }

    // packs/<packId>/publications/<seq>.json object key
    private static String objectKey(BuildRow published) {

        return "packs/" + published
                .packId() + "/publications/" + published
                        .publicationSequence() + ".json";
    }
}
