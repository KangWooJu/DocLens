package io.github.kangwooju.doclens.scheduler.schedule.converter;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 요청 틀에서 run 하나의 collectionRequest, 식별자 둘과 요청 시각만 새로 채움 */
@Component
public class CollectionRequestConverter {

    private static final JsonMapper JSON_MAPPER = JsonMapper
            .builder()
            .build();

    /** 틀에 새 requestId, traceId, requestedAt을 채운 본문 */
    public String render(
            String template,
            UUID requestId,
            UUID traceId,
            Instant requestedAt
    ) {

        ObjectNode request = (ObjectNode) JSON_MAPPER
                .readTree(template);
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
                        "requestedAt",
                        requestedAt
                                .toString()
                );

        return JSON_MAPPER
                .writeValueAsString(request);
    }
}
