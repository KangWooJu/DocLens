package io.github.kangwooju.doclens.pipeline.global.response;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunIncompleteException;
import io.github.kangwooju.doclens.pipeline.collected.exception.CollectedRunInvalidException;
import io.github.kangwooju.doclens.pipeline.collected.exception.ProcessRequestInvalidException;
import io.github.kangwooju.doclens.pipeline.global.response.dto.ErrorBody;
import io.github.kangwooju.doclens.pipeline.global.response.type.ErrorCode;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildArtifactMissingException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildFailedException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildInputConflictException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNeedsReviewException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildNotExportableException;
import io.github.kangwooju.doclens.pipeline.pack.exception.BuildPendingException;
import io.github.kangwooju.doclens.pipeline.pack.exception.ProcessingConfigMismatchException;
import io.github.kangwooju.doclens.pipeline.pack.exception.PublicationConflictException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 내부 HTTP API 예외를 contracts error와 상태 코드로
 * 이름 붙은 일시 장애만 503 재시도 가능, 그 밖 모르는 예외는 결정적 결함으로 보고 500 재시도 불가
 */
@RestControllerAdvice
public class ApiExceptionAdvice {

    private static final Logger LOGGER = System
            .getLogger(ApiExceptionAdvice.class
                    .getName());

    /** 본문이 JSON 객체가 아니거나 형식, 지원 범위 밖, 본문 없음 */
    @ExceptionHandler({
            ProcessRequestInvalidException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ErrorBody> invalid(Exception exception) {

        return error(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                exception instanceof ProcessRequestInvalidException ? exception
                        .getMessage() : "request body is missing or unreadable",
                false
        );
    }

    /** 처리 설정 hash 불일치, message에 지금 적용할 hash */
    @ExceptionHandler(ProcessingConfigMismatchException.class)
    public ResponseEntity<ErrorBody> configMismatch(ProcessingConfigMismatchException exception) {

        return error(
                HttpStatus.CONFLICT,
                ErrorCode.HASH_MISMATCH,
                exception
                        .getMessage(),
                false
        );
    }

    /** 기대 발행 번호 불일치 */
    @ExceptionHandler(PublicationConflictException.class)
    public ResponseEntity<ErrorBody> publicationConflict(PublicationConflictException exception) {

        return error(
                HttpStatus.CONFLICT,
                ErrorCode.STALE_OWNER,
                exception
                        .getMessage(),
                false
        );
    }

    /** 같은 작업 키의 다른 원본 Build */
    @ExceptionHandler(BuildInputConflictException.class)
    public ResponseEntity<ErrorBody> inputConflict(BuildInputConflictException exception) {

        return error(
                HttpStatus.CONFLICT,
                ErrorCode.ID_INPUT_CONFLICT,
                exception
                        .getMessage(),
                false
        );
    }

    /** 필수 문서 수집 실패 */
    @ExceptionHandler(CollectedRunIncompleteException.class)
    public ResponseEntity<ErrorBody> incomplete(CollectedRunIncompleteException exception) {

        return error(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.INPUT_INCOMPLETE,
                exception
                        .getMessage(),
                false
        );
    }

    /** 수집 묶음과 원본 bytes가 기록과 다름 */
    @ExceptionHandler({
            CollectedRunInvalidException.class,
            BuildFailedException.class
    })
    public ResponseEntity<ErrorBody> hashMismatch(RuntimeException exception) {

        return error(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.HASH_MISMATCH,
                exception
                        .getMessage(),
                false
        );
    }

    /** 문서 보류로 NEEDS_REVIEW인 Build */
    @ExceptionHandler(BuildNeedsReviewException.class)
    public ResponseEntity<ErrorBody> needsReview(BuildNeedsReviewException exception) {

        return error(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.NEEDS_REVIEW,
                exception
                        .getMessage(),
                false
        );
    }

    /** Export할 수 없는 Build, Pack 크기 상한 등 */
    @ExceptionHandler(BuildNotExportableException.class)
    public ResponseEntity<ErrorBody> notExportable(BuildNotExportableException exception) {

        return error(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ErrorCode.INVALID_TRANSITION,
                exception
                        .getMessage(),
                false
        );
    }

    /** SUCCEEDED Build의 Pack 객체가 없거나 바뀜 */
    @ExceptionHandler(BuildArtifactMissingException.class)
    public ResponseEntity<ErrorBody> artifactMissing(BuildArtifactMissingException exception) {

        LOGGER
                .log(
                        Level.ERROR,
                        exception
                                .getMessage(),
                        exception
                );

        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.STORAGE_FAILED,
                exception
                        .getMessage(),
                false
        );
    }

    /** 원본 객체가 아직 없어 RUNNING인 Build, 같은 본문 재전송으로 이어 감 */
    @ExceptionHandler(BuildPendingException.class)
    public ResponseEntity<ErrorBody> pending(BuildPendingException exception) {

        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.INPUT_INCOMPLETE,
                exception
                        .getMessage(),
                true
        );
    }

    /** 이름 붙은 일시 장애, DB 시간 초과와 잠금 실패, 연결 실패, S3 client 오류와 5xx */
    @ExceptionHandler({
            TransientDataAccessException.class,
            DataAccessResourceFailureException.class,
            SdkClientException.class
    })
    public ResponseEntity<ErrorBody> transientFailure(RuntimeException exception) {

        LOGGER
                .log(
                        Level.WARNING,
                        "transient failure",
                        exception
                );

        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.INTERNAL_ERROR,
                "temporary failure: " + exception
                        .getClass()
                        .getSimpleName(),
                true
        );
    }

    /** S3 오류, 5xx만 일시 장애이고 그 밖은 결정적 결함 */
    @ExceptionHandler(S3Exception.class)
    public ResponseEntity<ErrorBody> s3Failure(S3Exception exception) {

        if (exception
                .statusCode() >= 500) {
            return transientFailure(exception);
        }

        return unexpected(exception);
    }

    /** 그 밖 모든 예외, Spring MVC 요청 오류는 그 상태 코드, 나머지는 결정적 결함으로 500 재시도 불가 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorBody> unexpected(Exception exception) {

        if (exception instanceof ErrorResponse response) {
            HttpStatusCode status = response
                    .getStatusCode();

            return error(
                    status,
                    frameworkCode(status),
                    response
                            .getBody()
                            .getDetail(),
                    false
            );
        }

        LOGGER
                .log(
                        Level.ERROR,
                        "unexpected failure",
                        exception
                );

        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_ERROR,
                "unexpected " + exception
                        .getClass()
                        .getSimpleName(),
                false
        );
    }

    // Spring MVC 요청 오류 상태의 code, 404는 NOT_FOUND, 그 밖 4xx는 VALIDATION_FAILED
    private static ErrorCode frameworkCode(HttpStatusCode status) {

        if (status
                .value() == HttpStatus.NOT_FOUND
                        .value()) {
            return ErrorCode.NOT_FOUND;
        }

        return status
                .is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
    }

    // 상태 코드와 contracts error 본문
    private static ResponseEntity<ErrorBody> error(
            HttpStatusCode status,
            ErrorCode code,
            String message,
            boolean retryable
    ) {

        return ResponseEntity
                .status(status)
                .body(ErrorBody
                        .of(
                                code,
                                message,
                                retryable
                        ));
    }
}
