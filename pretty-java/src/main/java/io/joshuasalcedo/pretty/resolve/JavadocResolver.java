package io.joshuasalcedo.pretty.resolve;

import io.joshuasalcedo.pretty.model.*;

import javax.tools.*;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

/**
 * Enriches source-parsed structures with what only a compiler knows, by running the JDK's own
 * javadoc engine (Doclet API) over the sources with the project's classpath:
 * <ul>
 *   <li>qualified names for types JDT left unresolved (same package, wildcard imports);</li>
 *   <li>javadoc for methods that use {@code {@inheritDoc}} or have none, taken from the method they override;</li>
 *   <li>members inherited from supertypes, including library and JDK types.</li>
 * </ul>
 * Only declarations are attributed (not method bodies), so sources need not compile fully;
 * unresolvable symbols simply stay unresolved. Requires running on a JDK, not a JRE.
 */
public final class JavadocResolver {

    private JavadocResolver() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * @param sourceFiles  the {@code .java} files to analyse together ({@code module-info.java} is skipped)
     * @param classpath    jars and class directories the sources compile against
     * @param encoding     source encoding
     * @param log          receives warnings, e.g. the first few unresolved symbols
     * @return the resolution, or empty when no documentation tool is available (JRE)
     */
    public static Optional<Resolution> resolve(List<Path> sourceFiles, List<Path> classpath, Charset encoding,
                                               Consumer<String> log) throws IOException {
        DocumentationTool tool = ToolProvider.getSystemDocumentationTool();
        if (tool == null) {
            log.accept("No javadoc tool available (running on a JRE?); using source-only resolution");
            return Optional.empty();
        }
        List<Path> sources = sourceFiles.stream()
                .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                .toList();
        if (sources.isEmpty()) {
            return Optional.of(new Resolution(Map.of()));
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = tool.getStandardFileManager(diagnostics, Locale.ROOT, encoding)) {
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath);
            List<String> options = List.of(
                    "--show-members", "private",
                    "--show-types", "private",
                    "--ignore-source-errors", // keep going past unresolvable symbols
                    "-quiet");
            Resolution resolution;
            synchronized (ResolverDoclet.class) {
                ResolverDoclet.RESULT.remove();
                DocumentationTool.DocumentationTask task = tool.getTask(new StringWriter(), files, diagnostics,
                        ResolverDoclet.class, options, files.getJavaFileObjectsFromPaths(sources));
                task.setLocale(Locale.ROOT);
                task.call();
                resolution = ResolverDoclet.RESULT.get();
                ResolverDoclet.RESULT.remove();
            }
            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .toList();
            if (!errors.isEmpty()) {
                log.accept(errors.size() + " symbol(s) could not be resolved, e.g. " + errors.getFirst().getMessage(Locale.ROOT)
                        + (errors.getFirst().getSource() != null ? " (" + errors.getFirst().getSource().getName() + ")" : ""));
            }
            if (resolution == null) {
                log.accept("javadoc did not run the resolver; using source-only resolution");
                return Optional.empty();
            }
            return Optional.of(resolution);
        }
    }

    /**
     * Applies a resolution to parsed files in place. Only fills gaps: existing qualified names and
     * javadoc are kept, except that {@code {@inheritDoc}} is expanded.
     *
     * @return the number of type references that were resolved
     */
    public static int apply(Resolution resolution, Collection<JavaFileStructure> files) {
        int[] resolved = {0};
        for (JavaFileStructure file : files) {
            for (TypeStructure type : file.getTypes()) {
                apply(resolution, type, resolved);
            }
        }
        return resolved[0];
    }

    private static void apply(Resolution resolution, TypeStructure type, int[] resolved) {
        Resolution.TypeInfo info = resolution.types().get(type.getQualifiedName());
        if (info != null) {
            Consumer<TypeReference> fill = ref -> fill(ref, info.simpleNames(), resolved);
            if (type.getSuperclass() != null && type.getSuperclass().getQualifiedName() == null) {
                type.getSuperclass().setQualifiedName(info.simpleNames().get(type.getSuperclass().getName()));
            }
            for (TypeStructure stub : type.getInterfaces()) {
                if (stub.getQualifiedName() == null) {
                    stub.setQualifiedName(info.simpleNames().get(stub.getName()));
                }
            }
            type.getTypeParameters().forEach(tp -> tp.getBounds().forEach(fill));
            type.getAnnotations().forEach(a -> fillAnnotation(a, info));
            type.getRecordComponents().forEach(c -> fill.accept(c.getType()));
            for (FieldStructure field : type.getFields()) {
                fill.accept(field.getType());
                field.getAnnotations().forEach(a -> fillAnnotation(a, info));
            }
            for (MethodStructure method : type.getMethods()) {
                if (method.getReturnType() != null) {
                    fill.accept(method.getReturnType());
                }
                method.getParameters().forEach(p -> fill.accept(p.getType()));
                method.getThrownExceptions().forEach(fill);
                method.getTypeParameters().forEach(tp -> tp.getBounds().forEach(fill));
                method.getAnnotations().forEach(a -> fillAnnotation(a, info));
                Resolution.InheritedDoc inherited = info.inheritedDocs().get(memberKey(method.getName(), method.getParameters()));
                if (inherited != null) {
                    method.setJavadoc(merge(method.getJavadoc(), inherited));
                }
            }
            for (ConstructorStructure ctor : type.getConstructors()) {
                ctor.getParameters().forEach(p -> fill.accept(p.getType()));
                ctor.getThrownExceptions().forEach(fill);
            }
            type.setInheritedMethods(new ArrayList<>(info.inheritedMethods()));
            type.setInheritedFields(new ArrayList<>(info.inheritedFields()));
        }
        type.getNestedTypes().forEach(nested -> apply(resolution, nested, resolved));
    }

    private static void fill(TypeReference ref, Map<String, String> names, int[] resolved) {
        if (ref == null) {
            return;
        }
        if (ref.getQualifiedName() == null && !ref.isWildcard() && !ref.isPrimitive()) {
            String qualified = names.get(ref.getName());
            if (qualified != null) {
                ref.setQualifiedName(qualified);
                resolved[0]++;
            }
        }
        if (ref.getTypeArguments() != null) {
            ref.getTypeArguments().forEach(arg -> fill(arg, names, resolved));
        }
        fill(ref.getWildcardBound(), names, resolved);
    }

    private static void fillAnnotation(AnnotationStructure annotation, Resolution.TypeInfo info) {
        if (annotation.getQualifiedName() == null) {
            annotation.setQualifiedName(info.simpleNames().get(annotation.getName()));
        }
    }

    /** Method key shared with {@link ResolverDoclet}: name(SimpleType,SimpleType[]). */
    static String memberKey(String name, List<ParameterStructure> parameters) {
        StringJoiner joiner = new StringJoiner(",", name + "(", ")");
        for (ParameterStructure p : parameters) {
            joiner.add(p.getType().getName() + "[]".repeat(p.getType().isArray() ? p.getType().getArrayDimensions() : 0));
        }
        return joiner.toString();
    }

    /**
     * Javadoc's inheritance rules, simplified: no own doc means the inherited one; {@code {@inheritDoc}}
     * in the description or a tag is replaced by the inherited text; missing @param/@return/@throws are copied.
     */
    static JavadocStructure merge(JavadocStructure own, Resolution.InheritedDoc inherited) {
        JavadocStructure source = inherited.javadoc();
        if (own == null) {
            return JavadocStructure.builder()
                    .description(source.getDescription())
                    .tags(new ArrayList<>(source.getTags()))
                    .inheritedFrom(inherited.from())
                    .build();
        }
        boolean usesInheritDoc = own.getDescription().contains("{@inheritDoc}")
                || own.getTags().stream().anyMatch(t -> t.getText().contains("{@inheritDoc}"));
        if (!usesInheritDoc) {
            return own;
        }
        List<JavadocTagStructure> tags = new ArrayList<>();
        for (JavadocTagStructure tag : own.getTags()) {
            String replacement = source.getTags().stream()
                    .filter(t -> t.getName().equals(tag.getName()) && Objects.equals(t.getParamName(), tag.getParamName()))
                    .map(JavadocTagStructure::getText)
                    .findFirst().orElse("");
            tags.add(JavadocTagStructure.builder()
                    .name(tag.getName())
                    .paramName(tag.getParamName())
                    .text(tag.getText().replace("{@inheritDoc}", replacement).strip())
                    .position(tags.size())
                    .build());
        }
        for (JavadocTagStructure tag : source.getTags()) {
            boolean inheritable = Set.of("@param", "@return", "@throws", "@exception").contains(tag.getName());
            boolean present = tags.stream().anyMatch(t -> t.getName().equals(tag.getName()) && Objects.equals(t.getParamName(), tag.getParamName()));
            if (inheritable && !present) {
                tags.add(JavadocTagStructure.builder().name(tag.getName()).paramName(tag.getParamName())
                        .text(tag.getText()).position(tags.size()).build());
            }
        }
        return JavadocStructure.builder()
                .description(own.getDescription().replace("{@inheritDoc}", source.getDescription()).strip())
                .tags(tags)
                .startLine(own.getStartLine())
                .endLine(own.getEndLine())
                .inheritedFrom(inherited.from())
                .build();
    }
}
