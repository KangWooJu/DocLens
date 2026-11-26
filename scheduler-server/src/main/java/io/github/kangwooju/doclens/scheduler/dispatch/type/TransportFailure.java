package io.github.kangwooju.doclens.scheduler.dispatch.type;

/** 전송 실패 종류, 응답을 받았으면 NONE */
public enum TransportFailure {

    NONE,
    CONNECT,
    TIMEOUT
}
