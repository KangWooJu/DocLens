package io.github.kangwooju.doclens.mcp.pack.policy;

import java.util.Comparator;

import org.springframework.stereotype.Component;

/**
 * 정확 버전 정렬 규칙
 *
 * <p>`-` 앞 본 버전은 `.` 단위로 비교(숫자끼리는 수 크기, 그 밖은 문자열),
 * 같으면 수식어 없는 정식 버전이 수식어 버전(M1, RC1 등)보다 뒤
 */
@Component
public class VersionOrderPolicy implements Comparator<String> {

    @Override
    public int compare(
            String left,
            String right
    ) {

        String[] leftParts = left
                .split(
                        "-",
                        2
                );
        String[] rightParts = right
                .split(
                        "-",
                        2
                );
        int byRelease = compareRelease(
                leftParts[0],
                rightParts[0]
        );

        if (byRelease != 0) {
            return byRelease;
        }

        int byQualifier = compareQualifier(
                leftParts.length > 1
                        ? leftParts[1]
                        : null,
                rightParts.length > 1
                        ? rightParts[1]
                        : null
        );

        if (byQualifier != 0) {
            return byQualifier;
        }

        return left
                .compareTo(right);
    }

    // `.` 단위 본 버전 비교, 앞부분이 같으면 짧은 쪽 먼저
    private static int compareRelease(
            String left,
            String right
    ) {

        String[] leftSegments = left
                .split("\\.");
        String[] rightSegments = right
                .split("\\.");
        int common = Math
                .min(
                        leftSegments.length,
                        rightSegments.length
                );

        for (int index = 0; index < common; index++) {
            int bySegment = compareSegment(
                    leftSegments[index],
                    rightSegments[index]
            );

            if (bySegment != 0) {
                return bySegment;
            }
        }

        return Integer
                .compare(
                        leftSegments.length,
                        rightSegments.length
                );
    }

    // 숫자끼리는 자릿수와 값, 그 밖은 문자열 비교
    private static int compareSegment(
            String left,
            String right
    ) {

        if (isDigits(left) && isDigits(right)) {
            String leftNumber = stripLeadingZeros(left);
            String rightNumber = stripLeadingZeros(right);
            int byLength = Integer
                    .compare(
                            leftNumber
                                    .length(),
                            rightNumber
                                    .length()
                    );

            return byLength != 0
                    ? byLength
                    : leftNumber
                            .compareTo(rightNumber);
        }

        return left
                .compareTo(right);
    }

    // 수식어 없음(정식 버전)을 가장 뒤로 두는 비교
    private static int compareQualifier(
            String left,
            String right
    ) {

        if (left == null || right == null) {
            return Boolean
                    .compare(
                            left == null,
                            right == null
                    );
        }

        return left
                .compareTo(right);
    }

    // 비어 있지 않은 ASCII 숫자열 여부
    private static boolean isDigits(String value) {

        return !value
                .isEmpty() && value
                        .chars()
                        .allMatch(character -> character >= '0' && character <= '9');
    }

    // 앞쪽 0을 뺀 숫자열, 전부 0이면 "0"
    private static String stripLeadingZeros(String digits) {

        String stripped = digits
                .replaceFirst(
                        "^0+",
                        ""
                );

        return stripped
                .isEmpty()
                ? "0"
                : stripped;
    }
}
