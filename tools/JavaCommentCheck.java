import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.lang.model.element.Modifier;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/**
 * Java 주석 규칙 검사
 * 메서드와 생성자 위 주석 누락, public은 Javadoc, 그 밖은 // 한 줄
 * 주석 안 HTML 단락 태그, 가운뎃점, 화살표, 내부 문서 번호, 문장 어미, 마침표 금지
 */
public class JavaCommentCheck {

    private static final List<String> MODULES = List
            .of(
                    "knowledge-core",
                    "mcp-server",
                    "pipeline",
                    "scheduler-server",
                    "maintenance-cli"
            );

    private static final Map<Pattern, String> WORDING_RULES = Map
            .of(
                    Pattern
                            .compile("<p>"),
                    "<p> tag in comment",
                    Pattern
                            .compile("·"),
                    "middle dot, use a comma",
                    Pattern
                            .compile("→"),
                    "arrow in comment",
                    Pattern
                            .compile("ponytail:"),
                    "tool marker in comment",
                    Pattern
                            .compile("\\b(D|MC|F)\\d{1,2}\\b|순서 [A-G]\\b"),
                    "internal document code in comment",
                    Pattern
                            .compile("(한다|합니다|습니다)\\s*(\\*/)?$"),
                    "sentence ending, use a noun phrase",
                    Pattern
                            .compile("[^.]\\.\\s*(\\*/)?$"),
                    "trailing period"
            );

    /** 저장소 루트에서 실행, 위반이 있으면 종료 코드 1 */
    public static void main(String[] args) throws IOException {

        List<Path> files = javaFiles();
        List<String> violations = new ArrayList<>();
        JavaCompiler compiler = ToolProvider
                .getSystemJavaCompiler();

        try (StandardJavaFileManager fileManager = compiler
                .getStandardFileManager(
                        null,
                        null,
                        null
                )) {
            Iterable<? extends JavaFileObject> units = fileManager
                    .getJavaFileObjectsFromPaths(files);
            JavacTask task = (JavacTask) compiler
                    .getTask(
                            null,
                            fileManager,
                            diagnostic -> {
                            },
                            List
                                    .of("-proc:none"),
                            null,
                            units
                    );
            SourcePositions positions = Trees
                    .instance(task)
                    .getSourcePositions();

            for (CompilationUnitTree unit : task
                    .parse()) {
                check(
                        unit,
                        positions,
                        violations
                );
            }
        }

        violations
                .forEach(System.out::println);

        if (!violations
                .isEmpty()) {
            System.out
                    .println(violations
                            .size() + " comment rule violation(s)");
            System
                    .exit(1);
        }
    }

    // 모듈 src 아래 Java 파일 목록(build 제외)
    private static List<Path> javaFiles() throws IOException {

        List<Path> files = new ArrayList<>();

        for (String module : MODULES) {
            Path source = Path
                    .of(
                            module,
                            "src"
                    );

            if (!Files
                    .isDirectory(source)) {
                continue;
            }

            try (Stream<Path> paths = Files
                    .walk(source)) {
                paths
                        .filter(path -> path
                                .toString()
                                .endsWith(".java"))
                        .forEach(files::add);
            }
        }

        return files;
    }

    // 주석과 @DisplayName 문구의 금지 표현 검사
    private static void checkWording(
            String path,
            String source,
            List<String> violations
    ) {

        Map<Integer, String> lines = new TreeMap<>(commentLines(source));
        String[] sourceLines = source
                .split("\n");
        int count = sourceLines.length;

        for (int index = 0; index < count; index++) {
            String line = sourceLines[index]
                    .strip();

            if (line
                    .startsWith("@DisplayName(")) {
                lines
                        .put(
                                index + 1,
                                line
                        );
            }
        }

        for (Map.Entry<Integer, String> entry : lines
                .entrySet()) {
            String line = entry
                    .getValue()
                    .strip();

            for (Map.Entry<Pattern, String> rule : WORDING_RULES
                    .entrySet()) {
                if (rule
                        .getKey()
                        .matcher(line)
                        .find()) {
                    violations
                            .add(path + ":" + entry
                                    .getKey() + ": " + rule
                                            .getValue());
                }
            }
        }
    }

    // 문자열, 문자, 텍스트 블록을 건너뛰며 모은 줄별 주석 텍스트(구분 기호 포함)
    private static Map<Integer, String> commentLines(String source) {

        Map<Integer, StringBuilder> comments = new HashMap<>();
        int length = source
                .length();
        int line = 1;
        int index = 0;

        while (index < length) {
            char current = source
                    .charAt(index);

            if (current == '\n') {
                line++;
                index++;
            } else if (source
                    .startsWith(
                            "//",
                            index
                    )) {
                int end = source
                        .indexOf(
                                '\n',
                                index
                        );
                end = end < 0 ? length : end;
                comments
                        .computeIfAbsent(
                                line,
                                key -> new StringBuilder()
                        )
                        .append(source
                                .substring(
                                        index,
                                        end
                                ));
                index = end;
            } else if (source
                    .startsWith(
                            "/*",
                            index
                    )) {
                int end = source
                        .indexOf(
                                "*/",
                                index + 2
                        );
                end = end < 0 ? length : end + 2;

                for (String part : source
                        .substring(
                                index,
                                end
                        )
                        .split(
                                "\n",
                                -1
                        )) {
                    comments
                            .computeIfAbsent(
                                    line,
                                    key -> new StringBuilder()
                            )
                            .append(part);
                    line++;
                }

                line--;
                index = end;
            } else if (source
                    .startsWith(
                            "\"\"\"",
                            index
                    )) {
                int end = source
                        .indexOf(
                                "\"\"\"",
                                index + 3
                        );
                end = end < 0 ? length : end + 3;
                line += (int) source
                        .substring(
                                index,
                                end
                        )
                        .chars()
                        .filter(character -> character == '\n')
                        .count();
                index = end;
            } else if (current == '"' || current == '\'') {
                index = literalEnd(
                        source,
                        index,
                        current
                );
            } else {
                index++;
            }
        }

        Map<Integer, String> result = new HashMap<>();
        comments
                .forEach((key, value) -> result
                        .put(
                                key,
                                value
                                        .toString()
                        ));

        return result;
    }

    // 문자열이나 문자 리터럴 끝 다음 위치, 이스케이프는 건너뜀
    private static int literalEnd(
            String source,
            int start,
            char quote
    ) {

        int index = start + 1;
        int length = source
                .length();

        while (index < length) {
            char current = source
                    .charAt(index);

            if (current == '\\') {
                index += 2;
            } else if (current == quote || current == '\n') {
                return index + 1;
            } else {
                index++;
            }
        }

        return length;
    }

    // 한 파일의 주석 문구와 메서드 주석 검사
    private static void check(
            CompilationUnitTree unit,
            SourcePositions positions,
            List<String> violations
    ) throws IOException {

        String source = unit
                .getSourceFile()
                .getCharContent(true)
                .toString();
        String path = unit
                .getSourceFile()
                .getName();

        checkWording(
                path,
                source,
                violations
        );

        new TreePathScanner<Void, Void>() {

            /** 메서드 하나의 주석 존재와 종류 확인 */
            @Override
            public Void visitMethod(
                    MethodTree method,
                    Void unused
            ) {

                long start = positions
                        .getStartPosition(
                                unit,
                                method
                        );

                if (start >= 0 && !isTest(method)) {
                    String before = source
                            .substring(
                                    0,
                                    (int) start
                            )
                            .stripTrailing();
                    String lastLine = before
                            .substring(before
                                    .lastIndexOf('\n') + 1)
                            .strip();
                    boolean javadoc = before
                            .endsWith("*/");
                    boolean lineComment = lastLine
                            .startsWith("//");
                    boolean publicApi = isPublicApi(
                            method,
                            getCurrentPath()
                                    .getParentPath()
                                    .getLeaf()
                    );
                    long line = unit
                            .getLineMap()
                            .getLineNumber(start);
                    String location = path + ":" + line + ": " + method
                            .getName();

                    if (!javadoc && !lineComment) {
                        violations
                                .add(location + " missing comment");
                    } else if (publicApi && lineComment) {
                        violations
                                .add(location + " public API needs /** */");
                    } else if (!publicApi && javadoc) {
                        violations
                                .add(location + " non-public method needs //");
                    }
                }

                return super
                        .visitMethod(
                                method,
                                unused
                        );
            }
        }
                .scan(
                        unit,
                        null
                );
    }

    // @Test, @ParameterizedTest 메서드 여부
    private static boolean isTest(MethodTree method) {

        return method
                .getModifiers()
                .getAnnotations()
                .stream()
                .map(annotation -> annotation
                        .getAnnotationType()
                        .toString())
                .anyMatch(name -> name
                        .equals("Test")
                        || name
                                .equals("ParameterizedTest"));
    }

    // public 선언이나 interface 메서드 여부
    private static boolean isPublicApi(
            MethodTree method,
            Tree owner
    ) {

        boolean interfaceMethod = owner instanceof ClassTree type
                && type
                        .getKind() == Tree.Kind.INTERFACE;

        return interfaceMethod || method
                .getModifiers()
                .getFlags()
                .contains(Modifier.PUBLIC);
    }
}
