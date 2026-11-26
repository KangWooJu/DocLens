package io.github.kangwooju.doclens.scheduler.handoff.converter;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.scheduler.handoff.dto.PackScope;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * readyRun에서 processRequest로 변환
 * readyRun 필드를 그대로 옮기고 createdAt을 빼고 처리 요청 필드를 더함, AUTO 발행만
 */
@Component
public class ProcessRequestConverter {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    /** 처리 요청 본문, 모든 projection에 같은 발행 번호 기대값 */
    public String toProcessRequest(
            String readyRunJson,
            UUID requestId,
            UUID traceId,
            String processingConfigHash,
            long expectedPublicationSequence,
            Instant requestedAt
    ) {

        ObjectNode request = (ObjectNode) JSON_MAPPER
                .readTree(readyRunJson);
        request
                .remove("createdAt");
        request
                .put(
                        "requestId",
                        requestId
                                .toString()
                );
        request
                .put(
                        "traceId",
                        traceId
                                .toString()
                );
        request
                .put(
                        "processingConfigHash",
                        processingConfigHash
                );
        request
                .putNull("modelChecksum");
        request
                .put(
                        "activationMode",
                        "AUTO"
                );
        request
                .put(
                        "requestedAt",
                        requestedAt
                                .toString()
                );

        for (JsonNode projection : request
                .get("projections")) {
            ((ObjectNode) projection)
                    .put(
                            "expectedPublicationSequence",
                            expectedPublicationSequence
                    );
            ((ObjectNode) projection)
                    .putNull("activationApprovalId");
        }

        return JSON_MAPPER
                .writeValueAsString(request);
    }

    /** 첫 projection의 Pack 범위, 발행 번호 조회용 */
    public PackScope packScope(String readyRunJson) {

        JsonNode scope = JSON_MAPPER
                .readTree(readyRunJson)
                .get("projections")
                .get(0)
                .get("packScope");

        return new PackScope(
                scope
                        .get("project")
                        .stringValue(),
                scope
                        .get("exactVersion")
                        .stringValue()
        );
    }
}
