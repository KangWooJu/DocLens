package io.github.kangwooju.doclens.pipeline.pack.policy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.pack.dto.BuildRequest;
import io.github.kangwooju.doclens.pipeline.pack.dto.SourceEntry;

/** Build 요청 허용 여부, DB에 쓰기 전에 거절할 입력 오류 */
@Component
public class BuildRequestPolicy {

    /** Build 하나의 최대 문서 수 */
    public static final int MAX_DOCUMENTS = 100_000;

    /** 지원하는 적용 범위 */
    public static final String APPLICABILITY = "EXACT_RELEASE";

    private static final Pattern HASH = Pattern
            .compile("sha256:[0-9a-f]{64}");

    private static final Pattern RAW_OBJECT_KEY = Pattern
            .compile("raw/[a-zA-Z0-9_@.-]+(/[a-zA-Z0-9_@.-]+)*");

    private static final Pattern DOT_SEGMENT = Pattern
            .compile("(^|/)\\.{1,2}(/|$)");

    /** 요청 형식, 적용 범위, 문서 수, 원본과 include의 documentId 중복, object key 검사, 어긋나면 IllegalArgumentException */
    public void requireValid(BuildRequest request) {

        if (request == null) {
            throw new IllegalArgumentException("build request is required");
        }

        requireText(
                request
                        .project(),
                "project"
        );
        requireText(
                request
                        .exactVersion(),
                "exactVersion"
        );

        if (!APPLICABILITY
                .equals(request
                        .applicability())) {
            throw new IllegalArgumentException("applicability must be " + APPLICABILITY);
        }

        requireHash(
                request
                        .inputScopeKey(),
                "inputScopeKey"
        );
        requireHash(
                request
                        .runInputSnapshotHash(),
                "runInputSnapshotHash"
        );
        requireHash(
                request
                        .processingConfigHash(),
                "processingConfigHash"
        );

        if (request
                .modelChecksum() != null) {
            throw new IllegalArgumentException("modelChecksum must be null until embeddings exist");
        }

        List<SourceEntry> sources = request
                .sources();

        if (sources == null || sources
                .isEmpty() || sources
                        .size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("a build needs 1 to " + MAX_DOCUMENTS + " sources");
        }

        List<SourceEntry> includes = request
                .includes();

        if (includes == null) {
            throw new IllegalArgumentException("includes is required, use an empty list for none");
        }

        if (sources
                .size() + includes
                        .size() > MAX_DOCUMENTS) {
            throw new IllegalArgumentException("a build needs at most " + MAX_DOCUMENTS + " sources and includes");
        }

        Set<String> documentIds = new HashSet<>();

        for (SourceEntry source : sources) {
            requireSource(source);
            requireUnique(
                    documentIds,
                    source
            );
        }

        for (SourceEntry include : includes) {
            requireSource(include);
            requireUnique(
                    documentIds,
                    include
            );
        }
    }

    // 원본과 include 전체에서 처음 보는 documentId
    private static void requireUnique(
            Set<String> documentIds,
            SourceEntry source
    ) {

        if (!documentIds
                .add(source
                        .documentId())) {
            throw new IllegalArgumentException("duplicate documentId " + source
                    .documentId());
        }
    }

    // 원본 하나의 필수 값과 형식
    private static void requireSource(SourceEntry source) {

        if (source == null || source
                .documentType() == null) {
            throw new IllegalArgumentException("every source needs a documentType");
        }

        requireHash(
                source
                        .documentId(),
                "documentId"
        );
        requireHash(
                source
                        .rawSha256(),
                "rawSha256"
        );
        requireText(
                source
                        .canonicalUrl(),
                "canonicalUrl"
        );
        requireText(
                source
                        .originalUrl(),
                "originalUrl"
        );
        requireText(
                source
                        .finalUrl(),
                "finalUrl"
        );
        requireText(
                source
                        .sourceSnapshotId(),
                "sourceSnapshotId"
        );

        String key = source
                .rawObjectKey();

        if (key == null || !RAW_OBJECT_KEY
                .matcher(key)
                .matches() || DOT_SEGMENT
                        .matcher(key)
                        .find()) {
            throw new IllegalArgumentException("invalid rawObjectKey " + key);
        }
    }

    // 비어 있지 않은 문자열
    private static void requireText(
            String value,
            String name
    ) {

        if (value == null || value
                .isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    // sha256: 접두 소문자 hex 64자리
    private static void requireHash(
            String value,
            String name
    ) {

        if (value == null || !HASH
                .matcher(value)
                .matches()) {
            throw new IllegalArgumentException(name + " must be sha256:<64 hex>");
        }
    }
}
