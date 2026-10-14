package io.joshuasalcedo.pretty.resolve;

import com.sun.source.doctree.*;
import com.sun.source.util.DocTrees;
import io.joshuasalcedo.pretty.model.*;
import jdk.javadoc.doclet.Doclet;
import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.*;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Doclet run by {@link JavadocResolver}; collects a {@link Resolution} instead of writing HTML.
 * Public with a no-arg constructor because the javadoc tool instantiates it reflectively.
 */
public final class ResolverDoclet implements Doclet {

    /** Hand-off to {@link JavadocResolver}; javadoc runs the doclet on the calling thread. */
    static final ThreadLocal<Resolution> RESULT = new ThreadLocal<>();

    private Elements elements;
    private Types types;
    private DocTrees docTrees;

    @Override
    public void init(Locale locale, Reporter reporter) {
    }

    @Override
    public String getName() {
        return "pretty-docs-resolver";
    }

    @Override
    public Set<? extends Option> getSupportedOptions() {
        return Set.of();
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean run(DocletEnvironment environment) {
        elements = environment.getElementUtils();
        types = environment.getTypeUtils();
        docTrees = environment.getDocTrees();

        Map<String, Resolution.TypeInfo> result = new HashMap<>();
        Deque<TypeElement> pending = new ArrayDeque<>(ElementFilter.typesIn(environment.getIncludedElements()));
        Set<TypeElement> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            TypeElement type = pending.pop();
            if (!seen.add(type)) {
                continue;
            }
            result.put(type.getQualifiedName().toString(), analyse(type));
            pending.addAll(ElementFilter.typesIn(type.getEnclosedElements()));
        }
        RESULT.set(new Resolution(result));
        return true;
    }

    private Resolution.TypeInfo analyse(TypeElement type) {
        return new Resolution.TypeInfo(simpleNames(type), inheritedDocs(type), inheritedMethods(type), inheritedFields(type));
    }

    // ------------------------------------------------------------ names

    private Map<String, String> simpleNames(TypeElement type) {
        Map<String, Set<String>> names = new HashMap<>();
        collect(type.getSuperclass(), names);
        type.getInterfaces().forEach(t -> collect(t, names));
        type.getTypeParameters().forEach(tp -> tp.getBounds().forEach(b -> collect(b, names)));
        type.getRecordComponents().forEach(c -> collect(c.asType(), names));
        annotations(type, names);
        for (Element member : type.getEnclosedElements()) {
            annotations(member, names);
            if (member instanceof VariableElement field) {
                collect(field.asType(), names);
            } else if (member instanceof ExecutableElement executable) {
                collect(executable.getReturnType(), names);
                executable.getParameters().forEach(p -> {
                    collect(p.asType(), names);
                    annotations(p, names);
                });
                executable.getThrownTypes().forEach(t -> collect(t, names));
                executable.getTypeParameters().forEach(tp -> tp.getBounds().forEach(b -> collect(b, names)));
            }
        }
        Map<String, String> unique = new HashMap<>();
        names.forEach((simple, qualified) -> {
            if (qualified.size() == 1) {
                unique.put(simple, qualified.iterator().next());
            }
        });
        return unique;
    }

    private void annotations(Element element, Map<String, Set<String>> names) {
        element.getAnnotationMirrors().forEach(a -> collect(a.getAnnotationType(), names));
    }

    private void collect(TypeMirror mirror, Map<String, Set<String>> names) {
        switch (mirror) {
            case DeclaredType declared when declared.asElement() instanceof TypeElement element -> {
                names.computeIfAbsent(element.getSimpleName().toString(), k -> new TreeSet<>())
                        .add(element.getQualifiedName().toString());
                declared.getTypeArguments().forEach(arg -> collect(arg, names));
            }
            case ArrayType array -> collect(array.getComponentType(), names);
            case WildcardType wildcard -> {
                if (wildcard.getExtendsBound() != null) collect(wildcard.getExtendsBound(), names);
                if (wildcard.getSuperBound() != null) collect(wildcard.getSuperBound(), names);
            }
            case IntersectionType intersection -> intersection.getBounds().forEach(b -> collect(b, names));
            case UnionType union -> union.getAlternatives().forEach(a -> collect(a, names));
            default -> { } // primitives, type variables, errors
        }
    }

    // ------------------------------------------------------- inheritance

    /** Docs for methods that have none or use {@code {@inheritDoc}}, from the nearest documented overridden method. */
    private Map<String, Resolution.InheritedDoc> inheritedDocs(TypeElement type) {
        Map<String, Resolution.InheritedDoc> docs = new HashMap<>();
        for (ExecutableElement method : ElementFilter.methodsIn(type.getEnclosedElements())) {
            DocCommentTree own = docTrees.getDocCommentTree(method);
            if (own != null && !own.toString().contains("{@inheritDoc}")) {
                continue;
            }
            overridden(method, type).ifPresent(overridden -> docs.put(key(method),
                    new Resolution.InheritedDoc(((TypeElement) overridden.getEnclosingElement()).getQualifiedName().toString(),
                            toJavadoc(docTrees.getDocCommentTree(overridden)))));
        }
        return docs;
    }

    /** Superclass chain first, then interfaces, breadth-first; only methods with a doc comment (i.e. from source). */
    private Optional<ExecutableElement> overridden(ExecutableElement method, TypeElement type) {
        Deque<TypeMirror> queue = new ArrayDeque<>(types.directSupertypes(type.asType()));
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeMirror supertype = queue.poll();
            if (!(types.asElement(supertype) instanceof TypeElement superElement)
                    || !visited.add(superElement.getQualifiedName().toString())) {
                continue;
            }
            for (ExecutableElement candidate : ElementFilter.methodsIn(superElement.getEnclosedElements())) {
                if (elements.overrides(method, candidate, type)) {
                    DocCommentTree doc = docTrees.getDocCommentTree(candidate);
                    if (doc != null && !doc.toString().contains("{@inheritDoc}")) {
                        return Optional.of(candidate);
                    }
                    if (doc != null) {
                        Optional<ExecutableElement> further = overridden(candidate, superElement);
                        if (further.isPresent()) {
                            return further;
                        }
                    }
                }
            }
            queue.addAll(types.directSupertypes(supertype));
        }
        return Optional.empty();
    }

    private List<MethodReference> inheritedMethods(TypeElement type) {
        if (type.getKind() == ElementKind.ANNOTATION_TYPE) {
            return List.of();
        }
        return ElementFilter.methodsIn(elements.getAllMembers(type)).stream()
                .filter(m -> !m.getEnclosingElement().equals(type))
                .filter(m -> !m.getModifiers().contains(Modifier.PRIVATE))
                .filter(m -> !(m.getModifiers().contains(Modifier.STATIC) && m.getEnclosingElement().getKind().isInterface()))
                .map(m -> MethodReference.builder()
                        .methodName(m.getSimpleName().toString())
                        .declaringTypeName(m.getEnclosingElement().getSimpleName().toString())
                        .qualifiedDeclaringTypeName(((TypeElement) m.getEnclosingElement()).getQualifiedName().toString())
                        .parameterTypes(m.getParameters().stream().map(p -> reference(p.asType())).toList())
                        .build())
                .sorted(Comparator.comparing(MethodReference::getQualifiedDeclaringTypeName).thenComparing(MethodReference::getMethodName))
                .toList();
    }

    private List<FieldReference> inheritedFields(TypeElement type) {
        return ElementFilter.fieldsIn(elements.getAllMembers(type)).stream()
                .filter(f -> !f.getEnclosingElement().equals(type))
                .filter(f -> !f.getModifiers().contains(Modifier.PRIVATE))
                .map(f -> FieldReference.builder()
                        .fieldName(f.getSimpleName().toString())
                        .declaringTypeName(f.getEnclosingElement().getSimpleName().toString())
                        .qualifiedDeclaringTypeName(((TypeElement) f.getEnclosingElement()).getQualifiedName().toString())
                        .isRead(true)
                        .build())
                .sorted(Comparator.comparing(FieldReference::getQualifiedDeclaringTypeName).thenComparing(FieldReference::getFieldName))
                .toList();
    }

    private TypeReference reference(TypeMirror mirror) {
        TypeReference.TypeReferenceBuilder builder = TypeReference.builder().typeArguments(List.of());
        int dimensions = 0;
        while (mirror instanceof ArrayType array) {
            dimensions++;
            mirror = array.getComponentType();
        }
        if (mirror instanceof DeclaredType declared && declared.asElement() instanceof TypeElement element) {
            builder.name(element.getSimpleName().toString()).qualifiedName(element.getQualifiedName().toString());
        } else {
            builder.name(mirror.toString()).isPrimitive(mirror.getKind().isPrimitive());
        }
        return builder.isArray(dimensions > 0).arrayDimensions(dimensions).build();
    }

    /** Must match {@link JavadocResolver#memberKey}: simple names as written, type variables by name. */
    private static String key(ExecutableElement method) {
        return method.getSimpleName() + method.getParameters().stream()
                .map(p -> simpleName(p.asType()))
                .collect(Collectors.joining(",", "(", ")"));
    }

    private static String simpleName(TypeMirror mirror) {
        return switch (mirror) {
            case ArrayType array -> simpleName(array.getComponentType()) + "[]";
            case DeclaredType declared -> declared.asElement().getSimpleName().toString();
            case TypeVariable variable -> variable.asElement().getSimpleName().toString();
            default -> mirror.toString();
        };
    }

    // ---------------------------------------------------------- javadoc

    /** Same shape as the source parser produces: inline tags kept as written, "@tag" names. */
    private static JavadocStructure toJavadoc(DocCommentTree doc) {
        List<JavadocTagStructure> tags = new ArrayList<>();
        for (DocTree tag : doc.getBlockTags()) {
            String name = "@" + tag.getKind().tagName;
            String paramName = null;
            String text;
            switch (tag) {
                case ParamTree param -> {
                    paramName = param.isTypeParameter() ? "<" + param.getName() + ">" : param.getName().toString();
                    text = join(param.getDescription());
                }
                case ReturnTree ret -> text = join(ret.getDescription());
                case ThrowsTree t -> text = (t.getExceptionName() + " " + join(t.getDescription())).strip();
                case BlockTagTree block -> {
                    String written = tag.toString().strip();
                    name = "@" + block.getTagName();
                    text = written.startsWith(name) ? written.substring(name.length()).strip() : written;
                }
                default -> text = tag.toString();
            }
            tags.add(JavadocTagStructure.builder().name(name).paramName(paramName).text(text).position(tags.size()).build());
        }
        return JavadocStructure.builder().description(join(doc.getFullBody())).tags(tags).build();
    }

    private static String join(List<? extends DocTree> trees) {
        return trees.stream().map(DocTree::toString).collect(Collectors.joining()).strip();
    }
}
