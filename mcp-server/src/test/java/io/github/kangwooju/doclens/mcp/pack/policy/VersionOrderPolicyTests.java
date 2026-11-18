package io.github.kangwooju.doclens.mcp.pack.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VersionOrderPolicy")
class VersionOrderPolicyTests {

    private final VersionOrderPolicy policy = new VersionOrderPolicy();

    @Test
    @DisplayName("숫자 단위 비교와 수식어 버전의 정식 버전 앞 정렬")
    void ordersVersionsSemantically() {

        List<String> expected = List
                .of(
                        "3.5.9",
                        "3.5.10",
                        "4.0",
                        "4.0.0-M1",
                        "4.0.0-RC1",
                        "4.0.0",
                        "4.0.1",
                        "10.0.0"
                );

        assertEquals(
                expected,
                expected
                        .reversed()
                        .stream()
                        .sorted(policy)
                        .toList()
        );
    }
}
