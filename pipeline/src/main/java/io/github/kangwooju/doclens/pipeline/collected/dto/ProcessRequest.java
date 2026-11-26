package io.github.kangwooju.doclens.pipeline.collected.dto;

import tools.jackson.databind.JsonNode;

/** 형식을 확인한 contracts processRequest, 잠금 키와 처리 설정 hash, 기대 발행 번호, readyRun 필드를 담은 본문 */
public record ProcessRequest(
        String readyRunId,
        String processingConfigHash,
        long expectedPublicationSequence,
        JsonNode body
) {
}
