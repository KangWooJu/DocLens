package io.github.kangwooju.doclens.pipeline.parse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
import io.github.kangwooju.doclens.pipeline.parse.helper.AsciidocHtmlHelper;
import io.github.kangwooju.doclens.pipeline.parse.helper.BlockExtractHelper;
import io.github.kangwooju.doclens.pipeline.parse.policy.ParserProfilePolicy;
import io.github.kangwooju.doclens.pipeline.parse.service.DocumentParseService;
import io.github.kangwooju.doclens.pipeline.parse.store.ParserProfileStore;
import io.github.kangwooju.doclens.pipeline.parse.type.ApprovalState;

/** 파싱 시험용 원본, 승인 프로필, 서비스 */
public final class ParseFixtures {

    /** 시험 문서 URL */
    public static final String URL = "https://docs.spring.io/spring-boot/4.1.2/reference/features/sample.html";

    /** 첫 시험 문서 ID */
    public static final String DOCUMENT_ID = "sha256:" + "1"
            .repeat(64);

    /** 둘째 시험 문서 ID */
    public static final String OTHER_DOCUMENT_ID = "sha256:" + "2"
            .repeat(64);

    /** AsciiDoc fixture의 commit 고정 원본 URL 뿌리, commit은 합성 값 */
    public static final String RAW_ROOT = "https://raw.githubusercontent.com/spring-projects/spring-boot/0123456789abcdef0123456789abcdef01234567/";

    /** AsciiDoc fixture 쪽 디렉터리의 저장소 경로 */
    public static final String PAGES = "documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/";

    /** 시험 JVM 전체가 함께 쓰는 AsciiDoc 변환기, JRuby를 한 번만 띄움 */
    public static final AsciidocHtmlHelper ASCIIDOC_HTML_HELPER = new AsciidocHtmlHelper();

    // 인스턴스 생성 금지
    private ParseFixtures() {
    }

    /** 시험 리소스 HTML */
    public static String html(String name) {

        try (InputStream input = ParseFixtures.class
                .getResourceAsStream("/fixtures/antora/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing fixture " + name);
            }

            return new String(
                    input
                            .readAllBytes(),
                    StandardCharsets.UTF_8
            );
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** 기본 프로필과 같은 값의 ENABLED 프로필 */
    public static ParserProfile enabledProfile() {

        return withApproval(
                ParserProfileStore.SPRING_BOOT_REFERENCE,
                ApprovalState.ENABLED
        );
    }

    /** 승인 상태만 바꾼 프로필 */
    public static ParserProfile withApproval(
            ParserProfile profile,
            ApprovalState approvalState
    ) {

        return new ParserProfile(
                profile
                        .profileId(),
                profile
                        .profileVersion(),
                profile
                        .project(),
                profile
                        .documentType(),
                profile
                        .urlPattern(),
                profile
                        .rootSelector(),
                profile
                        .excludedSelectors(),
                profile
                        .headingSelectors(),
                profile
                        .tabSelectors(),
                profile
                        .versionMarkerRule(),
                profile
                        .fixtureHashes(),
                approvalState,
                profile
                        .defaultTabRule(),
                profile
                        .preferredDefaultLabels(),
                profile
                        .canonicalLabels(),
                profile
                        .groupTypes(),
                profile
                        .asciidoc()
        );
    }

    /** ENABLED 프로필을 쓰는 서비스 */
    public static DocumentParseService service() {

        return service(enabledProfile());
    }

    /** 지정 프로필 하나를 쓰는 서비스 */
    public static DocumentParseService service(ParserProfile profile) {

        return new DocumentParseService(
                new ParserProfileStore(List
                        .of(profile)),
                new ParserProfilePolicy(),
                new BlockExtractHelper(),
                ASCIIDOC_HTML_HELPER
        );
    }

    /** 첫 문서 ID의 원본 */
    public static SourceDocument source(String html) {

        return source(
                html,
                DOCUMENT_ID
        );
    }

    /** 지정 문서 ID의 원본, rawSha256은 바이트에서 계산 */
    public static SourceDocument source(
            String html,
            String documentId
    ) {

        byte[] bytes = html
                .getBytes(StandardCharsets.UTF_8);

        return bytes(
                bytes,
                documentId
        );
    }

    /** 바이트 그대로의 원본 */
    public static SourceDocument bytes(
            byte[] bytes,
            String documentId
    ) {

        return new SourceDocument(
                documentId,
                "spring-boot",
                "4.1.2",
                DocumentType.REFERENCE,
                URL,
                URL,
                URL,
                "sha256:" + "5"
                        .repeat(64),
                sha256(bytes),
                bytes
        );
    }

    /** AsciiDoc 프로필을 ENABLED로 바꾼 서비스 */
    public static DocumentParseService asciidocService() {

        return service(withApproval(
                ParserProfileStore.SPRING_BOOT_ASCIIDOC_REFERENCE,
                ApprovalState.ENABLED
        ));
    }

    /** AsciiDoc fixture 쪽 하나의 원본, 경로는 pages 아래 */
    public static SourceDocument asciidocPage(String pagePath) {

        return asciidoc(
                PAGES + pagePath,
                DocumentType.REFERENCE
        );
    }

    /** AsciiDoc fixture의 예시 코드와 partial 전부, key는 commit 고정 원본 URL */
    public static Map<String, SourceDocument> asciidocIncludes() {

        Path root = asciidocRoot();
        Map<String, SourceDocument> includes = new TreeMap<>();

        for (String directory : List
                .of(
                        "documentation/spring-boot-docs/src/main",
                        "documentation/spring-boot-docs/src/docs/antora/modules/reference/partials"
                )) {
            try (Stream<Path> files = Files
                    .walk(root
                            .resolve(directory))) {
                for (Path file : files
                        .filter(Files::isRegularFile)
                        .toList()) {
                    SourceDocument include = asciidoc(
                            root
                                    .relativize(file)
                                    .toString()
                                    .replace(
                                            '\\',
                                            '/'
                                    ),
                            DocumentType.EXAMPLE
                    );

                    includes
                            .put(
                                    include
                                            .canonicalUrl(),
                                    include
                            );
                }
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        return includes;
    }

    /** AsciiDoc fixture 파일 하나의 bytes, 경로는 fixture 뿌리 기준 저장소 경로 */
    public static byte[] asciidocBytes(String repositoryPath) {

        try {
            return Files
                    .readAllBytes(asciidocRoot()
                            .resolve(repositoryPath));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // 저장소 경로 하나의 원본, 문서 ID는 URL의 hash
    private static SourceDocument asciidoc(
            String repositoryPath,
            DocumentType documentType
    ) {

        String url = RAW_ROOT + repositoryPath;
        byte[] bytes = asciidocBytes(repositoryPath);

        return new SourceDocument(
                sha256(url
                        .getBytes(StandardCharsets.UTF_8)),
                "spring-boot",
                "4.1.2",
                documentType,
                url,
                url,
                url,
                "sha256:" + "5"
                        .repeat(64),
                sha256(bytes),
                bytes
        );
    }

    // 시험 리소스의 AsciiDoc fixture 뿌리 디렉터리
    private static Path asciidocRoot() {

        try {
            return Path
                    .of(ParseFixtures.class
                            .getResource("/fixtures/asciidoc")
                            .toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** 최소 Antora 페이지, body는 article.doc 안 내용 */
    public static String page(String body) {

        return "<html><head><meta name=\"version\" content=\"4.1.2\"></head><body><article class=\"doc\">"
                + "<h1 id=\"page-title\" class=\"page\">Title</h1>" + body + "</article></body></html>";
    }

    // 바이트의 sha256: 접두 hex
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
