package io.github.kangwooju.doclens.pipeline.parse.dto;

import java.util.List;
import java.util.Map;

/**
 * AsciiDoc 원본 프로필의 출처별 규칙, 모두 처리 설정 preimage에 포함
 * 경로는 저장소 뿌리 기준이고 includeCodePath의 {lang}은 java 또는 kotlin
 * residueAllowlist 항목은 쪽 경로와 잔여 글자를 | 로 이은 값
 */
public record AsciidocRules(
        String readerUrlRoot,
        String sourceViewRoot,
        String antoraModulesPath,
        String rootExamplesPath,
        String includeCodePath,
        List<String> generatedIncludes,
        List<String> linkAttributePrefixes,
        List<String> residueAllowlist,
        Map<String, String> attributes,
        String exactVersionAttribute
) {

    /** 목록과 대응표 불변 사본 */
    public AsciidocRules {
        generatedIncludes = List
                .copyOf(generatedIncludes);
        linkAttributePrefixes = List
                .copyOf(linkAttributePrefixes);
        residueAllowlist = List
                .copyOf(residueAllowlist);
        attributes = Map
                .copyOf(attributes);
    }
}
