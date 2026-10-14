package io.joshuasalcedo.pretty;

import io.joshuasalcedo.pretty.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JavaParserTest {

    private static final String REPOSITORY = """
            package com.example.store;

            import java.util.List;
            import java.util.Map;
            import java.io.IOException;
            import static java.util.Objects.requireNonNull;

            // file-level note

            /**
             * Stores items by key.
             * Second line with {@code inline} code.
             *
             * @param <T> the item type
             * @author Someone
             * @see Map
             */
            @Deprecated(since = "1.0")
            public class Repository<T extends Comparable<T>> extends Base implements Iterable<T>, AutoCloseable {

                /** Number of saves. */
                private int count = 0;
                protected final Map<String, List<T>> items, backup;
                String[] names[];

                public Repository() {
                    this(16);
                }

                Repository(int capacity) {
                    super();
                    // sized constructor
                    this.count = capacity > 0 ? 0 : -1;
                }

                /**
                 * Saves an item.
                 *
                 * @param key the key
                 * @param item the item
                 * @return true when saved
                 * @throws IOException when storage fails
                 */
                @Override
                public synchronized boolean save(final String key, T item) throws IOException {
                    requireNonNull(key);
                    if (key.isEmpty() || item == null) {
                        return false;
                    }
                    for (int i = 0; i < 3; i++) {
                        count++;
                    }
                    log(key);
                    return true;
                }

                private void log(String... messages) {
                    System.out.println(count);
                }

                public <R> R map(java.util.function.Function<? super T, ? extends R> fn, List<?> any) {
                    int count = 1; // shadows the field
                    return fn.apply(null);
                }

                static class Entry {
                    Repository<?> owner;
                }

                public java.util.Iterator<T> iterator() { return null; }
                public void close() {}
            }
            """;

    @Test
    void parsesFileLevelInformation() {
        JavaFileStructure file = JavaParser.parse(REPOSITORY, "Repository.java");

        assertEquals("Repository.java", file.getFileName());
        assertNull(file.getFilePath());
        assertEquals("com.example.store", file.getPackageName());
        assertEquals(4, file.getImports().size());
        ImportStructure staticImport = file.getImports().get(3);
        assertTrue(staticImport.isStatic());
        assertEquals("java.util.Objects.requireNonNull", staticImport.getQualifiedName());
        assertTrue(file.getFileComments().stream().anyMatch(c -> c.getKind() == CommentKind.LINE && c.getContent().equals("file-level note")));
        assertEquals(1, file.getTypes().size());
    }

    @Test
    void parsesTypeDeclaration() {
        TypeStructure type = JavaParser.parse(REPOSITORY, "Repository.java").getTypes().getFirst();

        assertEquals("Repository", type.getName());
        assertEquals("com.example.store.Repository", type.getQualifiedName());
        assertEquals(TypeKind.CLASS, type.getKind());
        assertEquals(List.of(ModifierKind.PUBLIC), type.getModifiers().stream().map(ModifierStructure::getKind).toList());

        AnnotationStructure deprecated = type.getAnnotations().getFirst();
        assertEquals("Deprecated", deprecated.getName());
        assertEquals("java.lang.Deprecated", deprecated.getQualifiedName());
        assertEquals("\"1.0\"", deprecated.getValues().get("since"));

        TypeParameterStructure typeParameter = type.getTypeParameters().getFirst();
        assertEquals("T", typeParameter.getName());
        TypeReference bound = typeParameter.getBounds().getFirst();
        assertEquals("java.lang.Comparable", bound.getQualifiedName());
        assertEquals("T", bound.getTypeArguments().getFirst().getName());
        assertNull(bound.getTypeArguments().getFirst().getQualifiedName(), "type parameters are not resolved");

        assertEquals("Base", type.getSuperclass().getName());
        assertNull(type.getSuperclass().getQualifiedName(), "same-package types cannot be resolved syntactically");
        assertEquals(List.of("java.lang.Iterable", "java.lang.AutoCloseable"),
                type.getInterfaces().stream().map(TypeStructure::getQualifiedName).toList());

        assertEquals(1, type.getNestedTypes().size());
        assertEquals("com.example.store.Repository.Entry", type.getNestedTypes().getFirst().getQualifiedName());
        assertEquals(18, type.getStartLine(), "position starts at the declaration (its annotation), after its javadoc");
    }

    @Test
    void parsesJavadoc() {
        TypeStructure type = JavaParser.parse(REPOSITORY, "Repository.java").getTypes().getFirst();
        JavadocStructure doc = type.getJavadoc();

        assertEquals("Stores items by key.\nSecond line with {@code inline} code.", doc.getDescription());
        assertEquals(List.of("@param", "@author", "@see"), doc.getTags().stream().map(JavadocTagStructure::getName).toList());
        assertEquals("<T>", doc.getTags().get(0).getParamName());
        assertEquals("the item type", doc.getTags().get(0).getText());
        assertEquals("Map", doc.getTags().get(2).getText());

        MethodStructure save = method(type, "save");
        assertEquals("Saves an item.", save.getJavadoc().getDescription());
        JavadocTagStructure keyParam = save.getJavadoc().getTags().getFirst();
        assertEquals("key", keyParam.getParamName());
        assertEquals("the key", keyParam.getText());
        assertEquals("IOException when storage fails", save.getJavadoc().getTags().get(3).getText());
    }

    @Test
    void parsesFields() {
        TypeStructure type = JavaParser.parse(REPOSITORY, "Repository.java").getTypes().getFirst();

        FieldStructure count = field(type, "count");
        assertEquals("int", count.getType().getName());
        assertTrue(count.getType().isPrimitive());
        assertEquals("0", count.getInitializer());
        assertEquals("Number of saves.", count.getJavadoc().getDescription());

        FieldStructure items = field(type, "items");
        FieldStructure backup = field(type, "backup");
        assertEquals("java.util.Map", items.getType().getQualifiedName());
        assertEquals(backup.getType(), items.getType());
        TypeReference listOfT = items.getType().getTypeArguments().get(1);
        assertEquals("java.util.List", listOfT.getQualifiedName());
        assertEquals("T", listOfT.getTypeArguments().getFirst().getName());
        assertEquals(List.of(ModifierKind.PROTECTED, ModifierKind.FINAL),
                items.getModifiers().stream().map(ModifierStructure::getKind).toList());

        FieldStructure names = field(type, "names");
        assertTrue(names.getType().isArray());
        assertEquals(2, names.getType().getArrayDimensions(), "String[] names[] has two dimensions");
        assertEquals(List.of(ModifierKind.PACKAGE_PRIVATE), names.getModifiers().stream().map(ModifierStructure::getKind).toList());
    }

    @Test
    void parsesMethodsAndParameters() {
        TypeStructure type = JavaParser.parse(REPOSITORY, "Repository.java").getTypes().getFirst();

        MethodStructure save = method(type, "save");
        assertEquals("boolean", save.getReturnType().getName());
        assertEquals(List.of(ModifierKind.PUBLIC, ModifierKind.SYNCHRONIZED),
                save.getModifiers().stream().map(ModifierStructure::getKind).toList());
        assertEquals("Override", save.getAnnotations().getFirst().getName());
        assertEquals("java.io.IOException", save.getThrownExceptions().getFirst().getQualifiedName());
        ParameterStructure key = save.getParameters().getFirst();
        assertEquals("key", key.getName());
        assertTrue(key.isFinal());
        assertEquals("java.lang.String", key.getType().getQualifiedName());
        assertEquals(3, key.getUsageLocations().size(), "key is used three times in the body");
        assertTrue(save.getBody().startsWith("{"));

        MethodStructure log = method(type, "log");
        ParameterStructure messages = log.getParameters().getFirst();
        assertTrue(messages.isVarArgs());
        assertTrue(messages.getType().isArray());

        MethodStructure map = method(type, "map");
        assertEquals("R", map.getTypeParameters().getFirst().getName());
        TypeReference function = map.getParameters().getFirst().getType();
        assertEquals("java.util.function.Function", function.getQualifiedName());
        TypeReference superT = function.getTypeArguments().get(0);
        assertTrue(superT.isWildcard());
        assertEquals(WildcardKind.SUPER, superT.getWildcardKind());
        assertEquals("T", superT.getWildcardBound().getName());
        assertEquals(WildcardKind.EXTENDS, function.getTypeArguments().get(1).getWildcardKind());
        assertEquals(WildcardKind.UNBOUNDED, map.getParameters().get(1).getType().getTypeArguments().getFirst().getWildcardKind());
        assertTrue(map.getComments().stream().anyMatch(c -> c.getContent().equals("shadows the field")));
    }

    @Test
    void computesComplexity() {
        TypeStructure type = JavaParser.parse(REPOSITORY, "Repository.java").getTypes().getFirst();

        // 1 + if + || + for
        assertEquals(4, method(type, "save").getCyclomaticComplexity());
        assertEquals(1, method(type, "log").getCyclomaticComplexity());
        assertEquals(3, method(type, "log").getLineCount());
    }

    @Test
    void linksCallsAndFieldAccessWithinType() {
        TypeStructure type = JavaParser.parse(REPOSITORY, "Repository.java").getTypes().getFirst();

        MethodStructure save = method(type, "save");
        assertTrue(save.getCalls().stream().anyMatch(c -> c.getMethodName().equals("log")
                && "com.example.store.Repository".equals(c.getQualifiedDeclaringTypeName())));
        assertTrue(save.getCalls().stream().anyMatch(c -> c.getMethodName().equals("isEmpty") && c.getDeclaringTypeName() == null));

        MethodStructure log = method(type, "log");
        assertEquals(List.of("save"), log.getCalledBy().stream().map(MethodReference::getMethodName).toList());
        assertEquals("String", log.getCalledBy().getFirst().getParameterTypes().getFirst().getName());

        FieldStructure count = field(type, "count");
        assertEquals(List.of("Repository", "save"), count.getWrittenBy().stream().map(MethodReference::getMethodName).toList());
        assertEquals(List.of("save", "log"), count.getReadBy().stream().map(MethodReference::getMethodName).toList(),
                "map() declares a local 'count', so its use is not a field read");

        ConstructorStructure noArgs = type.getConstructors().get(0);
        ConstructorStructure sized = type.getConstructors().get(1);
        assertEquals("Repository", noArgs.getCalls().getFirst().getTypeName());
        assertEquals(List.of("Repository"), sized.getCalledBy().stream().map(MethodReference::getMethodName).toList());
        assertEquals("Base", sized.getCalls().getFirst().getTypeName(), "super() calls the superclass constructor");
        assertTrue(sized.getComments().stream().anyMatch(c -> c.getContent().equals("sized constructor")));
        assertEquals(List.of(ModifierKind.PACKAGE_PRIVATE), sized.getModifiers().stream().map(ModifierStructure::getKind).toList());
    }

    @Test
    void parsesEnumsRecordsInterfacesAndAnnotations() {
        String source = """
                package com.example;

                public interface Shape {
                    double area();

                    enum Color {
                        /** Warm. */
                        RED("#f00"), GREEN("#0f0");
                        private final String hex;
                        Color(String hex) { this.hex = hex; }
                    }

                    /**
                     * A point.
                     * @param x horizontal
                     * @param y vertical
                     */
                    record Point(int x, int y) implements Shape {
                        public double area() { return 0; }
                    }

                    @interface Tag {
                        String value() default "";
                    }

                    sealed interface Closed permits Open {}
                    non-sealed interface Open extends Closed {}
                }
                """;
        TypeStructure shape = JavaParser.parse(source, "Shape.java").getTypes().getFirst();

        assertEquals(TypeKind.INTERFACE, shape.getKind());
        MethodStructure area = method(shape, "area");
        assertNull(area.getBody());
        assertTrue(area.getModifiers().isEmpty(), "interface members are implicitly public, not package-private");

        TypeStructure color = nested(shape, "Color");
        assertEquals(TypeKind.ENUM, color.getKind());
        assertEquals(List.of("RED", "GREEN"), color.getEnumConstants().stream().map(EnumConstantStructure::getName).toList());
        assertEquals(List.of("\"#0f0\""), color.getEnumConstants().get(1).getArguments());
        assertEquals(1, color.getEnumConstants().get(1).getOrdinal());
        assertEquals("Warm.", color.getEnumConstants().getFirst().getJavadoc().getDescription());
        assertEquals(List.of("Color"), field(color, "hex").getWrittenBy().stream().map(MethodReference::getMethodName).toList());

        TypeStructure point = nested(shape, "Point");
        assertEquals(TypeKind.RECORD, point.getKind());
        assertEquals(List.of("x", "y"), point.getRecordComponents().stream().map(RecordComponentStructure::getName).toList());
        assertEquals("vertical", point.getRecordComponents().get(1).getJavadoc().getDescription());
        assertEquals("com.example.Shape", point.getInterfaces().getFirst().getQualifiedName());

        TypeStructure tag = nested(shape, "Tag");
        assertEquals(TypeKind.ANNOTATION, tag.getKind());
        assertEquals("String", method(tag, "value").getReturnType().getName());

        assertEquals(List.of(ModifierKind.SEALED), nested(shape, "Closed").getModifiers().stream().map(ModifierStructure::getKind).toList());
        assertEquals(List.of(ModifierKind.NON_SEALED), nested(shape, "Open").getModifiers().stream().map(ModifierStructure::getKind).toList());

        assertTrue(shape.getUsedBy().contains("com.example.Shape.Point"));
    }

    @Test
    void handlesModernSyntax() {
        String source = """
                class Modern {
                    sealed interface Expr permits Num, Add {}
                    record Num(int value) implements Expr {}
                    record Add(Expr left, Expr right) implements Expr {}

                    static int eval(Expr expr) {
                        return switch (expr) {
                            case Num(var value) -> value;
                            case Add(Expr l, Expr r) when l != null -> eval(l) + eval(r);
                            default -> throw new IllegalStateException();
                        };
                    }

                    static String text(Object o) {
                        var builder = new StringBuilder();
                        if (o instanceof String s && !s.isBlank()) builder.append(s);
                        return builder.toString();
                    }
                }
                """;
        JavaFileStructure file = JavaParser.parse(source, "Modern.java");
        TypeStructure modern = file.getTypes().getFirst();

        assertEquals("", file.getPackageName());
        assertEquals("Modern", modern.getQualifiedName());
        MethodStructure eval = method(modern, "eval");
        assertEquals(2, eval.getCalledBy().size(), "eval is called recursively twice");
        assertTrue(eval.getCyclomaticComplexity() >= 3);
        assertEquals(3, modern.getNestedTypes().size());
        assertEquals(List.of("isBlank", "append", "toString"),
                method(modern, "text").getCalls().stream().map(MethodReference::getMethodName).toList());
    }

    @Test
    void parsesDirectoryAndLinksUsedByAcrossFiles(@TempDir Path dir) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("com/example"));
        Files.writeString(pkg.resolve("Engine.java"), "package com.example;\npublic class Engine { void start() {} }\n");
        Files.writeString(pkg.resolve("Car.java"), """
                package com.example;
                public class Car {
                    private final Engine engine = new Engine();
                }
                """);
        Files.writeString(pkg.resolve("Garage.java"), """
                package com.example;
                import java.util.List;
                public class Garage {
                    List<Car> cars;
                    void fill() { var e = Engine.class; }
                }
                """);

        List<JavaFileStructure> files = JavaParser.parseDirectory(dir);

        assertEquals(List.of("Car.java", "Engine.java", "Garage.java"), files.stream().map(JavaFileStructure::getFileName).toList());
        TypeStructure engine = files.get(1).getTypes().getFirst();
        TypeStructure car = files.getFirst().getTypes().getFirst();
        assertEquals(List.of("com.example.Car", "com.example.Garage"), engine.getUsedBy(), "Garage uses Engine.class");
        assertEquals(List.of("com.example.Garage"), car.getUsedBy());
        assertTrue(files.getFirst().getFilePath().endsWith("Car.java"));

        String json = JavaParser.toJson(files, true);
        assertTrue(json.contains("\"qualifiedName\": \"com.example.Engine\""));
    }

    @Test
    void countsLinesLikeCloc() {
        String source = "package a; // trailing comment counts as code\n"
                + "\n"
                + "/**\n"
                + " * Doc.\n"
                + " */\n"
                + "public class A {\n"
                + "    // note\n"
                + "    int x; /* c */\n"
                + "}\n";
        JavaFileStructure file = JavaParser.parse(source, "A.java");

        assertEquals(9, file.getTotalLines());
        assertEquals(4, file.getCodeLines());
        assertEquals(4, file.getCommentLines());
        assertEquals(1, file.getBlankLines());
    }

    @Test
    void parsesThisModulesOwnSources() throws IOException {
        List<JavaFileStructure> files = JavaParser.parseDirectory(Path.of("src/main/java"));

        assertTrue(files.size() > 20);
        TypeStructure converter = files.stream()
                .flatMap(f -> f.getTypes().stream())
                .filter(t -> t.getName().equals("CompilationUnitConverter"))
                .findFirst().orElseThrow();
        assertFalse(converter.getMethods().isEmpty());
        assertTrue(files.stream().flatMap(f -> f.getTypes().stream())
                .filter(t -> t.getName().equals("TypeReference")).findFirst().orElseThrow()
                .getUsedBy().contains("io.joshuasalcedo.pretty.model.FieldStructure"));
    }

    private static MethodStructure method(TypeStructure type, String name) {
        return type.getMethods().stream().filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
    }

    private static FieldStructure field(TypeStructure type, String name) {
        return type.getFields().stream().filter(f -> f.getName().equals(name)).findFirst().orElseThrow();
    }

    private static TypeStructure nested(TypeStructure type, String name) {
        return type.getNestedTypes().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow();
    }
}
