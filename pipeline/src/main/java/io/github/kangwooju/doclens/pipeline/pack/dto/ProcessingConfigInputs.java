package io.github.kangwooju.doclens.pipeline.pack.dto;

import java.util.List;

import io.github.kangwooju.doclens.pipeline.parse.dto.ParserProfile;

/** 처리 설정 hash의 입력값 전부, Service가 실제 상수에서 채우고 Policy는 이 값만 씀 */
public record ProcessingConfigInputs(
        String parserPolicyVersion,
        String chunkerPolicyVersion,
        String jsoupVersion,
        int structuredDocumentSchemaVersion,
        int codeMaxBytes,
        int targetBytes,
        int maxBytes,
        int maxBlocks,
        int overlapMaxBytes,
        int headerRepeatMaxBytes,
        String separator,
        List<String> identifierPatterns,
        int identifierMaxLength,
        String sourceAttribution,
        String createdBy,
        List<ParserProfile> profiles
) {

    /** 목록 불변 사본 */
    public ProcessingConfigInputs {
        identifierPatterns = List
                .copyOf(identifierPatterns);
        profiles = List
                .copyOf(profiles);
    }
}
