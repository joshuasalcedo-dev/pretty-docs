package io.joshuasalcedo.pretty;

import io.joshuasalcedo.pretty.model.*;
import io.joshuasalcedo.pretty.model.MethodReference;
import org.eclipse.jdt.core.dom.*;

import java.util.*;

/**
 * Converts a JDT {@link CompilationUnit} into a {@link JavaFileStructure}.
 * <p>
 * Works on syntax only (no binding resolution), so qualified names are resolved from the file's
 * package, imports, its own declared types and {@code java.lang}. Usage information (calls,
 * field reads/writes, {@code calledBy}) is linked within a type; cross-file {@code usedBy}
 * linking is done by {@link JavaParser#parseDirectory}.
 * <p>
 * Instances are single-use and not thread-safe.
 */
final class CompilationUnitConverter {

    private static final Set<String> PRIMITIVES =
            Set.of("boolean", "byte", "char", "short", "int", "long", "float", "double", "void");

    private final String source;
    private final CompilationUnit unit;
    private final String packageName;
    private final Map<String, String> importedTypes = new HashMap<>();
    private final Map<String, String> declaredTypes = new HashMap<>();
    private final Map<ASTNode, List<CommentStructure>> commentsByOwner = new IdentityHashMap<>();
    private final List<CommentStructure> fileComments = new ArrayList<>();
    private final Deque<TypeContext> typeContexts = new ArrayDeque<>();
    private final Deque<Set<String>> typeParameterScopes = new ArrayDeque<>();

    /** Fully qualified type name -> names of the types it references (resolved or same-package guesses). */
    private final Map<String, Set<String>> typeReferences = new LinkedHashMap<>();

    CompilationUnitConverter(String source, CompilationUnit unit) {
        this.source = source;
        this.unit = unit;
        this.packageName = unit.getPackage() == null ? "" : unit.getPackage().getName().getFullyQualifiedName();
    }

    /** Type references collected during {@link #convert}, keyed by referencing type. */
    Map<String, Set<String>> typeReferences() {
        return typeReferences;
    }

    JavaFileStructure convert(String fileName, String filePath) {
        List<ImportStructure> imports = new ArrayList<>();
        for (ImportDeclaration imp : nodes(unit.imports(), ImportDeclaration.class)) {
            String name = imp.getName().getFullyQualifiedName();
            if (!imp.isStatic() && !imp.isOnDemand()) {
                importedTypes.put(lastSegment(name), name);
            }
            imports.add(ImportStructure.builder()
                    .qualifiedName(name)
                    .isStatic(imp.isStatic())
                    .isWildcard(imp.isOnDemand())
                    .lineNumber(line(imp.getStartPosition()))
                    .build());
        }

        List<AbstractTypeDeclaration> topLevel = nodes(unit.types(), AbstractTypeDeclaration.class);
        for (AbstractTypeDeclaration decl : topLevel) {
            registerDeclaredTypes(decl, qualify(packageName, decl.getName().getIdentifier()));
        }
        assignComments();

        List<TypeStructure> types = new ArrayList<>();
        for (AbstractTypeDeclaration decl : topLevel) {
            types.add(convertType(decl, qualify(packageName, decl.getName().getIdentifier()), false));
        }

        int[] lines = countLines();
        return JavaFileStructure.builder()
                .fileName(fileName)
                .filePath(filePath)
                .packageName(packageName)
                .imports(imports)
                .types(types)
                .fileComments(fileComments)
                .totalLines(lines[0])
                .codeLines(lines[1])
                .commentLines(lines[2])
                .blankLines(lines[3])
                .build();
    }

    /** {total, code, comment-only, blank} lines; characters inside comment ranges count as comment. */
    private int[] countLines() {
        List<int[]> comments = new ArrayList<>();
        for (Comment comment : nodes(unit.getCommentList(), Comment.class)) {
            comments.add(new int[]{comment.getStartPosition(), comment.getStartPosition() + comment.getLength()});
        }
        comments.sort(Comparator.comparingInt(range -> range[0]));

        int lineCount = source.isEmpty() ? 0 : (int) source.chars().filter(c -> c == '\n').count() + (source.endsWith("\n") ? 0 : 1);
        boolean[] code = new boolean[lineCount + 1];
        boolean[] comment = new boolean[lineCount + 1];
        int line = 0;
        int next = 0; // index of the first comment range not yet passed
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\n') {
                line++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                continue;
            }
            while (next < comments.size() && comments.get(next)[1] <= i) {
                next++;
            }
            boolean inComment = next < comments.size() && comments.get(next)[0] <= i;
            (inComment ? comment : code)[line] = true;
        }
        int codeLines = 0;
        int commentLines = 0;
        for (int i = 0; i < lineCount; i++) {
            if (code[i]) {
                codeLines++;
            } else if (comment[i]) {
                commentLines++;
            }
        }
        return new int[]{lineCount, codeLines, commentLines, lineCount - codeLines - commentLines};
    }

    // ---------------------------------------------------------------- types

    private void registerDeclaredTypes(AbstractTypeDeclaration decl, String qualifiedName) {
        declaredTypes.putIfAbsent(decl.getName().getIdentifier(), qualifiedName);
        for (AbstractTypeDeclaration nested : nodes(decl.bodyDeclarations(), AbstractTypeDeclaration.class)) {
            registerDeclaredTypes(nested, qualifiedName + "." + nested.getName().getIdentifier());
        }
    }

    private TypeStructure convertType(AbstractTypeDeclaration decl, String qualifiedName, boolean insideInterface) {
        TypeKind kind = kindOf(decl);
        List<TypeParameterStructure> typeParameters = new ArrayList<>();
        Set<String> typeParameterNames = new HashSet<>();
        List<TypeParameter> declaredTypeParameters = switch (decl) {
            case TypeDeclaration t -> nodes(t.typeParameters(), TypeParameter.class);
            case RecordDeclaration r -> nodes(r.typeParameters(), TypeParameter.class);
            default -> List.of();
        };
        declaredTypeParameters.forEach(tp -> typeParameterNames.add(tp.getName().getIdentifier()));
        typeParameterScopes.push(typeParameterNames);

        Set<String> references = new LinkedHashSet<>();
        typeReferences.put(qualifiedName, references);
        TypeContext context = new TypeContext(decl.getName().getIdentifier(), qualifiedName, references);
        typeContexts.push(context);
        try {
            declaredTypeParameters.forEach(tp -> typeParameters.add(toTypeParameter(tp)));

            TypeStructure superclass = null;
            List<TypeStructure> interfaces = new ArrayList<>();
            List<Type> superInterfaces = switch (decl) {
                case TypeDeclaration t -> nodes(t.superInterfaceTypes(), Type.class);
                case EnumDeclaration e -> nodes(e.superInterfaceTypes(), Type.class);
                case RecordDeclaration r -> nodes(r.superInterfaceTypes(), Type.class);
                default -> List.of();
            };
            if (decl instanceof TypeDeclaration t && t.getSuperclassType() != null) {
                TypeReference ref = toTypeReference(t.getSuperclassType());
                context.superName = ref.getName();
                context.superQualifiedName = ref.getQualifiedName();
                superclass = typeStub(ref, TypeKind.CLASS);
            }
            for (Type type : superInterfaces) {
                interfaces.add(typeStub(toTypeReference(type), TypeKind.INTERFACE));
            }

            List<BodyDeclaration> members = nodes(decl.bodyDeclarations(), BodyDeclaration.class);
            List<RecordComponentStructure> recordComponents = new ArrayList<>();
            if (decl instanceof RecordDeclaration record) {
                JavadocStructure recordDoc = toJavadoc(record.getJavadoc());
                for (SingleVariableDeclaration component : nodes(record.recordComponents(), SingleVariableDeclaration.class)) {
                    String name = component.getName().getIdentifier();
                    context.fieldNames.add(name);
                    recordComponents.add(RecordComponentStructure.builder()
                            .name(name)
                            .type(toTypeReference(component.getType(), component.getExtraDimensions(), component.isVarargs()))
                            .annotations(annotations(component.modifiers()))
                            .javadoc(paramJavadoc(recordDoc, name))
                            .build());
                }
            }
            for (FieldDeclaration field : filter(members, FieldDeclaration.class)) {
                for (VariableDeclarationFragment fragment : nodes(field.fragments(), VariableDeclarationFragment.class)) {
                    context.fieldNames.add(fragment.getName().getIdentifier());
                }
            }

            boolean membersImplicitlyPublic = kind == TypeKind.INTERFACE || kind == TypeKind.ANNOTATION;
            List<FieldStructure> fields = new ArrayList<>();
            List<MethodStructure> methods = new ArrayList<>();
            List<ConstructorStructure> constructors = new ArrayList<>();
            List<TypeStructure> nestedTypes = new ArrayList<>();
            List<MemberUsage> usages = new ArrayList<>();

            for (BodyDeclaration member : members) {
                switch (member) {
                    case FieldDeclaration f -> fields.addAll(convertField(f, membersImplicitlyPublic));
                    case MethodDeclaration m when m.isConstructor() -> {
                        BodyAnalysis analysis = analyze(m);
                        ConstructorStructure ctor = convertConstructor(m, analysis);
                        constructors.add(ctor);
                        usages.add(new MemberUsage(ctor.getName(), ctor.getParameters(), analysis, m));
                    }
                    case MethodDeclaration m -> {
                        BodyAnalysis analysis = analyze(m);
                        MethodStructure method = convertMethod(m, analysis, membersImplicitlyPublic);
                        methods.add(method);
                        usages.add(new MemberUsage(method.getName(), method.getParameters(), analysis, m));
                    }
                    case AnnotationTypeMemberDeclaration a -> methods.add(convertAnnotationMember(a));
                    case AbstractTypeDeclaration nested -> nestedTypes.add(convertType(
                            nested, qualifiedName + "." + nested.getName().getIdentifier(), membersImplicitlyPublic));
                    default -> { } // initializers are not part of the model
                }
            }

            List<EnumConstantStructure> enumConstants = new ArrayList<>();
            if (decl instanceof EnumDeclaration enumDecl) {
                int ordinal = 0;
                for (EnumConstantDeclaration constant : nodes(enumDecl.enumConstants(), EnumConstantDeclaration.class)) {
                    enumConstants.add(convertEnumConstant(constant, ordinal++));
                }
            }

            linkUsages(context, usages, methods, constructors, fields);

            int start = declarationStart(decl);
            int end = endOffset(decl);
            return TypeStructure.builder()
                    .name(decl.getName().getIdentifier())
                    .qualifiedName(qualifiedName)
                    .kind(kind)
                    .modifiers(modifiers(decl.modifiers(), insideInterface))
                    .annotations(annotations(decl.modifiers()))
                    .typeParameters(typeParameters)
                    .superclass(superclass)
                    .interfaces(interfaces)
                    .fields(fields)
                    .methods(methods)
                    .constructors(constructors)
                    .nestedTypes(nestedTypes)
                    .javadoc(toJavadoc(decl.getJavadoc()))
                    .comments(commentsOf(decl))
                    .enumConstants(enumConstants)
                    .usedBy(new ArrayList<>())
                    .recordComponents(recordComponents)
                    .startLine(line(start))
                    .endLine(line(end))
                    .startColumn(column(start))
                    .endColumn(column(end))
                    .build();
        } finally {
            typeContexts.pop();
            typeParameterScopes.pop();
        }
    }

    private static TypeKind kindOf(AbstractTypeDeclaration decl) {
        return switch (decl) {
            case TypeDeclaration t -> t.isInterface() ? TypeKind.INTERFACE : TypeKind.CLASS;
            case EnumDeclaration e -> TypeKind.ENUM;
            case AnnotationTypeDeclaration a -> TypeKind.ANNOTATION;
            case RecordDeclaration r -> TypeKind.RECORD;
            default -> TypeKind.CLASS;
        };
    }

    /** A superclass/interface entry: only name and qualified name are known from the declaration. */
    private static TypeStructure typeStub(TypeReference ref, TypeKind kind) {
        return TypeStructure.builder()
                .name(ref.getName())
                .qualifiedName(ref.getQualifiedName())
                .kind(kind)
                .modifiers(List.of())
                .annotations(List.of())
                .typeParameters(List.of())
                .interfaces(List.of())
                .fields(List.of())
                .methods(List.of())
                .constructors(List.of())
                .nestedTypes(List.of())
                .comments(List.of())
                .enumConstants(List.of())
                .usedBy(List.of())
                .recordComponents(List.of())
                .build();
    }

    // -------------------------------------------------------------- members

    private List<FieldStructure> convertField(FieldDeclaration decl, boolean implicitlyPublic) {
        List<FieldStructure> result = new ArrayList<>();
        List<ModifierStructure> modifiers = modifiers(decl.modifiers(), implicitlyPublic);
        List<AnnotationStructure> annotations = annotations(decl.modifiers());
        JavadocStructure javadoc = toJavadoc(decl.getJavadoc());
        int start = declarationStart(decl);
        int end = endOffset(decl);
        for (VariableDeclarationFragment fragment : nodes(decl.fragments(), VariableDeclarationFragment.class)) {
            result.add(FieldStructure.builder()
                    .name(fragment.getName().getIdentifier())
                    .type(toTypeReference(decl.getType(), fragment.getExtraDimensions(), false))
                    .modifiers(modifiers)
                    .annotations(annotations)
                    .initializer(fragment.getInitializer() == null ? null : text(fragment.getInitializer()))
                    .javadoc(javadoc)
                    .comments(commentsOf(decl))
                    .isEnumConstant(false)
                    .readBy(new ArrayList<>())
                    .writtenBy(new ArrayList<>())
                    .startLine(line(start))
                    .endLine(line(end))
                    .build());
        }
        return result;
    }

    private MethodStructure convertMethod(MethodDeclaration decl, BodyAnalysis analysis, boolean implicitlyPublic) {
        typeParameterScopes.push(typeParameterNames(decl));
        try {
            int start = declarationStart(decl);
            int end = endOffset(decl);
            return MethodStructure.builder()
                    .name(decl.getName().getIdentifier())
                    .typeParameters(typeParameters(decl))
                    .returnType(decl.getReturnType2() == null ? null
                            : toTypeReference(decl.getReturnType2(), decl.getExtraDimensions(), false))
                    .modifiers(modifiers(decl.modifiers(), implicitlyPublic))
                    .annotations(annotations(decl.modifiers()))
                    .parameters(parameters(decl))
                    .thrownExceptions(thrownExceptions(decl))
                    .body(decl.getBody() == null ? null : text(decl.getBody()))
                    .javadoc(toJavadoc(decl.getJavadoc()))
                    .comments(commentsOf(decl))
                    .calledBy(new ArrayList<>())
                    .calls(analysis.methodCalls())
                    .fieldReads(analysis.fieldReads)
                    .fieldWrites(analysis.fieldWrites)
                    .cyclomaticComplexity(analysis.complexity)
                    .lineCount(line(end) - line(start) + 1)
                    .startLine(line(start))
                    .endLine(line(end))
                    .startColumn(column(start))
                    .endColumn(column(end))
                    .build();
        } finally {
            typeParameterScopes.pop();
        }
    }

    private ConstructorStructure convertConstructor(MethodDeclaration decl, BodyAnalysis analysis) {
        typeParameterScopes.push(typeParameterNames(decl));
        try {
            int start = declarationStart(decl);
            return ConstructorStructure.builder()
                    .name(decl.getName().getIdentifier())
                    .typeParameters(typeParameters(decl))
                    .modifiers(modifiers(decl.modifiers(), false))
                    .annotations(annotations(decl.modifiers()))
                    .parameters(parameters(decl))
                    .thrownExceptions(thrownExceptions(decl))
                    .body(decl.getBody() == null ? null : text(decl.getBody()))
                    .javadoc(toJavadoc(decl.getJavadoc()))
                    .comments(commentsOf(decl))
                    .calledBy(new ArrayList<>())
                    .calls(analysis.constructorCalls())
                    .startLine(line(start))
                    .endLine(line(endOffset(decl)))
                    .build();
        } finally {
            typeParameterScopes.pop();
        }
    }

    private MethodStructure convertAnnotationMember(AnnotationTypeMemberDeclaration decl) {
        int start = declarationStart(decl);
        int end = endOffset(decl);
        return MethodStructure.builder()
                .name(decl.getName().getIdentifier())
                .returnType(toTypeReference(decl.getType()))
                .modifiers(modifiers(decl.modifiers(), true))
                .annotations(annotations(decl.modifiers()))
                .parameters(List.of())
                .thrownExceptions(List.of())
                .typeParameters(List.of())
                .javadoc(toJavadoc(decl.getJavadoc()))
                .comments(commentsOf(decl))
                .calledBy(List.of())
                .calls(List.of())
                .fieldReads(List.of())
                .fieldWrites(List.of())
                .cyclomaticComplexity(1)
                .lineCount(line(end) - line(start) + 1)
                .startLine(line(start))
                .endLine(line(end))
                .startColumn(column(start))
                .endColumn(column(end))
                .build();
    }

    private EnumConstantStructure convertEnumConstant(EnumConstantDeclaration decl, int ordinal) {
        List<String> arguments = new ArrayList<>();
        for (Expression argument : nodes(decl.arguments(), Expression.class)) {
            arguments.add(text(argument));
        }
        return EnumConstantStructure.builder()
                .name(decl.getName().getIdentifier())
                .annotations(annotations(decl.modifiers()))
                .arguments(arguments)
                .javadoc(toJavadoc(decl.getJavadoc()))
                .comments(commentsOf(decl))
                .ordinal(ordinal)
                .startLine(line(declarationStart(decl)))
                .endLine(line(endOffset(decl)))
                .build();
    }

    private List<ParameterStructure> parameters(MethodDeclaration decl) {
        List<ParameterStructure> result = new ArrayList<>();
        for (SingleVariableDeclaration param : nodes(decl.parameters(), SingleVariableDeclaration.class)) {
            boolean isFinal = nodes(param.modifiers(), Modifier.class).stream().anyMatch(Modifier::isFinal);
            result.add(ParameterStructure.builder()
                    .name(param.getName().getIdentifier())
                    .type(toTypeReference(param.getType(), param.getExtraDimensions(), param.isVarargs()))
                    .annotations(annotations(param.modifiers()))
                    .isVarArgs(param.isVarargs())
                    .isFinal(isFinal)
                    .usageLocations(parameterUsages(decl, param.getName().getIdentifier()))
                    .build());
        }
        return result;
    }

    /** "line:column" of every plain-name use of a parameter in the method body. */
    private List<String> parameterUsages(MethodDeclaration decl, String name) {
        List<String> locations = new ArrayList<>();
        if (decl.getBody() == null) {
            return locations;
        }
        decl.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.getIdentifier().equals(name) && isVariableUse(node)) {
                    locations.add(line(node.getStartPosition()) + ":" + column(node.getStartPosition()));
                }
                return false;
            }
        });
        return locations;
    }

    private List<TypeReference> thrownExceptions(MethodDeclaration decl) {
        List<TypeReference> result = new ArrayList<>();
        for (Type type : nodes(decl.thrownExceptionTypes(), Type.class)) {
            result.add(toTypeReference(type));
        }
        return result;
    }

    private List<TypeParameterStructure> typeParameters(MethodDeclaration decl) {
        List<TypeParameterStructure> result = new ArrayList<>();
        for (TypeParameter tp : nodes(decl.typeParameters(), TypeParameter.class)) {
            result.add(toTypeParameter(tp));
        }
        return result;
    }

    private static Set<String> typeParameterNames(MethodDeclaration decl) {
        Set<String> names = new HashSet<>();
        for (TypeParameter tp : nodes(decl.typeParameters(), TypeParameter.class)) {
            names.add(tp.getName().getIdentifier());
        }
        return names;
    }

    private TypeParameterStructure toTypeParameter(TypeParameter tp) {
        List<TypeReference> bounds = new ArrayList<>();
        for (Type bound : nodes(tp.typeBounds(), Type.class)) {
            bounds.add(toTypeReference(bound));
        }
        return TypeParameterStructure.builder().name(tp.getName().getIdentifier()).bounds(bounds).build();
    }

    // ---------------------------------------------------- modifiers & annotations

    private List<ModifierStructure> modifiers(List<?> extendedModifiers, boolean implicitlyPublic) {
        List<ModifierStructure> result = new ArrayList<>();
        boolean hasAccessModifier = false;
        for (Modifier modifier : nodes(extendedModifiers, Modifier.class)) {
            ModifierKind kind = modifierKind(modifier.getKeyword().toString());
            if (kind == null) {
                continue;
            }
            hasAccessModifier |= kind == ModifierKind.PUBLIC || kind == ModifierKind.PROTECTED || kind == ModifierKind.PRIVATE;
            result.add(ModifierStructure.builder().kind(kind).position(modifier.getStartPosition()).build());
        }
        if (!hasAccessModifier && !implicitlyPublic) {
            result.add(ModifierStructure.builder().kind(ModifierKind.PACKAGE_PRIVATE).position(-1).build());
        }
        return result;
    }

    private static ModifierKind modifierKind(String keyword) {
        try {
            return ModifierKind.valueOf(keyword.replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null; // e.g. module modifiers such as "transitive"
        }
    }

    private List<AnnotationStructure> annotations(List<?> extendedModifiers) {
        List<AnnotationStructure> result = new ArrayList<>();
        for (Annotation annotation : nodes(extendedModifiers, Annotation.class)) {
            String name = annotation.getTypeName().getFullyQualifiedName();
            Map<String, String> values = new LinkedHashMap<>();
            if (annotation instanceof SingleMemberAnnotation single) {
                values.put("value", text(single.getValue()));
            } else if (annotation instanceof NormalAnnotation normal) {
                for (MemberValuePair pair : nodes(normal.values(), MemberValuePair.class)) {
                    values.put(pair.getName().getIdentifier(), text(pair.getValue()));
                }
            }
            String qualifiedName = resolve(name);
            recordReference(qualifiedName, name);
            result.add(AnnotationStructure.builder()
                    .name(lastSegment(name))
                    .qualifiedName(qualifiedName)
                    .values(values)
                    .position(annotation.getStartPosition())
                    .build());
        }
        return result;
    }

    // ---------------------------------------------------------------- types refs

    private TypeReference toTypeReference(Type type) {
        return toTypeReference(type, 0, false);
    }

    private TypeReference toTypeReference(Type type, int extraDimensions, boolean varargs) {
        int dimensions = extraDimensions + (varargs ? 1 : 0);
        if (type instanceof ArrayType array) {
            type = array.getElementType();
            dimensions += array.getDimensions();
        }
        TypeReference ref = switch (type) {
            case PrimitiveType p -> TypeReference.builder()
                    .name(p.getPrimitiveTypeCode().toString())
                    .qualifiedName(p.getPrimitiveTypeCode().toString())
                    .isPrimitive(true)
                    .build();
            case ParameterizedType p -> {
                TypeReference base = toTypeReference(p.getType());
                List<TypeReference> arguments = new ArrayList<>();
                for (Type argument : nodes(p.typeArguments(), Type.class)) {
                    arguments.add(toTypeReference(argument));
                }
                base.setTypeArguments(arguments);
                yield base;
            }
            case WildcardType w -> TypeReference.builder()
                    .name("?")
                    .isWildcard(true)
                    .wildcardKind(w.getBound() == null ? WildcardKind.UNBOUNDED
                            : w.isUpperBound() ? WildcardKind.EXTENDS : WildcardKind.SUPER)
                    .wildcardBound(w.getBound() == null ? null : toTypeReference(w.getBound()))
                    .build();
            case SimpleType s -> namedType(s.getName().getFullyQualifiedName());
            case QualifiedType q -> namedType(stripTypeArguments(q.toString()));
            case NameQualifiedType n -> namedType(n.getQualifier().getFullyQualifiedName() + "." + n.getName().getIdentifier());
            default -> TypeReference.builder().name(text(type)).build(); // union / intersection types
        };
        if (ref.getTypeArguments() == null) {
            ref.setTypeArguments(new ArrayList<>());
        }
        if (dimensions > 0) {
            ref.setArray(true);
            ref.setArrayDimensions(dimensions);
        }
        return ref;
    }

    private TypeReference namedType(String writtenName) {
        String qualifiedName = isTypeParameter(writtenName) ? null : resolve(writtenName);
        recordReference(qualifiedName, writtenName);
        return TypeReference.builder().name(lastSegment(writtenName)).qualifiedName(qualifiedName).build();
    }

    /**
     * Best-effort qualified name for a type written as {@code name}: file-declared types, single-type
     * imports, already-qualified names and {@code java.lang}. Returns null when it cannot be known
     * without the classpath (same-package or wildcard-imported types).
     */
    private String resolve(String name) {
        if (PRIMITIVES.contains(name)) {
            return name;
        }
        int dot = name.indexOf('.');
        String first = dot < 0 ? name : name.substring(0, dot);
        String rest = dot < 0 ? "" : name.substring(dot);
        String qualified = declaredTypes.get(first);
        if (qualified == null) {
            qualified = importedTypes.get(first);
        }
        if (qualified != null) {
            return qualified + rest;
        }
        if (dot > 0 && Character.isLowerCase(first.charAt(0))) {
            return name;
        }
        if (dot < 0 && JavaLang.contains(name)) {
            return "java.lang." + name;
        }
        return null;
    }

    private boolean isTypeParameter(String name) {
        for (Set<String> scope : typeParameterScopes) {
            if (scope.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** Remembers a type use for {@code usedBy} linking; unresolved names are guessed to be same-package. */
    private void recordReference(String qualifiedName, String writtenName) {
        TypeContext context = typeContexts.peek();
        if (context == null || PRIMITIVES.contains(writtenName) || isTypeParameter(writtenName)) {
            return;
        }
        context.references.add(qualifiedName != null ? qualifiedName : qualify(packageName, writtenName));
    }

    // ---------------------------------------------------------------- javadoc

    private JavadocStructure toJavadoc(Javadoc doc) {
        if (doc == null) {
            return null;
        }
        String description = "";
        List<JavadocTagStructure> tags = new ArrayList<>();
        int position = 0;
        for (TagElement tag : nodes(doc.tags(), TagElement.class)) {
            List<ASTNode> fragments = nodes(tag.fragments(), ASTNode.class);
            if (tag.getTagName() == null) {
                description = fragmentsText(fragments);
                continue;
            }
            String paramName = null;
            int textStart = 0;
            if (TagElement.TAG_PARAM.equals(tag.getTagName()) && !fragments.isEmpty()) {
                if (fragments.get(0) instanceof SimpleName name) {
                    paramName = name.getIdentifier();
                    textStart = 1;
                } else if (fragments.size() >= 3 && fragments.get(0) instanceof TextElement open
                        && open.getText().trim().equals("<") && fragments.get(1) instanceof SimpleName name) {
                    paramName = "<" + name.getIdentifier() + ">";
                    textStart = 3;
                }
            }
            tags.add(JavadocTagStructure.builder()
                    .name(tag.getTagName())
                    .text(fragmentsText(fragments.subList(textStart, fragments.size())))
                    .paramName(paramName)
                    .position(position++)
                    .build());
        }
        return JavadocStructure.builder()
                .description(description)
                .tags(tags)
                .startLine(line(doc.getStartPosition()))
                .endLine(line(endOffset(doc)))
                .build();
    }

    /** Joins javadoc fragments, keeping line breaks and rebuilding inline tags such as {@code {@link X}}. */
    private String fragmentsText(List<ASTNode> fragments) {
        StringBuilder text = new StringBuilder();
        int previousLine = -1;
        for (ASTNode fragment : fragments) {
            int fragmentLine = line(fragment.getStartPosition());
            if (previousLine != -1 && fragmentLine > previousLine) {
                text.append('\n');
            }
            if (fragment instanceof TextElement element) {
                text.append(element.getText());
            } else if (fragment instanceof TagElement inline) {
                String inner = fragmentsText(nodes(inline.fragments(), ASTNode.class));
                text.append('{').append(inline.getTagName() == null ? "" : inline.getTagName());
                if (!inner.isEmpty()) {
                    text.append(' ').append(inner);
                }
                text.append('}');
            } else {
                text.append(text(fragment));
            }
            previousLine = line(endOffset(fragment));
        }
        return text.toString().strip();
    }

    /** The {@code @param} description of a record component, as its own javadoc. */
    private static JavadocStructure paramJavadoc(JavadocStructure recordDoc, String name) {
        if (recordDoc == null) {
            return null;
        }
        return recordDoc.getTags().stream()
                .filter(tag -> name.equals(tag.getParamName()))
                .findFirst()
                .map(tag -> JavadocStructure.builder()
                        .description(tag.getText())
                        .tags(List.of())
                        .startLine(recordDoc.getStartLine())
                        .endLine(recordDoc.getEndLine())
                        .build())
                .orElse(null);
    }

    // --------------------------------------------------------------- comments

    /**
     * Assigns every comment (except declarations' own javadoc) to the innermost member or type whose
     * extended range (including leading/trailing comments) contains it; the rest are file comments.
     */
    private void assignComments() {
        List<ASTNode> owners = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof AbstractTypeDeclaration || node instanceof MethodDeclaration
                        || node instanceof FieldDeclaration || node instanceof EnumConstantDeclaration
                        || node instanceof AnnotationTypeMemberDeclaration) {
                    owners.add(node);
                }
            }

            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(TypeDeclarationStatement node) {
                return false;
            }
        });

        for (Comment comment : nodes(unit.getCommentList(), Comment.class)) {
            if (comment.isDocComment() && comment.getParent() instanceof BodyDeclaration) {
                continue;
            }
            CommentStructure structure = toComment(comment);
            if (comment.getParent() instanceof PackageDeclaration) {
                fileComments.add(structure);
                continue;
            }
            ASTNode owner = null;
            int ownerLength = Integer.MAX_VALUE;
            for (ASTNode candidate : owners) {
                int start = unit.getExtendedStartPosition(candidate);
                int length = unit.getExtendedLength(candidate);
                if (comment.getStartPosition() >= start && comment.getStartPosition() < start + length && length < ownerLength) {
                    owner = candidate;
                    ownerLength = length;
                }
            }
            if (owner == null) {
                fileComments.add(structure);
            } else {
                commentsByOwner.computeIfAbsent(owner, k -> new ArrayList<>()).add(structure);
            }
        }
    }

    private CommentStructure toComment(Comment comment) {
        String raw = text(comment);
        CommentKind kind;
        String content;
        if (comment.isLineComment()) {
            kind = CommentKind.LINE;
            content = raw.substring(2).strip();
        } else {
            kind = comment.isDocComment() ? CommentKind.JAVADOC : CommentKind.BLOCK;
            String inner = raw.substring(comment.isDocComment() ? 3 : 2, Math.max(raw.length() - 2, 2));
            content = inner.lines().map(l -> l.strip().replaceFirst("^\\*\\s?", "")).reduce((a, b) -> a + "\n" + b).orElse("").strip();
        }
        int end = endOffset(comment);
        return CommentStructure.builder()
                .kind(kind)
                .content(content)
                .startLine(line(comment.getStartPosition()))
                .endLine(line(end))
                .startColumn(column(comment.getStartPosition()))
                .endColumn(column(end))
                .build();
    }

    private List<CommentStructure> commentsOf(ASTNode owner) {
        return commentsByOwner.getOrDefault(owner, new ArrayList<>());
    }

    // ------------------------------------------------------- body analysis

    private BodyAnalysis analyze(MethodDeclaration decl) {
        TypeContext context = typeContexts.element();
        BodyAnalysis analysis = new BodyAnalysis();
        Block body = decl.getBody();
        if (body == null) {
            return analysis;
        }

        // Names that shadow fields anywhere in the method; such fields only count when accessed via "this."
        Set<String> localNames = new HashSet<>();
        nodes(decl.parameters(), SingleVariableDeclaration.class).forEach(p -> localNames.add(p.getName().getIdentifier()));
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                localNames.add(node.getName().getIdentifier());
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                localNames.add(node.getName().getIdentifier());
                return true;
            }
        });

        Set<ASTNode> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        body.accept(new ASTVisitor() {
            // Members of local and anonymous classes belong to those classes, not to this method.
            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(TypeDeclarationStatement node) {
                return false;
            }

            @Override
            public boolean visit(SimpleType node) {
                toTypeReference(node); // records the type use
                return false;
            }

            @Override
            public boolean visit(ParameterizedType node) {
                toTypeReference(node);
                return false;
            }

            @Override
            public boolean visit(IfStatement node) { analysis.complexity++; return true; }

            @Override
            public boolean visit(ForStatement node) { analysis.complexity++; return true; }

            @Override
            public boolean visit(EnhancedForStatement node) { analysis.complexity++; return true; }

            @Override
            public boolean visit(WhileStatement node) { analysis.complexity++; return true; }

            @Override
            public boolean visit(DoStatement node) { analysis.complexity++; return true; }

            @Override
            public boolean visit(CatchClause node) { analysis.complexity++; return true; }

            @Override
            public boolean visit(ConditionalExpression node) { analysis.complexity++; return true; }

            @Override
            public boolean visit(SwitchCase node) {
                if (!node.isDefault()) {
                    analysis.complexity += Math.max(1, node.expressions().size());
                }
                return true;
            }

            @Override
            public boolean visit(InfixExpression node) {
                if (node.getOperator() == InfixExpression.Operator.CONDITIONAL_AND
                        || node.getOperator() == InfixExpression.Operator.CONDITIONAL_OR) {
                    analysis.complexity += 1 + node.extendedOperands().size();
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                String declaring = null;
                String qualifiedDeclaring = null;
                Expression target = node.getExpression();
                if (target == null || target instanceof ThisExpression) {
                    declaring = context.simpleName;
                    qualifiedDeclaring = context.qualifiedName;
                } else if (target instanceof Name name && looksLikeTypeName(name)) {
                    String written = name.getFullyQualifiedName();
                    declaring = lastSegment(written);
                    qualifiedDeclaring = resolve(written);
                    recordReference(qualifiedDeclaring, written);
                    handled.add(name);
                }
                analysis.calls.add(new CallSite(node.getName().getIdentifier(), declaring, qualifiedDeclaring,
                        node.arguments().size(), node.getStartPosition()));
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                analysis.calls.add(new CallSite(node.getName().getIdentifier(), context.superName,
                        context.superQualifiedName, node.arguments().size(), node.getStartPosition()));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                TypeReference type = toTypeReference(node.getType());
                analysis.constructorCalls.add(new CallSite(type.getName(), type.getName(), type.getQualifiedName(),
                        node.arguments().size(), node.getStartPosition()));
                for (Expression argument : nodes(node.arguments(), Expression.class)) {
                    argument.accept(this);
                }
                if (node.getExpression() != null) {
                    node.getExpression().accept(this);
                }
                return false; // type already handled; anonymous body is skipped
            }

            @Override
            public boolean visit(ConstructorInvocation node) {
                analysis.constructorCalls.add(new CallSite(context.simpleName, context.simpleName, context.qualifiedName,
                        node.arguments().size(), node.getStartPosition()));
                return true;
            }

            @Override
            public boolean visit(SuperConstructorInvocation node) {
                if (context.superName != null) {
                    analysis.constructorCalls.add(new CallSite(context.superName, context.superName,
                            context.superQualifiedName, node.arguments().size(), node.getStartPosition()));
                }
                return true;
            }

            @Override
            public boolean visit(Assignment node) {
                SimpleName field = ownFieldName(node.getLeftHandSide());
                if (field != null) {
                    handled.add(field);
                    analysis.fieldWrites.add(fieldReference(field, false));
                    if (node.getOperator() != Assignment.Operator.ASSIGN) {
                        analysis.fieldReads.add(fieldReference(field, true));
                    }
                }
                return true;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                if (node.getOperator() == PrefixExpression.Operator.INCREMENT
                        || node.getOperator() == PrefixExpression.Operator.DECREMENT) {
                    readAndWrite(node.getOperand());
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                readAndWrite(node.getOperand());
                return true;
            }

            private void readAndWrite(Expression operand) {
                SimpleName field = ownFieldName(operand);
                if (field != null) {
                    handled.add(field);
                    analysis.fieldReads.add(fieldReference(field, true));
                    analysis.fieldWrites.add(fieldReference(field, false));
                }
            }

            @Override
            public boolean visit(FieldAccess node) {
                if (node.getExpression() instanceof ThisExpression && !handled.contains(node.getName())
                        && context.fieldNames.contains(node.getName().getIdentifier())) {
                    handled.add(node.getName());
                    analysis.fieldReads.add(fieldReference(node.getName(), true));
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                String name = node.getIdentifier();
                if (!handled.contains(node) && context.fieldNames.contains(name) && !localNames.contains(name)
                        && isVariableUse(node)) {
                    analysis.fieldReads.add(fieldReference(node, true));
                }
                return false;
            }

            /** The written field's name node when {@code expression} is {@code field} or {@code this.field}. */
            private SimpleName ownFieldName(Expression expression) {
                if (expression instanceof FieldAccess access && access.getExpression() instanceof ThisExpression
                        && context.fieldNames.contains(access.getName().getIdentifier())) {
                    return access.getName();
                }
                if (expression instanceof SimpleName name && context.fieldNames.contains(name.getIdentifier())
                        && !localNames.contains(name.getIdentifier())) {
                    return name;
                }
                return null;
            }

            private FieldReference fieldReference(SimpleName name, boolean read) {
                return FieldReference.builder()
                        .fieldName(name.getIdentifier())
                        .declaringTypeName(context.simpleName)
                        .qualifiedDeclaringTypeName(context.qualifiedName)
                        .isRead(read)
                        .line(line(name.getStartPosition()))
                        .column(column(name.getStartPosition()))
                        .build();
            }
        });
        return analysis;
    }

    /** True when the name is used as a variable, not as a declaration, member name, label or type. */
    private static boolean isVariableUse(SimpleName node) {
        StructuralPropertyDescriptor location = node.getLocationInParent();
        return location != MethodInvocation.NAME_PROPERTY
                && location != SuperMethodInvocation.NAME_PROPERTY
                && location != QualifiedName.NAME_PROPERTY
                && location != FieldAccess.NAME_PROPERTY
                && location != SuperFieldAccess.NAME_PROPERTY
                && location != VariableDeclarationFragment.NAME_PROPERTY
                && location != SingleVariableDeclaration.NAME_PROPERTY
                && location != SimpleType.NAME_PROPERTY
                && location != QualifiedType.NAME_PROPERTY
                && location != NameQualifiedType.NAME_PROPERTY
                && location != LabeledStatement.LABEL_PROPERTY
                && location != BreakStatement.LABEL_PROPERTY
                && location != ContinueStatement.LABEL_PROPERTY
                && location != MemberValuePair.NAME_PROPERTY
                && location != ExpressionMethodReference.NAME_PROPERTY
                && location != TypeMethodReference.NAME_PROPERTY
                && location != SuperMethodReference.NAME_PROPERTY;
    }

    /** {@code Foo.bar()} / {@code java.util.List.of()} receivers, as opposed to {@code foo.bar()}. */
    private boolean looksLikeTypeName(Name name) {
        String last = lastSegment(name.getFullyQualifiedName());
        return !last.isEmpty() && Character.isUpperCase(last.charAt(0)) && resolve(name.getFullyQualifiedName()) != null;
    }

    /** Fills calledBy / readBy / writtenBy from the calls and field accesses found inside this type. */
    private void linkUsages(TypeContext context, List<MemberUsage> usages, List<MethodStructure> methods,
                            List<ConstructorStructure> constructors, List<FieldStructure> fields) {
        for (MemberUsage caller : usages) {
            for (CallSite call : caller.analysis().calls) {
                if (!context.qualifiedName.equals(call.qualifiedDeclaring())) {
                    continue;
                }
                for (MethodStructure target : methods) {
                    if (target.getName().equals(call.name()) && accepts(target.getParameters(), call.argumentCount())) {
                        target.getCalledBy().add(callerReference(context, caller, call));
                    }
                }
            }
            for (CallSite call : caller.analysis().constructorCalls) {
                if (!context.qualifiedName.equals(call.qualifiedDeclaring())) {
                    continue;
                }
                for (ConstructorStructure target : constructors) {
                    if (accepts(target.getParameters(), call.argumentCount())) {
                        target.getCalledBy().add(callerReference(context, caller, call));
                    }
                }
            }
            for (FieldReference access : caller.analysis().fieldReads) {
                linkField(fields, access, callerReference(context, caller, access));
            }
            for (FieldReference access : caller.analysis().fieldWrites) {
                linkField(fields, access, callerReference(context, caller, access));
            }
        }
    }

    private static void linkField(List<FieldStructure> fields, FieldReference access, MethodReference caller) {
        for (FieldStructure field : fields) {
            if (field.getName().equals(access.getFieldName())) {
                (access.isRead() ? field.getReadBy() : field.getWrittenBy()).add(caller);
            }
        }
    }

    private static boolean accepts(List<ParameterStructure> parameters, int argumentCount) {
        boolean varargs = !parameters.isEmpty() && parameters.getLast().isVarArgs();
        return varargs ? argumentCount >= parameters.size() - 1 : argumentCount == parameters.size();
    }

    private MethodReference callerReference(TypeContext context, MemberUsage caller, CallSite call) {
        return callerReference(context, caller, line(call.offset()), column(call.offset()));
    }

    private MethodReference callerReference(TypeContext context, MemberUsage caller, FieldReference access) {
        return callerReference(context, caller, access.getLine(), access.getColumn());
    }

    private static MethodReference callerReference(TypeContext context, MemberUsage caller, int line, int column) {
        List<TypeReference> parameterTypes = new ArrayList<>();
        caller.parameters().forEach(p -> parameterTypes.add(p.getType()));
        return MethodReference.builder()
                .methodName(caller.name())
                .declaringTypeName(context.simpleName)
                .qualifiedDeclaringTypeName(context.qualifiedName)
                .parameterTypes(parameterTypes)
                .line(line)
                .column(column)
                .build();
    }

    // ---------------------------------------------------------------- helpers

    private int line(int offset) {
        return unit.getLineNumber(offset);
    }

    /** 1-based column. */
    private int column(int offset) {
        return unit.getColumnNumber(offset) + 1;
    }

    private static int endOffset(ASTNode node) {
        return node.getStartPosition() + Math.max(node.getLength() - 1, 0);
    }

    /** Start of a declaration, skipping its javadoc so positions point at the declaration itself. */
    private int declarationStart(BodyDeclaration decl) {
        Javadoc doc = decl.getJavadoc();
        if (doc == null) {
            return decl.getStartPosition();
        }
        int position = doc.getStartPosition() + doc.getLength();
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
        return position;
    }

    private String text(ASTNode node) {
        return source.substring(node.getStartPosition(), node.getStartPosition() + node.getLength());
    }

    private static String qualify(String packageName, String name) {
        return packageName.isEmpty() ? name : packageName + "." + name;
    }

    private static String lastSegment(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static String stripTypeArguments(String name) {
        return name.replaceAll("<[^<>]*>", "").replaceAll("<[^<>]*>", "").replace(" ", "");
    }

    /** JDT exposes raw lists; this narrows them to the element type the caller expects. */
    private static <T> List<T> nodes(List<?> list, Class<T> type) {
        List<T> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (type.isInstance(element)) {
                result.add(type.cast(element));
            }
        }
        return result;
    }

    private static <T> List<T> filter(List<?> list, Class<T> type) {
        return nodes(list, type);
    }

    // ------------------------------------------------------------ state types

    private static final class TypeContext {
        final String simpleName;
        final String qualifiedName;
        final Set<String> references;
        final Set<String> fieldNames = new HashSet<>();
        String superName;
        String superQualifiedName;

        TypeContext(String simpleName, String qualifiedName, Set<String> references) {
            this.simpleName = simpleName;
            this.qualifiedName = qualifiedName;
            this.references = references;
        }
    }

    private record CallSite(String name, String declaring, String qualifiedDeclaring, int argumentCount, int offset) {
    }

    private record MemberUsage(String name, List<ParameterStructure> parameters, BodyAnalysis analysis,
                               MethodDeclaration declaration) {
    }

    private final class BodyAnalysis {
        final List<CallSite> calls = new ArrayList<>();
        final List<CallSite> constructorCalls = new ArrayList<>();
        final List<FieldReference> fieldReads = new ArrayList<>();
        final List<FieldReference> fieldWrites = new ArrayList<>();
        int complexity = 1;

        List<MethodReference> methodCalls() {
            List<MethodReference> result = new ArrayList<>();
            for (CallSite call : calls) {
                result.add(MethodReference.builder()
                        .methodName(call.name())
                        .declaringTypeName(call.declaring())
                        .qualifiedDeclaringTypeName(call.qualifiedDeclaring())
                        .parameterTypes(List.of())
                        .line(line(call.offset()))
                        .column(column(call.offset()))
                        .build());
            }
            return result;
        }

        List<ConstructorReference> constructorCalls() {
            List<ConstructorReference> result = new ArrayList<>();
            for (CallSite call : constructorCalls) {
                result.add(ConstructorReference.builder()
                        .typeName(call.declaring())
                        .qualifiedTypeName(call.qualifiedDeclaring())
                        .parameterTypes(List.of())
                        .line(line(call.offset()))
                        .column(column(call.offset()))
                        .build());
            }
            return result;
        }
    }

    /** Lazily checks whether a simple name is a public {@code java.lang} type on the running JDK. */
    private static final class JavaLang {
        private static final Map<String, Boolean> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

        static boolean contains(String simpleName) {
            return CACHE.computeIfAbsent(simpleName, name -> {
                try {
                    Class.forName("java.lang." + name, false, JavaLang.class.getClassLoader());
                    return true;
                } catch (ClassNotFoundException | LinkageError e) {
                    return false;
                }
            });
        }
    }
}
