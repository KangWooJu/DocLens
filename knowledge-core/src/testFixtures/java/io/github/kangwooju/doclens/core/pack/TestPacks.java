package io.github.kangwooju.doclens.core.pack;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import io.github.kangwooju.doclens.core.json.CanonicalJson;

/**
 * 시험용 작은 Pack 생성기(pipeline·mcp-server 시험 공유).
 *
 * <p>문서마다 섹션 하나, 청크는 넣은 순서대로. ID는 seed의 SHA-256으로 결정적.
 * 문서는 documentId 순서로 정렬해 Writer에 넘김.
 */
public final class TestPacks {

    public static final String VERSION = "4.0.0";

    private TestPacks() {
    }

    public static Builder builder() {

        return new Builder();
    }

    /** seed로 만든 결정적 `sha256:` ID. */
    public static String id(String seed) {

        try {
            byte[] digest = MessageDigest
                    .getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));

            return "sha256:" + HexFormat
                    .of()
                    .formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 시험용 메타데이터. */
    public static PackMeta meta(String buildId) {

        return new PackMeta(
                "spring-boot-" + VERSION + "-test",
                "spring-boot",
                VERSION,
                buildId,
                id("input-snapshot"),
                id("processing-config"),
                "parser-test-1",
                "chunker-test-1",
                "Spring documentation, Apache-2.0",
                List.of(id("source-snapshot")),
                "test-packs"
        );
    }

    public static final class Builder {

        private final List<DocumentDraft> documents = new ArrayList<>();
        private String buildId = "11111111-1111-4111-8111-111111111111";

        private Builder() {
        }

        public Builder buildId(String value) {

            buildId = value;
            return this;
        }

        /** 새 문서를 시작. 이후 chunk는 이 문서에 들어감. */
        public Builder document(String key) {

            documents.add(new DocumentDraft(key));
            return this;
        }

        public Builder chunk(String content) {

            return chunk(
                    content,
                    List.of()
            );
        }

        public Builder chunk(
                String content,
                List<String> identifiers
        ) {

            if (documents.isEmpty()) {
                throw new IllegalStateException("call document(key) before chunk(content)");
            }

            documents
                    .getLast()
                    .chunks()
                    .add(new ChunkDraft(
                            content,
                            List.copyOf(identifiers)
                    ));
            return this;
        }

        /** Writer에 넘길 문서들(documentId 순서). */
        public List<PackDocument> documents() {

            return documents
                    .stream()
                    .map(DocumentDraft::toDocument)
                    .sorted((left, right) -> CanonicalJson.compareCodePoints(
                            left.documentId(),
                            right.documentId()
                    ))
                    .toList();
        }

        /** directory 아래 `test.pack`으로 써서 결과를 돌려줌. */
        public PackWriteResult writeTo(Path directory) {

            try (PackWriter writer = PackWriter.create(
                    directory.resolve("test.pack"),
                    meta(buildId)
            )) {
                documents()
                        .forEach(writer::add);

                return writer.finish();
            }
        }
    }

    private record DocumentDraft(
            String key,
            List<ChunkDraft> chunks
    ) {

        DocumentDraft(String key) {

            this(
                    key,
                    new ArrayList<>()
            );
        }

        PackDocument toDocument() {

            String sectionId = id("section:" + key);
            List<PackChunk> packChunks = new ArrayList<>();

            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                ChunkDraft chunk = chunks.get(ordinal);
                packChunks.add(new PackChunk(
                        id("chunk:" + key + ":" + ordinal),
                        ordinal,
                        chunk.content(),
                        key,
                        "[]",
                        "{}",
                        List.of(),
                        chunk.identifiers(),
                        List.of()
                ));
            }

            return new PackDocument(
                    id("document:" + key),
                    "https://docs.spring.io/spring-boot/" + key + ".html",
                    key,
                    DocumentType.REFERENCE,
                    VERSION,
                    id("source-snapshot"),
                    id("raw:" + key),
                    "{}",
                    List.of(new PackSection(
                            sectionId,
                            null,
                            null,
                            List.of(key),
                            0,
                            packChunks
                    ))
            );
        }
    }

    private record ChunkDraft(
            String content,
            List<String> identifiers
    ) {
    }
}
