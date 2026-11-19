package io.github.kangwooju.doclens.core.pack.fixture;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.dto.PackChunk;
import io.github.kangwooju.doclens.core.pack.dto.PackDocument;
import io.github.kangwooju.doclens.core.pack.dto.PackMeta;
import io.github.kangwooju.doclens.core.pack.dto.PackSection;
import io.github.kangwooju.doclens.core.pack.dto.PackWriteResult;
import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.core.pack.writer.PackWriter;

/**
 * 시험용 작은 Pack 생성기
 * 문서마다 섹션 하나, ID는 seed의 SHA-256으로 고정
 */
public final class TestPacks {

    public static final String VERSION = "4.0.0";

    public static final String PROJECT = "spring-boot";

    // 정적 메서드 전용
    private TestPacks() {
    }

    /** 새 Pack builder */
    public static Builder builder() {

        return new Builder();
    }

    /** seed 기반 결정적 `sha256:` ID */
    public static String id(String seed) {

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(seed
                            .getBytes(StandardCharsets.UTF_8));

            return "sha256:" + HexFormat
                    .of()
                    .formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 기본 프로젝트와 버전의 시험용 메타데이터 */
    public static PackMeta meta(String buildId) {

        return meta(
                PROJECT,
                VERSION,
                buildId
        );
    }

    /** 프로젝트와 버전을 지정한 시험용 메타데이터, packId는 `<project>-<version>-test` */
    public static PackMeta meta(
            String project,
            String version,
            String buildId
    ) {

        return new PackMeta(
                project + "-" + version + "-test",
                project,
                version,
                buildId,
                id("input-snapshot"),
                id("processing-config"),
                "parser-test-1",
                "chunker-test-1",
                "Spring documentation, Apache-2.0",
                List
                        .of(id("source-snapshot")),
                "test-packs"
        );
    }

    public static final class Builder {

        private final List<DocumentDraft> documents = new ArrayList<>();
        private String buildId = "11111111-1111-4111-8111-111111111111";
        private String project = PROJECT;
        private String version = VERSION;

        // TestPacks.builder()로만 생성
        private Builder() {
        }

        /** buildId 지정 */
        public Builder buildId(String value) {

            buildId = value;
            return this;
        }

        /** 프로젝트 지정 */
        public Builder project(String value) {

            project = value;
            return this;
        }

        /** Pack docVersion이자 모든 문서의 exactVersion */
        public Builder version(String value) {

            version = value;
            return this;
        }

        /** 새 reference 문서 시작, 이후 chunk의 소속 문서 */
        public Builder document(String key) {

            return document(
                    key,
                    DocumentType.REFERENCE
            );
        }

        /** 문서 유형 지정 새 문서 시작 */
        public Builder document(
                String key,
                DocumentType documentType
        ) {

            documents
                    .add(new DocumentDraft(
                            key,
                            documentType
                    ));
            return this;
        }

        /** 식별자 없는 청크 추가 */
        public Builder chunk(String content) {

            return chunk(
                    content,
                    List
                            .of()
            );
        }

        /** 마지막 문서에 청크 추가 */
        public Builder chunk(
                String content,
                List<String> identifiers
        ) {

            if (documents
                    .isEmpty()) {
                throw new IllegalStateException("call document(key) before chunk(content)");
            }

            documents
                    .getLast()
                    .chunks()
                    .add(new ChunkDraft(
                            content,
                            List
                                    .copyOf(identifiers)
                    ));
            return this;
        }

        /** Writer 전달용 문서 목록(documentId 순서) */
        public List<PackDocument> documents() {

            return documents
                    .stream()
                    .map(document -> document
                            .toDocument(version))
                    .sorted((left, right) -> CanonicalJson
                            .compareCodePoints(
                                    left
                                            .documentId(),
                                    right
                                            .documentId()
                            ))
                    .toList();
        }

        /** builder에 지정한 값의 메타데이터 */
        public PackMeta meta() {

            return TestPacks
                    .meta(
                            project,
                            version,
                            buildId
                    );
        }

        /** directory 아래 `test.pack` 쓰기와 결과 반환 */
        public PackWriteResult writeTo(Path directory) {

            try (PackWriter writer = PackWriter
                    .create(
                            directory
                                    .resolve("test.pack"),
                            meta()
                    )) {
                documents()
                        .forEach(writer::add);

                return writer
                        .finish();
            }
        }
    }

    // builder가 모은 문서 키, 유형, 청크
    private record DocumentDraft(
            String key,
            DocumentType documentType,
            List<ChunkDraft> chunks
    ) {

        // 청크 없는 문서 초안
        DocumentDraft(
                String key,
                DocumentType documentType
        ) {

            this(
                    key,
                    documentType,
                    new ArrayList<>()
            );
        }

        // 섹션 하나짜리 PackDocument 변환
        PackDocument toDocument(String version) {

            String sectionId = id("section:" + key);
            List<PackChunk> packChunks = new ArrayList<>();
            int chunkCount = chunks
                    .size();

            for (int ordinal = 0; ordinal < chunkCount; ordinal++) {
                ChunkDraft chunk = chunks
                        .get(ordinal);
                packChunks
                        .add(new PackChunk(
                                id("chunk:" + key + ":" + ordinal),
                                ordinal,
                                chunk
                                        .content(),
                                key,
                                "[]",
                                "{}",
                                List
                                        .of(),
                                chunk
                                        .identifiers(),
                                List
                                        .of()
                        ));
            }

            return new PackDocument(
                    id("document:" + key),
                    "https://docs.spring.io/spring-boot/" + key + ".html",
                    key,
                    documentType,
                    version,
                    id("source-snapshot"),
                    id("raw:" + key),
                    "{}",
                    List
                            .of(new PackSection(
                                    sectionId,
                                    null,
                                    null,
                                    List
                                            .of(key),
                                    0,
                                    packChunks
                            ))
            );
        }
    }

    // builder가 모은 청크 내용과 식별자
    private record ChunkDraft(
            String content,
            List<String> identifiers
    ) {
    }
}
