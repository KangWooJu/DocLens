package io.github.kangwooju.doclens.pipeline.chunk.helper;

import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkMember;
import io.github.kangwooju.doclens.pipeline.chunk.dto.ChunkPiece;
import io.github.kangwooju.doclens.pipeline.chunk.type.MemberRole;
import io.github.kangwooju.doclens.pipeline.parse.dto.Block;
import io.github.kangwooju.doclens.pipeline.parse.dto.InlineCode;
import io.github.kangwooju.doclens.pipeline.parse.dto.TabGroup;
import io.github.kangwooju.doclens.pipeline.parse.type.BlockKind;

/**
 * 정확 일치 검색용 식별자, 원문 대소문자 그대로, 비기본 탭 변형 제외
 * 인라인 코드 전체와 코드 안 annotation, 점 표기 이름, CamelCase 타입
 */
@Component
public class IdentifierHelper {

    /** 식별자 최대 길이(code point) */
    public static final int MAX_LENGTH = 256;

    private static final List<Pattern> CODE_PATTERNS = List
            .of(
                    Pattern
                            .compile("(?<![\\w.@{])@[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*)*"),
                    Pattern
                            .compile("(?<![\\w.@-])[a-z][\\w-]*(?:\\.[A-Za-z0-9][\\w-]*)+"),
                    Pattern
                            .compile("(?<![\\w.])[A-Z][A-Za-z0-9]+(?![\\w])")
            );

    /** 청크 PRIMARY 구성원의 식별자, 중복 없이 code point 순 */
    public List<String> identifiers(List<ChunkMember> members) {

        TreeSet<String> identifiers = new TreeSet<>(IdentifierHelper::compareCodePoints);

        for (ChunkMember member : members) {
            if (member
                    .role() != MemberRole.PRIMARY) {
                continue;
            }

            ChunkPiece piece = member
                    .piece();

            for (InlineCode code : piece
                    .inlineCodes()) {
                if (isIdentifier(code
                        .text())) {
                    identifiers
                            .add(code
                                    .text());
                }
            }

            for (String code : codeTexts(piece
                    .rendered()
                    .block())) {
                addMatches(
                        code,
                        identifiers
                );
            }
        }

        return List
                .copyOf(identifiers);
    }

    // 정규식 대상 코드, CODE 블록과 탭 그룹의 기본 변형 CODE
    private static List<String> codeTexts(Block block) {

        if (block
                .kind() == BlockKind.CODE) {
            return List
                    .of(block
                            .text());
        }

        if (block
                .kind() != BlockKind.TAB_GROUP) {
            return List
                    .of();
        }

        TabGroup group = block
                .tabGroup();

        return group
                .variants()
                .stream()
                .filter(variant -> variant
                        .variantKey()
                        .equals(group
                                .defaultVariant()))
                .flatMap(variant -> variant
                        .blocks()
                        .stream())
                .filter(inner -> inner
                        .kind() == BlockKind.CODE)
                .map(Block::text)
                .toList();
    }

    // 코드에서 세 패턴의 일치 추가, 타입 이름은 소문자 1개와 대문자 2개 이상
    private static void addMatches(
            String code,
            TreeSet<String> identifiers
    ) {

        for (int index = 0; index < CODE_PATTERNS
                .size(); index++) {
            Matcher matcher = CODE_PATTERNS
                    .get(index)
                    .matcher(code);

            while (matcher
                    .find()) {
                String match = matcher
                        .group();
                boolean typeName = index == 2;

                if (typeName && !isCamelCase(match)) {
                    continue;
                }

                if (match
                        .codePointCount(
                                0,
                                match
                                        .length()
                        ) <= MAX_LENGTH) {
                    identifiers
                            .add(match);
                }
            }
        }
    }

    // 인라인 코드 전체를 식별자로 쓸 수 있는지, 공백 없음과 길이
    private static boolean isIdentifier(String text) {

        int length = text
                .codePointCount(
                        0,
                        text
                                .length()
                );

        return length >= 1 && length <= MAX_LENGTH && text
                .codePoints()
                .noneMatch(Character::isWhitespace);
    }

    // 소문자 1개 이상, 대문자 2개 이상인 타입 이름
    private static boolean isCamelCase(String text) {

        long upper = text
                .chars()
                .filter(Character::isUpperCase)
                .count();
        boolean lower = text
                .chars()
                .anyMatch(Character::isLowerCase);

        return lower && upper >= 2;
    }

    // code point 순 비교
    private static int compareCodePoints(
            String left,
            String right
    ) {

        int[] a = left
                .codePoints()
                .toArray();
        int[] b = right
                .codePoints()
                .toArray();

        return Arrays
                .compare(
                        a,
                        b
                );
    }
}
