package io.github.kangwooju.doclens.pipeline.collected.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import io.github.kangwooju.doclens.pipeline.collected.dto.ObjectRef;
import io.github.kangwooju.doclens.pipeline.collected.dto.ProcessingReceipt;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunIncompleteException;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunInvalidException;
import io.github.kangwooju.doclens.pipeline.collected.exception.ProcessRequestInvalidException;
import io.github.kangwooju.doclens.pipeline.global.security.InternalSecurityConfig;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildArtifactMissingException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildFailedException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildInputConflictException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNeedsReviewException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNotExportableException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildPendingException;
import io.github.kangwooju.doclens.pipeline.pack.exception.ProcessingConfigMismatchException;
import io.github.kangwooju.doclens.pipeline.pack.exception.PublicationConflictException;
import io.github.kangwooju.doclens.pipeline.pack.exception.StageNondeterminismException;
import io.github.kangwooju.doclens.pipeline.pack.facade.PackBuildFacade;
import io.github.kangwooju.doclens.testsupport.CallerTokens;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest(ProcessRequestController.class)
@Import(InternalSecurityConfig.class)
@DisplayName("내부 API 인증, 호출자별 경로, 예외의 계약 error 대응")
class ProcessRequestControllerTests {

    private static final JsonMapper JSON = JsonMapper
            .builder()
            .build();

    private static final String SCHEDULER_TOKEN = CallerTokens
            .newToken();

    private static final String NEXT_SCHEDULER_TOKEN = CallerTokens
            .newToken();

    private static final String OPERATOR_TOKEN = CallerTokens
            .newToken();

    private static final String PROCESSING_JOBS = "/internal/v1/processing-jobs";

    private static final String LATEST = "/internal/v1/publications/latest";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PackBuildFacade facade;

    // 교체 기간처럼 scheduler 항목 둘과 operator 항목 하나
    @DynamicPropertySource
    static void callerTokens(DynamicPropertyRegistry registry) {

        registry
                .add(
                        "doclens.security.caller-tokens",
                        () -> String
                                .join(
                                        ",",
                                        CallerTokens
                                                .entry(
                                                        "scheduler",
                                                        SCHEDULER_TOKEN
                                                ),
                                        CallerTokens
                                                .entry(
                                                        "scheduler",
                                                        NEXT_SCHEDULER_TOKEN
                                                ),
                                        CallerTokens
                                                .entry(
                                                        "operator",
                                                        OPERATOR_TOKEN
                                                )
                                )
                );
    }

    // Facade 정상 응답
    @BeforeEach
    void stubFacade() {

        when(facade
                .process(anyString()))
                .thenReturn(new ProcessingReceipt(
                        "sha256:" + "a"
                                .repeat(64),
                        List
                                .of(),
                        new ObjectRef(
                                "packs/spring-boot-4.1.2/publications/1.json",
                                "sha256:" + "b"
                                        .repeat(64),
                                1
                        )
                ));
        when(facade
                .latestPublication(
                        anyString(),
                        anyString()
                ))
                .thenReturn(Optional
                        .of("{\"publicationSequence\":1}"));
    }

    @Test
    @DisplayName("토큰 없음과 틀린 토큰은 401 WWW-Authenticate와 AUTH_REQUIRED, scheduler 두 토큰 모두 통과, operator는 조회만, 다른 경로는 403")
    void authorizesCallers() throws Exception {

        MockHttpServletResponse missing = perform(post(PROCESSING_JOBS)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));

        assertError(
                missing,
                401,
                "AUTH_REQUIRED",
                false
        );
        assertEquals(
                "Bearer",
                missing
                        .getHeader("WWW-Authenticate")
        );

        MockHttpServletResponse wrong = perform(processing(CallerTokens
                .newToken()));

        assertError(
                wrong,
                401,
                "AUTH_REQUIRED",
                false
        );
        assertEquals(
                "Bearer",
                wrong
                        .getHeader("WWW-Authenticate")
        );

        for (String token : List
                .of(
                        SCHEDULER_TOKEN,
                        NEXT_SCHEDULER_TOKEN
                )) {
            assertEquals(
                    200,
                    perform(processing(token))
                            .getStatus()
            );
            assertEquals(
                    200,
                    perform(latest(token))
                            .getStatus()
            );
        }

        assertEquals(
                200,
                perform(latest(OPERATOR_TOKEN))
                        .getStatus()
        );
        assertError(
                perform(processing(OPERATOR_TOKEN)),
                403,
                "FORBIDDEN",
                false
        );
        assertError(
                perform(get("/internal/v1/other")
                        .header(
                                "Authorization",
                                "Bearer " + SCHEDULER_TOKEN
                        )),
                403,
                "FORBIDDEN",
                false
        );
        assertError(
                perform(get(PROCESSING_JOBS)
                        .header(
                                "Authorization",
                                "Bearer " + SCHEDULER_TOKEN
                        )),
                403,
                "FORBIDDEN",
                false
        );
    }

    @Test
    @DisplayName("최신 발행이 없으면 404 NOT_FOUND 계약 error")
    void reportsMissingPublication() throws Exception {

        when(facade
                .latestPublication(
                        anyString(),
                        anyString()
                ))
                .thenReturn(Optional
                        .empty());

        assertError(
                perform(latest(SCHEDULER_TOKEN)),
                404,
                "NOT_FOUND",
                false
        );
    }

    @Test
    @DisplayName("예외 대응 표, 이름 붙은 일시 장애만 503 재시도 가능이고 모르는 예외는 500 재시도 불가")
    void mapsExceptions() throws Exception {

        assertMapped(
                new ProcessRequestInvalidException(
                        "activationMode",
                        "must be AUTO"
                ),
                400,
                "VALIDATION_FAILED",
                false
        );
        assertMapped(
                new ProcessingConfigMismatchException(
                        "sha256:old",
                        "sha256:new"
                ),
                409,
                "HASH_MISMATCH",
                false
        );
        assertMapped(
                new PublicationConflictException(
                        "build",
                        1L
                ),
                409,
                "STALE_OWNER",
                false
        );
        assertMapped(
                new BuildInputConflictException("sha256:job"),
                409,
                "ID_INPUT_CONFLICT",
                false
        );
        assertMapped(
                new CollectedRunIncompleteException(
                        List
                                .of("doc"),
                        List
                                .of("HTTP_CLIENT")
                ),
                422,
                "INPUT_INCOMPLETE",
                false
        );
        assertMapped(
                new CollectedRunInvalidException(
                        "objectRef",
                        "size differs"
                ),
                422,
                "HASH_MISMATCH",
                false
        );
        assertMapped(
                new BuildFailedException(
                        "build",
                        "raw bytes differ"
                ),
                422,
                "HASH_MISMATCH",
                false
        );
        assertMapped(
                new BuildNeedsReviewException(
                        "build",
                        1
                ),
                422,
                "NEEDS_REVIEW",
                false
        );
        assertMapped(
                new BuildNotExportableException(
                        "build",
                        "too large"
                ),
                422,
                "INVALID_TRANSITION",
                false
        );
        assertMapped(
                new BuildArtifactMissingException(
                        "build",
                        "packs/a.pack"
                ),
                500,
                "STORAGE_FAILED",
                false
        );
        assertMapped(
                new StageNondeterminismException(
                        "build",
                        "doc"
                ),
                500,
                "INTERNAL_ERROR",
                false
        );
        assertMapped(
                new IllegalStateException("bug"),
                500,
                "INTERNAL_ERROR",
                false
        );
        assertMapped(
                new BuildPendingException("build"),
                503,
                "INPUT_INCOMPLETE",
                true
        );
        assertMapped(
                new QueryTimeoutException("statement timeout"),
                503,
                "INTERNAL_ERROR",
                true
        );
        assertMapped(
                new CannotGetJdbcConnectionException("no connection"),
                503,
                "INTERNAL_ERROR",
                true
        );
        assertMapped(
                SdkClientException
                        .create("connection reset"),
                503,
                "INTERNAL_ERROR",
                true
        );
        assertMapped(
                s3Exception(503),
                503,
                "INTERNAL_ERROR",
                true
        );
        assertMapped(
                s3Exception(403),
                500,
                "INTERNAL_ERROR",
                false
        );
    }

    // Facade가 던진 예외 하나의 응답 상태, code, 재시도 여부
    private void assertMapped(
            RuntimeException exception,
            int status,
            String code,
            boolean retryable
    ) throws Exception {

        doThrow(exception)
                .when(facade)
                .process(any());

        assertError(
                perform(processing(SCHEDULER_TOKEN)),
                status,
                code,
                retryable
        );
    }

    // S3 상태 코드의 S3Exception
    private static S3Exception s3Exception(int status) {

        return (S3Exception) S3Exception
                .builder()
                .statusCode(status)
                .message("status " + status)
                .build();
    }

    // bearer 토큰의 처리 요청
    private static RequestBuilder processing(String token) {

        return post(PROCESSING_JOBS)
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    // bearer 토큰의 최신 발행 조회
    private static RequestBuilder latest(String token) {

        return get(LATEST)
                .param(
                        "project",
                        "spring-boot"
                )
                .param(
                        "exactVersion",
                        "4.1.2"
                )
                .header(
                        "Authorization",
                        "Bearer " + token
                );
    }

    // 요청 하나의 응답
    private MockHttpServletResponse perform(RequestBuilder request) throws Exception {

        return mockMvc
                .perform(request)
                .andReturn()
                .getResponse();
    }

    // 계약 error의 상태, code, 재시도 여부, 프레임워크 필드
    private static void assertError(
            MockHttpServletResponse response,
            int status,
            String code,
            boolean retryable
    ) throws Exception {

        JsonNode error = JSON
                .readTree(response
                        .getContentAsString());

        assertEquals(
                status,
                response
                        .getStatus(),
                response
                        .getContentAsString()
        );
        assertEquals(
                code,
                error
                        .get("code")
                        .stringValue()
        );
        assertEquals(
                retryable,
                error
                        .get("retryable")
                        .booleanValue()
        );
        assertEquals(
                1,
                error
                        .get("schemaVersion")
                        .intValue()
        );
        assertTrue(error
                .get("requestId")
                .isNull());
        assertTrue(error
                .get("message")
                .stringValue()
                .length() <= 500);
        UUID
                .fromString(error
                        .get("traceId")
                        .stringValue());
    }
}
