package io.github.kangwooju.doclens.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import io.github.kangwooju.doclens.core.json.CanonicalJson;
import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.pack.BuildFixtures;
import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;
import io.github.kangwooju.doclens.pipeline.parse.ParseFixtures;

/** Export 시험과 자식 JVM이 함께 쓰는 원본 목록과 요청, 원본 bytes는 fixture 내용에서 계산 */
public final class PackRequestFixtures {

    /** Reference 프로필에 맞는 URL 접두어 */
    public static final String BASE_URL = "https://docs.spring.io/spring-boot/4.1.2/reference/features/";

    /** 원본 두 개의 fixture 이름, documentId 순서와 같음 */
    public static final List<String> FIXTURES = List
            .of(
                    "reference.html",
                    "f16-sections-tabs-table-links.html"
            );

    // 인스턴스 생성 금지
    private PackRequestFixtures() {
    }

    /** fixture 두 개의 원본 목록, documentId 순 */
    public static List<SourceEntry> sources() {

        return FIXTURES
                .stream()
                .map(PackRequestFixtures::source)
                .sorted((left, right) -> CanonicalJson
                        .compareCodePoints(
                                left
                                        .documentId(),
                                right
                                        .documentId()
                        ))
                .toList();
    }

    /** 원본 목록과 현재 처리 설정 hash의 요청 */
    public static BuildRequest request(List<SourceEntry> sources) {

        return new BuildRequest(
                "spring-boot",
                "4.1.2",
                "EXACT_RELEASE",
                "sha256:" + "1"
                        .repeat(64),
                "sha256:" + "2"
                        .repeat(64),
                BuildFixtures
                        .currentHash(),
                null,
                sources
        );
    }

    /** 원본 하나의 fixture 내용을 object root 아래 raw 경로에 씀 */
    public static void writeRaw(
            Path objectRoot,
            SourceEntry source
    ) {

        Path file = objectRoot
                .resolve(source
                        .rawObjectKey());

        try {
            Files
                    .createDirectories(file
                            .getParent());
            Files
                    .write(
                            file,
                            bytes(fixtureOf(source))
                    );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 원본의 fixture 이름 */
    public static String fixtureOf(SourceEntry source) {

        return source
                .rawObjectKey()
                .substring(source
                        .rawObjectKey()
                        .lastIndexOf('/') + 1);
    }

    /** fixture 내용의 UTF-8 bytes */
    public static byte[] bytes(String fixture) {

        return ParseFixtures
                .html(fixture)
                .getBytes(StandardCharsets.UTF_8);
    }

    // fixture 하나의 원본, URL은 fixture 이름에서
    private static SourceEntry source(String fixture) {

        String url = BASE_URL + fixture;

        return new SourceEntry(
                CanonicalJson
                        .sha256(url),
                DocumentType.REFERENCE,
                url,
                url,
                url,
                "snap-" + fixture,
                sha256(bytes(fixture)),
                "raw/spring-boot/4.1.2/" + fixture
        );
    }

    // bytes의 sha256: 접두 hex
    private static String sha256(byte[] bytes) {

        try {
            return "sha256:" + HexFormat
                    .of()
                    .formatHex(MessageDigest
                            .getInstance("SHA-256")
                            .digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
