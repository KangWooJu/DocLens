import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

        String[] lines = source
                .split("\n");
        int count = lines.length;

        for (int index = 0; index < count; index++) {
            String line = lines[index]
                    .strip();
            boolean comment = line
                    .startsWith("//")
                    || line
                            .startsWith("/**")
                    || line
                            .startsWith("* ");
            boolean displayName = line
                    .startsWith("@DisplayName(");

            if (!comment && !displayName) {
                continue;
            }

            for (Map.Entry<Pattern, String> rule : WORDING_RULES
                    .entrySet()) {
                if (rule
                        .getKey()
                        .matcher(line)
                        .find()) {
                    violations
                            .add(path + ":" + (index + 1) + ": " + rule
                                    .getValue());
                }
            }
        }
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
