package io.github.kangwooju.doclens.pipeline.global.id;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 길이 접두 필드를 SHA-256으로 묶은 ID와 문서 안 위치 ID 공식
 * 필드마다 NFC UTF-8 바이트 앞에 4바이트 big-endian 길이, contracts/v1/id-golden-vectors.json과 일치
 */
public final class IdHash {

    /** 탭 그룹이 없는 청크의 tabGroupLocator와 variantKey */
    public static final String NONE = "none";

    private static final String PREFIX = "sha256:";

    // 대응표를 거친 occurrence 0 라벨만 그대로 key로 쓰는 7개 값
    private static final Set<String> LITERAL_VARIANT_KEYS = Set
            .of(
                    "java",
                    "kotlin",
                    "groovy",
                    "maven",
                    "gradle",
                    "properties",
                    "yaml"
            );

    // 인스턴스 생성 금지
    private IdHash() {
    }

    /** H(fields) = "sha256:" + 소문자 hex(SHA-256(E(fields))) */
    public static String hash(String... fields) {

        return PREFIX + HexFormat
                .of()
                .formatHex(sha256(encode(fields)));
    }

    /** E(fields), 필드마다 4바이트 길이 접두와 NFC UTF-8 바이트 */
    public static byte[] encode(String... fields) {

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        for (String field : fields) {
            Objects
                    .requireNonNull(
                            field,
                            "id field"
                    );

            byte[] body = TextNormalizer
                    .encodeUtf8(Normalizer
                            .normalize(
                                    field,
                                    Normalizer.Form.NFC
                            ));

            out
                    .writeBytes(ByteBuffer
                            .allocate(Integer.BYTES)
                            .putInt(body.length)
                            .array());
            out
                    .writeBytes(body);
        }

        return out
                .toByteArray();
    }

    /** HTML id가 있는 heading 위치, occurrence는 같은 NFC id끼리 DOM 순번 */
    public static String headingLocator(
            String elementId,
            int occurrence
    ) {

        return hash(
                "heading-locator-v1",
                "html-id",
                elementId,
                Integer
                        .toString(occurrence)
        );
    }

    /** HTML id가 없는 heading 위치, 계층마다 0부터 센 형제 순번 */
    public static String headingLocator(List<Integer> siblingOrdinals) {

        List<String> fields = new ArrayList<>();
        fields
                .add("heading-locator-v1");
        fields
                .add("ordinal");
        fields
                .add(Integer
                        .toString(siblingOrdinals
                                .size()));
        siblingOrdinals
                .forEach(ordinal -> fields
                        .add(Integer
                                .toString(ordinal)));

        return hash(fields
                .toArray(String[]::new));
    }

    /** HTML id가 있는 탭 그룹 위치, occurrence는 같은 NFC id끼리 DOM 순번 */
    public static String tabGroupLocator(
            String elementId,
            int occurrence
    ) {

        return hash(
                "tabgroup-locator-v1",
                "html-id",
                elementId,
                Integer
                        .toString(occurrence)
        );
    }

    /** HTML id가 없는 탭 그룹 위치, 문서 안 탭 그룹 순번 */
    public static String tabGroupLocator(int groupOrdinal) {

        return hash(
                "tabgroup-locator-v1",
                "ordinal",
                "1",
                Integer
                        .toString(groupOrdinal)
        );
    }

    /**
     * 탭 변형 key, 대응표를 거친 7개 라벨의 occurrence 0만 라벨 그대로
     * 나머지는 H("variant-label-v1", label, occurrence)의 64자리 hex
     */
    public static String variantKey(
            String canonicalLabel,
            int occurrence,
            boolean mappedLabel
    ) {

        String label = Normalizer
                .normalize(
                        canonicalLabel,
                        Normalizer.Form.NFC
                );

        if (mappedLabel && occurrence == 0 && LITERAL_VARIANT_KEYS
                .contains(label)) {
            return label;
        }

        return hash(
                "variant-label-v1",
                label,
                Integer
                        .toString(occurrence)
        )
                .substring(PREFIX
                        .length());
    }

    /** 문서와 heading 위치로 정한 섹션 ID, Pack 전체에서 유일 */
    public static String sectionId(
            String documentId,
            String headingLocator
    ) {

        return hash(
                "section-v1",
                documentId,
                headingLocator
        );
    }

    /** 섹션 안 블록 순번으로 정한 블록 ID, 내용은 넣지 않음 */
    public static String blockId(
            String sectionId,
            int ordinal
    ) {

        return hash(
                "block-v1",
                sectionId,
                Integer
                        .toString(ordinal)
        );
    }

    /** 청크에 든 탭 그룹 위치 목록의 대표값, 0개 none, 1개 그대로, 2개 이상 set digest */
    public static String tabGroupLocatorOf(List<String> locators) {

        if (locators
                .isEmpty()) {
            return NONE;
        }

        if (locators
                .size() == 1) {
            return locators
                    .getFirst();
        }

        return hash(withCount(
                "tabgroup-set-v1",
                locators
        ));
    }

    /** 청크에 든 기본 변형 key 목록의 대표값, 0개 none, 1개 그대로, 2개 이상 set digest hex */
    public static String variantKeyOf(List<String> variantKeys) {

        if (variantKeys
                .isEmpty()) {
            return NONE;
        }

        if (variantKeys
                .size() == 1) {
            return variantKeys
                    .getFirst();
        }

        return hash(withCount(
                "variant-set-v1",
                variantKeys
        ))
                .substring(PREFIX
                        .length());
    }

    /** 청크 ID, segmentIndex는 섹션 안 청크 순번 */
    public static String chunkId(
            String documentId,
            String headingLocator,
            String tabGroupLocator,
            String variantKey,
            int segmentIndex,
            String normalizedContent
    ) {

        return hash(
                "chunk-v1",
                documentId,
                headingLocator,
                tabGroupLocator,
                variantKey,
                Integer
                        .toString(segmentIndex),
                normalizedContent
        );
    }

    // domain, 십진 개수, 값 순서의 필드 배열
    private static String[] withCount(
            String domain,
            List<String> values
    ) {

        List<String> fields = new ArrayList<>();
        fields
                .add(domain);
        fields
                .add(Integer
                        .toString(values
                                .size()));
        fields
                .addAll(values);

        return fields
                .toArray(String[]::new);
    }

    // SHA-256 digest
    private static byte[] sha256(byte[] bytes) {

        try {
            return MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is required by the Java platform",
                    exception
            );
        }
    }
}
