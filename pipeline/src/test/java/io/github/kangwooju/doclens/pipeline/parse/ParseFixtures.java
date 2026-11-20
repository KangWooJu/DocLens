package io.github.kangwooju.doclens.pipeline.parse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import io.github.kangwooju.doclens.core.pack.type.DocumentType;
import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;
import io.github.kangwooju.doclens.pipeline.parse.dto.SourceDocument;
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
                        .groupTypes()
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
                new BlockExtractHelper()
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
