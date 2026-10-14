package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.model.*;
import io.joshuasalcedo.pretty.spring.model.*;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.joshuasalcedo.pretty.spring.Annotations.*;

/**
 * Works out, from parsed sources alone, what a Spring application context would contain: beans,
 * how their dependencies resolve, web endpoints, configuration properties. Then checks the wiring
 * for what Spring would reject at startup (missing, ambiguous or circular dependencies, duplicate
 * names) and for common mistakes.
 * <p>
 * Static analysis has limits: auto-configured beans, {@code @Import}ed library configurations,
 * conditions and profiles are not evaluated. Dependencies on types outside the project are
 * therefore reported as information, not errors.
 */
public final class SpringAnalyzer {

    static final String SPRING = "org.springframework.";
    private static final String STEREOTYPE = "org.springframework.stereotype.";
    private static final String CONTEXT = "org.springframework.context.annotation.";
    private static final String BEANS = "org.springframework.beans.factory.annotation.";
    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final String BOOT = "org.springframework.boot.";
    private static final String BOOT_PROPERTIES = "org.springframework.boot.context.properties.";

    private static final Map<String, BeanKind> STEREOTYPES = new LinkedHashMap<>();

    static {
        STEREOTYPES.put(WEB + "RestController", BeanKind.REST_CONTROLLER);
        STEREOTYPES.put(STEREOTYPE + "Controller", BeanKind.CONTROLLER);
        STEREOTYPES.put(WEB + "RestControllerAdvice", BeanKind.CONTROLLER_ADVICE);
        STEREOTYPES.put(WEB + "ControllerAdvice", BeanKind.CONTROLLER_ADVICE);
        STEREOTYPES.put(STEREOTYPE + "Service", BeanKind.SERVICE);
        STEREOTYPES.put(STEREOTYPE + "Repository", BeanKind.REPOSITORY);
        STEREOTYPES.put(BOOT + "autoconfigure.SpringBootApplication", BeanKind.CONFIGURATION);
        STEREOTYPES.put(BOOT + "SpringBootConfiguration", BeanKind.CONFIGURATION);
        STEREOTYPES.put(BOOT + "test.context.TestConfiguration", BeanKind.CONFIGURATION);
        STEREOTYPES.put(BOOT + "autoconfigure.AutoConfiguration", BeanKind.CONFIGURATION);
        STEREOTYPES.put(CONTEXT + "Configuration", BeanKind.CONFIGURATION);
        STEREOTYPES.put(STEREOTYPE + "Component", BeanKind.COMPONENT);
        STEREOTYPES.put("jakarta.inject.Named", BeanKind.COMPONENT);
        STEREOTYPES.put("javax.inject.Named", BeanKind.COMPONENT);
    }

    private static final Set<String> DATA_REPOSITORIES = Set.of(
            "Repository", "CrudRepository", "ListCrudRepository", "PagingAndSortingRepository",
            "ListPagingAndSortingRepository", "JpaRepository", "MongoRepository", "ReactiveCrudRepository",
            "ReactiveSortingRepository", "R2dbcRepository", "ElasticsearchRepository", "Neo4jRepository",
            "CassandraRepository", "KeyValueRepository", "RevisionRepository");
    private static final Set<String> MULTI_CONTAINERS = Set.of(
            "List", "Set", "Collection", "Iterable", "SortedSet", "Map", "ObjectProvider", "Stream");
    private static final Set<String> OPTIONAL_CONTAINERS = Set.of("Optional", "ObjectFactory", "Provider", "Supplier");
    private static final Map<String, String> MAPPINGS = Map.of(
            WEB + "GetMapping", "GET", WEB + "PostMapping", "POST", WEB + "PutMapping", "PUT",
            WEB + "DeleteMapping", "DELETE", WEB + "PatchMapping", "PATCH", WEB + "RequestMapping", "");
    private static final Set<String> SIMPLE_VALUE_TYPES = Set.of(
            "String", "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character", "BigDecimal",
            "BigInteger", "Duration", "Period", "DataSize", "Charset", "Locale", "File", "Path", "URI", "URL",
            "Class", "Pattern", "InetAddress", "UUID", "Instant", "LocalDate", "LocalDateTime", "LocalTime", "Resource");

    /** A type plus where it was found. */
    private record Located(TypeStructure type, String packageName) {
    }

    private final Map<String, Located> types = new LinkedHashMap<>();
    private final Map<String, List<String>> bySimpleName = new HashMap<>();
    private final Map<String, BeanKind> stereotypes = new LinkedHashMap<>(STEREOTYPES);
    private final List<BeanDefinition> beans = new ArrayList<>();
    private final List<BeanIssue> issues = new ArrayList<>();
    private final List<PropertyUsage> propertyUsages = new ArrayList<>();
    private final Set<String> fieldInjectionReported = new HashSet<>();

    private SpringAnalyzer() {
    }

    /**
     * @param files parsed sources, before any visibility filtering (private fields and constructors matter)
     * @return the Spring structure, or empty when the sources do not use Spring
     */
    public static Optional<SpringStructure> analyze(Collection<JavaFileStructure> files) {
        return new SpringAnalyzer().run(files);
    }

    private Optional<SpringStructure> run(Collection<JavaFileStructure> files) {
        files.forEach(file -> file.getTypes().forEach(type -> index(type, file.getPackageName())));
        if (types.values().stream().noneMatch(l -> usesSpring(l.type()))) {
            return Optional.empty();
        }
        registerMetaStereotypes();

        List<SpringApplicationStructure> applications = applications();
        Set<String> scanPackages = new LinkedHashSet<>();
        applications.forEach(a -> scanPackages.addAll(a.getScanPackages()));
        scanPackages.addAll(componentScans());

        List<ConfigurationPropertiesStructure> properties = configurationProperties();
        declareBeans(properties);
        checkComponentScan(scanPackages);
        resolveDependencies();
        checkDuplicates();
        checkCycles();
        properties.stream().filter(p -> !p.isRegistered()).forEach(p -> issues.add(issue(BeanIssue.Severity.WARNING,
                BeanIssue.Kind.UNREGISTERED_CONFIGURATION_PROPERTIES, null, p.getTypeName(),
                simple(p.getTypeName()) + " has @ConfigurationProperties(\"" + p.getPrefix() + "\") but is never registered: "
                        + "add @EnableConfigurationProperties(" + simple(p.getTypeName()) + ".class), @ConfigurationPropertiesScan or @Component")));

        issues.sort(Comparator.comparing(BeanIssue::getSeverity).thenComparing(i -> String.valueOf(i.getBeanName())));
        return Optional.of(SpringStructure.builder()
                .applications(applications)
                .scanPackages(new ArrayList<>(scanPackages))
                .beans(beans.stream().sorted(Comparator.comparing(BeanDefinition::getName)).toList())
                .endpoints(endpoints())
                .configurationProperties(properties)
                .propertyUsages(propertyUsages)
                .entities(entities())
                .issues(issues)
                .build());
    }

    // ------------------------------------------------------------------ index

    private void index(TypeStructure type, String packageName) {
        types.put(type.getQualifiedName(), new Located(type, packageName));
        bySimpleName.computeIfAbsent(type.getName(), k -> new ArrayList<>()).add(type.getQualifiedName());
        type.getNestedTypes().forEach(nested -> index(nested, packageName));
    }

    private boolean usesSpring(TypeStructure type) {
        Stream<AnnotationStructure> all = Stream.of(
                type.getAnnotations().stream(),
                type.getFields().stream().flatMap(f -> f.getAnnotations().stream()),
                type.getMethods().stream().flatMap(m -> m.getAnnotations().stream()),
                type.getConstructors().stream().flatMap(c -> c.getAnnotations().stream())).flatMap(s -> s);
        return all.anyMatch(a -> a.getQualifiedName() != null ? a.getQualifiedName().startsWith(SPRING)
                : STEREOTYPES.keySet().stream().anyMatch(s -> s.startsWith(SPRING) && is(a, s)))
                || isDataRepository(type, new HashSet<>());
    }

    /** Project annotations meta-annotated with a stereotype (e.g. a custom @UseCase carrying @Service). */
    private void registerMetaStereotypes() {
        boolean added = true;
        while (added) {
            added = false;
            for (Located located : types.values()) {
                TypeStructure type = located.type();
                if (type.getKind() == TypeKind.ANNOTATION && !stereotypes.containsKey(type.getQualifiedName())) {
                    Optional<BeanKind> kind = stereotypeOf(type.getAnnotations());
                    if (kind.isPresent()) {
                        stereotypes.put(type.getQualifiedName(), kind.get());
                        added = true;
                    }
                }
            }
        }
    }

    private Optional<BeanKind> stereotypeOf(List<AnnotationStructure> annotations) {
        for (Map.Entry<String, BeanKind> stereotype : stereotypes.entrySet()) {
            if (has(annotations, stereotype.getKey())) {
                return Optional.of(stereotype.getValue());
            }
        }
        return Optional.empty();
    }

    private Optional<AnnotationStructure> stereotypeAnnotation(List<AnnotationStructure> annotations) {
        return find(annotations, stereotypes.keySet().toArray(String[]::new));
    }

    // ------------------------------------------------------------ applications

    private List<SpringApplicationStructure> applications() {
        List<SpringApplicationStructure> result = new ArrayList<>();
        for (Located located : types.values()) {
            find(located.type().getAnnotations(), BOOT + "autoconfigure.SpringBootApplication").ifPresent(annotation -> {
                List<String> packages = new ArrayList<>(strings(annotation, "scanBasePackages"));
                classes(annotation, "scanBasePackageClasses").forEach(c -> packageOfClass(c, located).ifPresent(packages::add));
                if (packages.isEmpty()) {
                    packages.add(located.packageName());
                }
                result.add(SpringApplicationStructure.builder()
                        .typeName(located.type().getQualifiedName())
                        .hasMainMethod(located.type().getMethods().stream().anyMatch(m -> m.getName().equals("main")
                                && hasModifier(m.getModifiers(), ModifierKind.STATIC)))
                        .scanPackages(packages)
                        .excludedAutoConfigurations(classes(annotation, "exclude"))
                        .build());
            });
        }
        return result;
    }

    private Set<String> componentScans() {
        Set<String> packages = new LinkedHashSet<>();
        for (Located located : types.values()) {
            find(located.type().getAnnotations(), CONTEXT + "ComponentScan").ifPresent(annotation -> {
                List<String> declared = new ArrayList<>(strings(annotation, "basePackages", "value"));
                classes(annotation, "basePackageClasses").forEach(c -> packageOfClass(c, located).ifPresent(declared::add));
                packages.addAll(declared.isEmpty() ? List.of(located.packageName()) : declared);
            });
        }
        return packages;
    }

    private Optional<String> packageOfClass(String written, Located context) {
        return resolveTypeName(written, context).map(types::get).map(Located::packageName)
                .or(() -> written.contains(".") ? Optional.of(written.substring(0, written.lastIndexOf('.'))) : Optional.empty());
    }

    // ------------------------------------------------------------------ beans

    private void declareBeans(List<ConfigurationPropertiesStructure> properties) {
        Set<String> enabledProperties = new HashSet<>();
        List<String> propertyScans = new ArrayList<>();
        for (Located located : types.values()) {
            for (AnnotationStructure annotation : located.type().getAnnotations()) {
                if (is(annotation, BOOT_PROPERTIES + "EnableConfigurationProperties")) {
                    classes(annotation, "value").forEach(c -> resolveTypeName(c, located).ifPresent(enabledProperties::add));
                }
                if (is(annotation, BOOT_PROPERTIES + "ConfigurationPropertiesScan")) {
                    List<String> declared = strings(annotation, "value", "basePackages");
                    propertyScans.addAll(declared.isEmpty() ? List.of(located.packageName()) : declared);
                }
            }
        }

        for (Located located : types.values()) {
            TypeStructure type = located.type();
            Optional<BeanKind> stereotype = type.getKind() == TypeKind.ANNOTATION ? Optional.empty() : stereotypeOf(type.getAnnotations());
            if (type.getKind() == TypeKind.INTERFACE && isDataRepository(type, new HashSet<>())
                    && !has(type.getAnnotations(), "org.springframework.data.repository.NoRepositoryBean")) {
                beans.add(typeBean(located, BeanKind.DATA_REPOSITORY, null));
            } else if (stereotype.isPresent() && type.getKind() != TypeKind.INTERFACE) {
                String explicit = stereotypeAnnotation(type.getAnnotations()).flatMap(a -> strings(a, "value").stream().findFirst()).orElse(null);
                beans.add(typeBean(located, stereotype.get(), explicit));
            }
            if (stereotype.isPresent() || type.getKind() == TypeKind.CLASS) {
                boolean full = stereotype.orElse(null) == BeanKind.CONFIGURATION;
                boolean beanClass = stereotype.isPresent();
                for (MethodStructure method : type.getMethods()) {
                    find(method.getAnnotations(), CONTEXT + "Bean").ifPresent(annotation -> {
                        if (beanClass) {
                            beans.add(methodBean(located, method, annotation));
                            if (!full) {
                                issues.add(issue(BeanIssue.Severity.INFO, BeanIssue.Kind.BEAN_METHOD_LITE_MODE, method.getName(),
                                        type.getQualifiedName(), "@Bean " + method.getName() + "() is in " + type.getName()
                                                + ", which is not a @Configuration class: calls between its @Bean methods create new instances"));
                            }
                        }
                    });
                }
            }
        }

        for (ConfigurationPropertiesStructure property : properties) {
            boolean component = beans.stream().anyMatch(b -> b.getTypeName().equals(property.getTypeName()) && b.getDeclaringMember() == null);
            boolean fromBeanMethod = beans.stream().anyMatch(b -> b.getDeclaringMember() != null && b.getTypeName().equals(property.getTypeName()));
            String packageName = Optional.ofNullable(types.get(property.getTypeName())).map(Located::packageName).orElse("");
            boolean scanned = propertyScans.stream().anyMatch(p -> inPackage(packageName, p));
            if (enabledProperties.contains(property.getTypeName()) || scanned) {
                Located located = types.get(property.getTypeName());
                if (located != null && !component) {
                    beans.add(typeBean(located, BeanKind.CONFIGURATION_PROPERTIES, property.getPrefix() + "-" + property.getTypeName()));
                }
            }
            property.setRegistered(component || fromBeanMethod || enabledProperties.contains(property.getTypeName()) || scanned);
        }
    }

    private boolean isDataRepository(TypeStructure type, Set<String> visited) {
        if (!visited.add(type.getQualifiedName())) {
            return false;
        }
        for (TypeStructure parent : type.getInterfaces()) {
            boolean spring = parent.getQualifiedName() == null || parent.getQualifiedName().startsWith("org.springframework.data.");
            if (spring && DATA_REPOSITORIES.contains(parent.getName())) {
                return true;
            }
            Located local = lookup(parent.getName(), parent.getQualifiedName());
            if (local != null && isDataRepository(local.type(), visited)) {
                return true;
            }
        }
        return false;
    }

    private BeanDefinition typeBean(Located located, BeanKind kind, String explicitName) {
        TypeStructure type = located.type();
        BeanDefinition bean = BeanDefinition.builder()
                .name(explicitName != null && !explicitName.isBlank() ? explicitName : defaultBeanName(type))
                .aliases(List.of())
                .kind(kind)
                .typeName(type.getQualifiedName())
                .assignableTo(new ArrayList<>(supertypes(type)))
                .declaringType(type.getQualifiedName())
                .injectedInto(new ArrayList<>())
                .inComponentScan(true)
                .line(type.getStartLine())
                .build();
        applyBeanAnnotations(bean, type.getAnnotations(), List.of());
        bean.setDependencies(kind == BeanKind.DATA_REPOSITORY ? new ArrayList<>() : injectionPoints(located));
        return bean;
    }

    private BeanDefinition methodBean(Located configuration, MethodStructure method, AnnotationStructure annotation) {
        List<String> names = strings(annotation, "name", "value");
        TypeReference returned = method.getReturnType();
        Located returnedType = returned == null ? null : lookup(returned.getName(), returned.getQualifiedName());
        String typeName = returnedType != null ? returnedType.type().getQualifiedName()
                : returned == null ? "void" : Optional.ofNullable(returned.getQualifiedName()).orElse(returned.getName());
        Set<String> assignable = returnedType != null ? supertypes(returnedType.type()) : new LinkedHashSet<>(List.of(typeName));

        BeanDefinition bean = BeanDefinition.builder()
                .name(names.isEmpty() ? method.getName() : names.getFirst())
                .aliases(names.size() > 1 ? names.subList(1, names.size()) : List.of())
                .kind(BeanKind.BEAN_METHOD)
                .typeName(typeName)
                .assignableTo(new ArrayList<>(assignable))
                .declaringType(configuration.type().getQualifiedName())
                .declaringMember(method.getName())
                .injectedInto(new ArrayList<>())
                .inComponentScan(true)
                .line(method.getStartLine())
                .build();
        applyBeanAnnotations(bean, method.getAnnotations(), configuration.type().getAnnotations());

        List<InjectionPoint> dependencies = new ArrayList<>();
        for (ParameterStructure parameter : method.getParameters()) {
            injection(InjectionPoint.Kind.BEAN_METHOD_PARAMETER, parameter.getName(), parameter.getType(), parameter.getAnnotations(), true,
                    configuration.type().getQualifiedName(), method.getName()).ifPresent(dependencies::add);
        }
        bean.setDependencies(dependencies);
        return bean;
    }

    /** Scope, primary, lazy, qualifiers, profiles and conditions; class-level profiles/conditions apply to @Bean methods too. */
    private void applyBeanAnnotations(BeanDefinition bean, List<AnnotationStructure> own, List<AnnotationStructure> enclosing) {
        String scope = "singleton";
        Optional<AnnotationStructure> scopeAnnotation = find(own, CONTEXT + "Scope");
        if (scopeAnnotation.isPresent()) {
            scope = strings(scopeAnnotation.get(), "value", "scopeName").stream().findFirst()
                    .or(() -> constants(scopeAnnotation.get(), "ConfigurableBeanFactory", "value", "scopeName").stream()
                            .map(c -> c.replace("SCOPE_", "").toLowerCase(Locale.ROOT)).findFirst())
                    .orElse("singleton");
        } else if (has(own, "org.springframework.web.context.annotation.RequestScope")) {
            scope = "request";
        } else if (has(own, "org.springframework.web.context.annotation.SessionScope")) {
            scope = "session";
        } else if (has(own, "org.springframework.web.context.annotation.ApplicationScope")) {
            scope = "application";
        }
        bean.setScope(scope);
        bean.setPrimary(has(own, CONTEXT + "Primary"));
        bean.setLazy(find(own, CONTEXT + "Lazy").map(a -> !isFalse(a, "value")).orElse(false));
        bean.setQualifiers(find(own, BEANS + "Qualifier").map(a -> strings(a, "value")).orElse(List.of()));

        List<String> profiles = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        for (List<AnnotationStructure> source : List.of(enclosing, own)) {
            find(source, CONTEXT + "Profile").ifPresent(a -> profiles.addAll(strings(a, "value")));
            source.stream()
                    .filter(a -> a.getName().startsWith("ConditionalOn") || a.getName().equals("Conditional"))
                    .map(Annotations::display)
                    .forEach(conditions::add);
        }
        bean.setProfiles(profiles);
        bean.setConditions(conditions);
    }

    // ------------------------------------------------------------- injection

    /** Constructor (incl. Lombok-generated), field and setter injection points of a class bean. */
    private List<InjectionPoint> injectionPoints(Located located) {
        TypeStructure type = located.type();
        String typeName = type.getQualifiedName();
        List<InjectionPoint> points = new ArrayList<>();

        constructorParameters(located).forEach(p -> injection(InjectionPoint.Kind.CONSTRUCTOR, p.name(), p.type(), p.annotations(), true,
                typeName, type.getName()).ifPresent(points::add));

        List<String> injectedFields = new ArrayList<>();
        for (FieldStructure field : type.getFields()) {
            if (hasModifier(field.getModifiers(), ModifierKind.STATIC)) {
                continue;
            }
            Optional<AnnotationStructure> autowired = find(field.getAnnotations(), BEANS + "Autowired", "jakarta.inject.Inject", "javax.inject.Inject");
            Optional<AnnotationStructure> resource = find(field.getAnnotations(), "jakarta.annotation.Resource", "javax.annotation.Resource");
            if (autowired.isPresent() || resource.isPresent()) {
                boolean required = autowired.map(a -> !isFalse(a, "required")).orElse(true);
                Optional<InjectionPoint> point = injection(InjectionPoint.Kind.FIELD, field.getName(), field.getType(), field.getAnnotations(),
                        required, typeName, field.getName());
                point.ifPresent(p -> {
                    resource.flatMap(r -> strings(r, "name").stream().findFirst()).ifPresent(p::setQualifier);
                    points.add(p);
                    injectedFields.add(field.getName());
                });
            } else {
                valueUsage(field.getAnnotations(), typeName, field.getName());
            }
        }
        if (!injectedFields.isEmpty() && fieldInjectionReported.add(typeName)) {
            issues.add(issue(BeanIssue.Severity.INFO, BeanIssue.Kind.FIELD_INJECTION, defaultBeanName(type), typeName,
                    type.getName() + " uses field injection (" + String.join(", ", injectedFields)
                            + "); constructor injection makes dependencies explicit and the class testable without Spring"));
        }

        for (MethodStructure method : type.getMethods()) {
            Optional<AnnotationStructure> autowired = find(method.getAnnotations(), BEANS + "Autowired", "jakarta.inject.Inject", "javax.inject.Inject");
            if (autowired.isPresent() && !has(method.getAnnotations(), CONTEXT + "Bean")) {
                boolean required = !isFalse(autowired.get(), "required");
                for (ParameterStructure parameter : method.getParameters()) {
                    injection(InjectionPoint.Kind.SETTER, parameter.getName(), parameter.getType(), parameter.getAnnotations(), required,
                            typeName, method.getName()).ifPresent(points::add);
                }
            }
            if (!has(method.getAnnotations(), CONTEXT + "Bean")) {
                method.getParameters().forEach(p -> valueUsage(p.getAnnotations(), typeName, method.getName()));
            }
            valueUsage(method.getAnnotations(), typeName, method.getName());
        }
        return points;
    }

    private record Parameter(String name, TypeReference type, List<AnnotationStructure> annotations) {
    }

    /**
     * Spring's constructor choice: the @Autowired one, else the only one, else none (default
     * constructor). Lombok @RequiredArgsConstructor / @AllArgsConstructor count as constructors.
     */
    private List<Parameter> constructorParameters(Located located) {
        TypeStructure type = located.type();
        if (type.getKind() == TypeKind.RECORD) {
            return type.getRecordComponents().stream().map(c -> new Parameter(c.getName(), c.getType(), c.getAnnotations())).toList();
        }
        List<List<Parameter>> candidates = new ArrayList<>();
        List<Parameter> autowired = null;
        for (ConstructorStructure ctor : type.getConstructors()) {
            List<Parameter> params = ctor.getParameters().stream().map(p -> new Parameter(p.getName(), p.getType(), p.getAnnotations())).toList();
            params.forEach(p -> valueUsage(p.annotations(), type.getQualifiedName(), type.getName()));
            candidates.add(params);
            if (has(ctor.getAnnotations(), BEANS + "Autowired", "jakarta.inject.Inject", "javax.inject.Inject")) {
                autowired = params;
            }
        }
        List<FieldStructure> instanceFields = type.getFields().stream()
                .filter(f -> !hasModifier(f.getModifiers(), ModifierKind.STATIC)).toList();
        if (has(type.getAnnotations(), "lombok.RequiredArgsConstructor")) {
            candidates.add(instanceFields.stream()
                    .filter(f -> f.getInitializer() == null && (hasModifier(f.getModifiers(), ModifierKind.FINAL) || has(f.getAnnotations(), "lombok.NonNull")))
                    .map(f -> new Parameter(f.getName(), f.getType(), f.getAnnotations())).toList());
        }
        if (has(type.getAnnotations(), "lombok.AllArgsConstructor")) {
            candidates.add(instanceFields.stream()
                    .filter(f -> !(hasModifier(f.getModifiers(), ModifierKind.FINAL) && f.getInitializer() != null))
                    .map(f -> new Parameter(f.getName(), f.getType(), f.getAnnotations())).toList());
        }
        if (autowired != null) {
            return autowired;
        }
        if (candidates.size() == 1) {
            return candidates.getFirst();
        }
        if (candidates.size() > 1 && candidates.stream().noneMatch(List::isEmpty)) {
            issues.add(issue(BeanIssue.Severity.WARNING, BeanIssue.Kind.NO_USABLE_CONSTRUCTOR, defaultBeanName(type), type.getQualifiedName(),
                    type.getName() + " has " + candidates.size() + " constructors, none @Autowired and none without arguments: Spring cannot choose one"));
        }
        return List.of();
    }

    /** An injection point for a dependency, or empty when it is a @Value property instead of a bean. */
    private Optional<InjectionPoint> injection(InjectionPoint.Kind kind, String name, TypeReference type, List<AnnotationStructure> annotations,
                                               boolean required, String owner, String member) {
        if (has(annotations, BEANS + "Value")) {
            valueUsage(annotations, owner, member);
            return Optional.empty();
        }
        TypeReference target = type;
        boolean collection = type.isArray();
        boolean optional = false;
        if (!type.isArray() && type.getTypeArguments() != null && !type.getTypeArguments().isEmpty()) {
            String container = type.getName();
            if (MULTI_CONTAINERS.contains(container)) {
                collection = true;
                target = container.equals("Map") && type.getTypeArguments().size() == 2 ? type.getTypeArguments().get(1) : type.getTypeArguments().getFirst();
            } else if (OPTIONAL_CONTAINERS.contains(container)) {
                optional = true;
                target = type.getTypeArguments().getFirst();
            }
        }
        if (target.isWildcard() && target.getWildcardBound() != null) {
            target = target.getWildcardBound();
        }
        String qualifier = find(annotations, BEANS + "Qualifier", "jakarta.inject.Named", "javax.inject.Named")
                .flatMap(a -> strings(a, "value").stream().findFirst()).orElse(null);
        boolean lazy = has(annotations, CONTEXT + "Lazy");
        return Optional.of(InjectionPoint.builder()
                .kind(kind)
                .name(name)
                .typeName(Optional.ofNullable(target.getQualifiedName()).orElse(target.getName()))
                .collection(collection || lazy)
                .required(required && !optional && !(collection && type.getName().equals("ObjectProvider")))
                .qualifier(qualifier)
                .candidates(new ArrayList<>())
                .build());
    }

    /** Applies Spring's autowiring rules: type match, then @Qualifier, then @Primary, then parameter/field name. */
    private void resolveDependencies() {
        Map<String, BeanDefinition> byName = beans.stream().collect(Collectors.toMap(BeanDefinition::getName, b -> b, (a, b) -> a));
        Map<String, List<BeanDefinition>> externalNeeds = new TreeMap<>();
        for (BeanDefinition bean : beans) {
            for (InjectionPoint point : bean.getDependencies()) {
                List<BeanDefinition> candidates = beans.stream()
                        .filter(b -> b != bean && b.getAssignableTo().stream().anyMatch(t -> sameType(t, point.getTypeName())))
                        .toList();
                point.setCandidates(candidates.stream().map(BeanDefinition::getName).toList());
                List<BeanDefinition> chosen = candidates;
                if (point.getQualifier() != null) {
                    chosen = chosen.stream().filter(b -> b.getName().equals(point.getQualifier())
                            || b.getAliases().contains(point.getQualifier()) || b.getQualifiers().contains(point.getQualifier())).toList();
                }
                if (chosen.size() > 1 && chosen.stream().filter(BeanDefinition::isPrimary).count() == 1) {
                    chosen = chosen.stream().filter(BeanDefinition::isPrimary).toList();
                }
                if (chosen.size() > 1) {
                    List<BeanDefinition> byFallbackName = chosen.stream().filter(b -> b.getName().equals(point.getName())).toList();
                    if (byFallbackName.size() == 1) {
                        chosen = byFallbackName;
                    }
                }
                if (chosen.size() == 1) {
                    point.setResolvedTo(chosen.getFirst().getName());
                }
                (point.isCollection() ? candidates : chosen).forEach(b -> {
                    if (!b.getInjectedInto().contains(bean.getName())) {
                        b.getInjectedInto().add(bean.getName());
                    }
                });

                String where = bean.getName() + (point.getKind() == InjectionPoint.Kind.FIELD ? "." : " parameter ") + point.getName();
                if (chosen.isEmpty() && point.isRequired() && !point.isCollection()) {
                    if (point.getQualifier() != null && !candidates.isEmpty()) {
                        issues.add(issue(BeanIssue.Severity.ERROR, BeanIssue.Kind.UNSATISFIED_DEPENDENCY, bean.getName(), bean.getDeclaringType(),
                                where + " needs " + simple(point.getTypeName()) + " qualified \"" + point.getQualifier()
                                        + "\", but the candidates are " + candidates.stream().map(BeanDefinition::getName).toList()));
                    } else if (lookup(simple(point.getTypeName()), point.getTypeName().contains(".") ? point.getTypeName() : null) != null) {
                        issues.add(issue(BeanIssue.Severity.ERROR, BeanIssue.Kind.UNSATISFIED_DEPENDENCY, bean.getName(), bean.getDeclaringType(),
                                where + " needs a bean of type " + simple(point.getTypeName()) + ", but none is declared"
                                        + " (missing @Component/@Service, or a @Bean method?)"));
                    } else if (!byName.containsKey(point.getName())) {
                        externalNeeds.computeIfAbsent(point.getTypeName(), k -> new ArrayList<>()).add(bean);
                    }
                } else if (chosen.size() > 1 && !point.isCollection()) {
                    boolean exclusive = chosen.stream().anyMatch(b -> !b.getProfiles().isEmpty() || !b.getConditions().isEmpty());
                    issues.add(issue(exclusive ? BeanIssue.Severity.WARNING : BeanIssue.Severity.ERROR, BeanIssue.Kind.AMBIGUOUS_DEPENDENCY,
                            bean.getName(), bean.getDeclaringType(), where + " matches " + chosen.size() + " " + simple(point.getTypeName())
                                    + " beans " + chosen.stream().map(BeanDefinition::getName).toList()
                                    + (exclusive ? "; fine only if their profiles/conditions never overlap" : "; add @Primary to one or @Qualifier here")));
                }
            }
        }
        externalNeeds.forEach((type, needing) -> issues.add(issue(BeanIssue.Severity.INFO, BeanIssue.Kind.EXTERNAL_DEPENDENCY, null, type,
                simple(type) + " is not declared in this project; it must come from auto-configuration or a library (needed by "
                        + needing.stream().map(BeanDefinition::getName).distinct().collect(Collectors.joining(", ")) + ")")));
    }

    private void checkComponentScan(Set<String> scanPackages) {
        if (scanPackages.isEmpty()) {
            return;
        }
        for (BeanDefinition bean : beans) {
            if (bean.getKind() == BeanKind.BEAN_METHOD || bean.getKind() == BeanKind.CONFIGURATION_PROPERTIES) {
                continue;
            }
            String packageName = Optional.ofNullable(types.get(bean.getDeclaringType())).map(Located::packageName).orElse("");
            boolean scanned = scanPackages.stream().anyMatch(p -> inPackage(packageName, p));
            bean.setInComponentScan(scanned);
            if (!scanned && types.get(bean.getDeclaringType()).type().getAnnotations().stream()
                    .noneMatch(a -> a.getName().equals("SpringBootApplication"))) {
                issues.add(issue(BeanIssue.Severity.WARNING, BeanIssue.Kind.OUTSIDE_COMPONENT_SCAN, bean.getName(), bean.getDeclaringType(),
                        simple(bean.getTypeName()) + " is in package " + packageName + ", outside the scanned packages " + scanPackages
                                + ": Spring will not find it unless it is imported explicitly"));
            }
        }
    }

    private void checkDuplicates() {
        Map<String, List<BeanDefinition>> byName = new TreeMap<>();
        for (BeanDefinition bean : beans) {
            Stream.concat(Stream.of(bean.getName()), bean.getAliases().stream())
                    .forEach(name -> byName.computeIfAbsent(name, k -> new ArrayList<>()).add(bean));
        }
        byName.forEach((name, defined) -> {
            if (defined.size() > 1) {
                issues.add(issue(BeanIssue.Severity.ERROR, BeanIssue.Kind.DUPLICATE_BEAN_NAME, name, defined.getFirst().getDeclaringType(),
                        "Bean name \"" + name + "\" is defined " + defined.size() + " times (" + defined.stream()
                                .map(b -> simple(b.getDeclaringType()) + (b.getDeclaringMember() != null ? "." + b.getDeclaringMember() + "()" : ""))
                                .collect(Collectors.joining(", ")) + "); Spring Boot rejects bean overriding by default"));
            }
        });
    }

    /** Cycles through resolved dependencies (Tarjan's strongly connected components). */
    private void checkCycles() {
        Map<String, BeanDefinition> byName = beans.stream().collect(Collectors.toMap(BeanDefinition::getName, b -> b, (a, b) -> a));
        Map<String, List<InjectionPoint>> edges = new HashMap<>();
        for (BeanDefinition bean : beans) {
            edges.put(bean.getName(), bean.getDependencies().stream()
                    .filter(p -> p.getResolvedTo() != null && !p.isCollection()).toList());
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        int[] counter = {0};
        for (String start : edges.keySet().stream().sorted().toList()) {
            if (!index.containsKey(start)) {
                strongConnect(start, edges, index, low, stack, onStack, counter, byName);
            }
        }
    }

    private void strongConnect(String node, Map<String, List<InjectionPoint>> edges, Map<String, Integer> index, Map<String, Integer> low,
                               Deque<String> stack, Set<String> onStack, int[] counter, Map<String, BeanDefinition> byName) {
        index.put(node, counter[0]);
        low.put(node, counter[0]++);
        stack.push(node);
        onStack.add(node);
        for (InjectionPoint edge : edges.getOrDefault(node, List.of())) {
            String next = edge.getResolvedTo();
            if (!edges.containsKey(next)) {
                continue;
            }
            if (!index.containsKey(next)) {
                strongConnect(next, edges, index, low, stack, onStack, counter, byName);
                low.put(node, Math.min(low.get(node), low.get(next)));
            } else if (onStack.contains(next)) {
                low.put(node, Math.min(low.get(node), index.get(next)));
            }
        }
        if (low.get(node).equals(index.get(node))) {
            List<String> component = new ArrayList<>();
            String member;
            do {
                member = stack.pop();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(node));
            boolean selfLoop = component.size() == 1 && edges.get(node).stream().anyMatch(e -> e.getResolvedTo().equals(node));
            if (component.size() > 1 || selfLoop) {
                Collections.reverse(component);
                boolean constructorOnly = component.stream().flatMap(n -> edges.get(n).stream())
                        .filter(e -> component.contains(e.getResolvedTo()))
                        .allMatch(e -> e.getKind() == InjectionPoint.Kind.CONSTRUCTOR || e.getKind() == InjectionPoint.Kind.BEAN_METHOD_PARAMETER);
                issues.add(issue(BeanIssue.Severity.ERROR, BeanIssue.Kind.CIRCULAR_DEPENDENCY, component.getFirst(),
                        byName.get(component.getFirst()).getDeclaringType(),
                        "Circular dependency: " + String.join(" -> ", component) + " -> " + component.getFirst()
                                + (constructorOnly ? " (constructor injection: cannot be created at all)"
                                : " (fails on Spring Boot 2.6+ unless spring.main.allow-circular-references=true; consider @Lazy)")));
            }
        }
    }

    // --------------------------------------------------------------- web

    private List<EndpointStructure> endpoints() {
        List<EndpointStructure> result = new ArrayList<>();
        for (BeanDefinition bean : beans) {
            if (bean.getKind() != BeanKind.CONTROLLER && bean.getKind() != BeanKind.REST_CONTROLLER) {
                continue;
            }
            TypeStructure type = types.get(bean.getTypeName()).type();
            List<String> prefixes = find(type.getAnnotations(), WEB + "RequestMapping")
                    .map(a -> strings(a, "value", "path")).filter(p -> !p.isEmpty()).orElse(List.of(""));
            boolean classBody = bean.getKind() == BeanKind.REST_CONTROLLER || has(type.getAnnotations(), WEB + "ResponseBody");
            for (MethodStructure method : type.getMethods()) {
                for (Map.Entry<String, String> mapping : MAPPINGS.entrySet()) {
                    Optional<AnnotationStructure> annotation = find(method.getAnnotations(), mapping.getKey());
                    if (annotation.isEmpty()) {
                        continue;
                    }
                    List<String> methods = mapping.getValue().isEmpty()
                            ? constants(annotation.get(), "RequestMethod", "method") : List.of(mapping.getValue());
                    List<String> paths = strings(annotation.get(), "value", "path");
                    for (String prefix : prefixes) {
                        for (String path : paths.isEmpty() ? List.of("") : paths) {
                            result.add(EndpointStructure.builder()
                                    .httpMethods(methods)
                                    .path(joinPaths(prefix, path))
                                    .controllerType(type.getQualifiedName())
                                    .methodName(method.getName())
                                    .methodId(memberId(method))
                                    .returnType(method.getReturnType() == null ? "void" : typeToString(method.getReturnType()))
                                    .produces(strings(annotation.get(), "produces"))
                                    .consumes(strings(annotation.get(), "consumes"))
                                    .parameters(endpointParameters(method))
                                    .responseBody(classBody || has(method.getAnnotations(), WEB + "ResponseBody"))
                                    .line(method.getStartLine())
                                    .build());
                        }
                    }
                }
            }
        }
        result.sort(Comparator.comparing(EndpointStructure::getPath).thenComparing(e -> String.join(",", e.getHttpMethods())));
        return result;
    }

    private static List<EndpointParameter> endpointParameters(MethodStructure method) {
        List<EndpointParameter> result = new ArrayList<>();
        for (ParameterStructure parameter : method.getParameters()) {
            String type = typeToString(parameter.getType());
            boolean optionalType = parameter.getType().getName().equals("Optional");
            for (String[] binding : new String[][]{{"PathVariable", "path"}, {"RequestParam", "query"}, {"RequestBody", "body"},
                    {"RequestHeader", "header"}, {"CookieValue", "cookie"}, {"ModelAttribute", "form"}, {"RequestPart", "part"}}) {
                find(parameter.getAnnotations(), WEB + binding[0]).ifPresent(a -> {
                    String defaultValue = strings(a, "defaultValue").stream().findFirst().orElse(null);
                    result.add(EndpointParameter.builder()
                            .source(binding[1])
                            .name(strings(a, "value", "name").stream().findFirst().orElse(parameter.getName()))
                            .typeName(type)
                            .required(!isFalse(a, "required") && defaultValue == null && !optionalType)
                            .defaultValue(defaultValue)
                            .build());
                });
            }
        }
        return result;
    }

    private static String joinPaths(String prefix, String path) {
        String joined = ("/" + prefix + "/" + path).replaceAll("/{2,}", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    // -------------------------------------------------------- properties

    private List<ConfigurationPropertiesStructure> configurationProperties() {
        List<ConfigurationPropertiesStructure> result = new ArrayList<>();
        for (Located located : types.values()) {
            find(located.type().getAnnotations(), BOOT_PROPERTIES + "ConfigurationProperties").ifPresent(annotation -> {
                String prefix = strings(annotation, "value", "prefix").stream().findFirst().orElse("");
                result.add(ConfigurationPropertiesStructure.builder()
                        .prefix(prefix)
                        .typeName(located.type().getQualifiedName())
                        .properties(properties(located.type(), prefix, 0))
                        .build());
            });
            // @ConfigurationProperties on @Bean methods binds onto the returned object
            for (MethodStructure method : located.type().getMethods()) {
                Optional<AnnotationStructure> annotation = find(method.getAnnotations(), BOOT_PROPERTIES + "ConfigurationProperties");
                if (annotation.isPresent() && has(method.getAnnotations(), CONTEXT + "Bean") && method.getReturnType() != null) {
                    String prefix = strings(annotation.get(), "value", "prefix").stream().findFirst().orElse("");
                    Located returned = lookup(method.getReturnType().getName(), method.getReturnType().getQualifiedName());
                    result.add(ConfigurationPropertiesStructure.builder()
                            .prefix(prefix)
                            .typeName(returned != null ? returned.type().getQualifiedName() : typeToString(method.getReturnType()))
                            .properties(returned != null ? properties(returned.type(), prefix, 0) : List.of())
                            .build());
                }
            }
        }
        return result;
    }

    private List<ConfigurationProperty> properties(TypeStructure type, String prefix, int depth) {
        List<ConfigurationProperty> result = new ArrayList<>();
        List<Object[]> members = new ArrayList<>(); // name, type, default, javadoc
        if (type.getKind() == TypeKind.RECORD) {
            type.getRecordComponents().forEach(c -> members.add(new Object[]{c.getName(), c.getType(), null, c.getJavadoc()}));
        } else {
            type.getFields().stream().filter(f -> !hasModifier(f.getModifiers(), ModifierKind.STATIC))
                    .forEach(f -> members.add(new Object[]{f.getName(), f.getType(), f.getInitializer(), f.getJavadoc()}));
        }
        for (Object[] member : members) {
            String key = (prefix.isEmpty() ? "" : prefix + ".") + kebab((String) member[0]);
            TypeReference memberType = (TypeReference) member[1];
            Located nested = lookup(memberType.getName(), memberType.getQualifiedName());
            if (nested != null && nested.type().getKind() != TypeKind.ENUM && depth < 3 && !memberType.isArray()
                    && !SIMPLE_VALUE_TYPES.contains(memberType.getName())) {
                result.addAll(properties(nested.type(), key, depth + 1));
                continue;
            }
            JavadocStructure doc = (JavadocStructure) member[3];
            result.add(ConfigurationProperty.builder()
                    .key(key)
                    .typeName(typeToString(memberType))
                    .defaultValue((String) member[2])
                    .description(doc == null ? null : doc.getDescription())
                    .build());
        }
        return result;
    }

    private void valueUsage(List<AnnotationStructure> annotations, String typeName, String member) {
        find(annotations, BEANS + "Value").ifPresent(annotation -> {
            String expression = attribute(annotation, "value").orElse("");
            for (String literal : strings(expression)) {
                int at = 0;
                while ((at = literal.indexOf("${", at)) >= 0) {
                    int end = literal.indexOf('}', at);
                    if (end < 0) {
                        break;
                    }
                    String placeholder = literal.substring(at + 2, end);
                    int colon = placeholder.indexOf(':');
                    String key = colon < 0 ? placeholder : placeholder.substring(0, colon);
                    PropertyUsage usage = PropertyUsage.builder()
                            .key(key)
                            .defaultValue(colon < 0 ? null : placeholder.substring(colon + 1))
                            .expression(literal)
                            .typeName(typeName)
                            .memberName(member)
                            .build();
                    if (propertyUsages.stream().noneMatch(u -> u.getKey().equals(key) && u.getTypeName().equals(typeName)
                            && Objects.equals(u.getMemberName(), member))) {
                        propertyUsages.add(usage);
                    }
                    at = end + 1;
                }
            }
        });
    }

    // ------------------------------------------------------------ entities

    private List<EntityStructure> entities() {
        List<EntityStructure> result = new ArrayList<>();
        for (Located located : types.values()) {
            TypeStructure type = located.type();
            find(type.getAnnotations(), "jakarta.persistence.Entity", "javax.persistence.Entity").ifPresent(entity -> result.add(
                    EntityStructure.builder()
                            .typeName(type.getQualifiedName())
                            .entityName(strings(entity, "name").stream().findFirst().orElse(type.getName()))
                            .tableName(find(type.getAnnotations(), "jakarta.persistence.Table", "javax.persistence.Table")
                                    .flatMap(t -> strings(t, "name").stream().findFirst()).orElse(null))
                            .idField(type.getFields().stream()
                                    .filter(f -> has(f.getAnnotations(), "jakarta.persistence.Id", "javax.persistence.Id",
                                            "jakarta.persistence.EmbeddedId", "javax.persistence.EmbeddedId"))
                                    .map(FieldStructure::getName).findFirst().orElse(null))
                            .build()));
        }
        return result;
    }

    // -------------------------------------------------------------- helpers

    /** The type and every supertype the project knows about (qualified when known, simple otherwise). */
    private Set<String> supertypes(TypeStructure type) {
        Set<String> result = new LinkedHashSet<>();
        Deque<TypeStructure> pending = new ArrayDeque<>(List.of(type));
        while (!pending.isEmpty()) {
            TypeStructure current = pending.pop();
            String name = Optional.ofNullable(current.getQualifiedName()).orElse(current.getName());
            if (!result.add(name)) {
                continue;
            }
            List<TypeStructure> parents = new ArrayList<>(current.getInterfaces());
            if (current.getSuperclass() != null) {
                parents.add(current.getSuperclass());
            }
            for (TypeStructure parent : parents) {
                Located local = lookup(parent.getName(), parent.getQualifiedName());
                pending.push(local != null ? local.type() : parent);
            }
        }
        return result;
    }

    /** A project type by qualified name, or by simple name when that is unique. */
    private Located lookup(String simpleName, String qualifiedName) {
        if (qualifiedName != null) {
            return types.get(qualifiedName);
        }
        List<String> matches = bySimpleName.getOrDefault(simpleName, List.of());
        return matches.size() == 1 ? types.get(matches.getFirst()) : null;
    }

    private Optional<String> resolveTypeName(String written, Located context) {
        if (types.containsKey(written)) {
            return Optional.of(written);
        }
        String samePackage = context.packageName().isEmpty() ? written : context.packageName() + "." + written;
        if (types.containsKey(samePackage)) {
            return Optional.of(samePackage);
        }
        Located unique = lookup(simple(written), null);
        return Optional.ofNullable(unique).map(l -> l.type().getQualifiedName());
    }

    /** Equal qualified names, or equal simple names when one side is unqualified. */
    static boolean sameType(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        boolean aQualified = a.contains(".");
        boolean bQualified = b.contains(".");
        return (!aQualified || !bQualified) && simple(a).equals(simple(b));
    }

    static boolean inPackage(String packageName, String base) {
        return base.isEmpty() || packageName.equals(base) || packageName.startsWith(base + ".");
    }

    /** Spring's default: the short class name (Outer.Inner for nested) decapitalized like java.beans.Introspector. */
    private String defaultBeanName(TypeStructure type) {
        Located located = types.get(type.getQualifiedName());
        String packageName = located == null ? "" : located.packageName();
        String shortName = packageName.isEmpty() ? type.getQualifiedName() : type.getQualifiedName().substring(packageName.length() + 1);
        return decapitalize(shortName);
    }

    static String decapitalize(String name) {
        if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
            return name;
        }
        return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    /** camelCase -> kebab-case, as Spring Boot documents relaxed binding keys. */
    static String kebab(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static boolean hasModifier(List<ModifierStructure> modifiers, ModifierKind kind) {
        return modifiers.stream().anyMatch(m -> m.getKind() == kind);
    }

    private static String simple(String name) {
        int generic = name.indexOf('<');
        String raw = generic < 0 ? name : name.substring(0, generic);
        return raw.substring(raw.lastIndexOf('.') + 1);
    }

    /** Same text as the site uses for member anchors (see memberId in the site). */
    static String typeToString(TypeReference ref) {
        String text;
        if (ref.isWildcard()) {
            text = ref.getWildcardKind() == WildcardKind.EXTENDS && ref.getWildcardBound() != null ? "? extends " + typeToString(ref.getWildcardBound())
                    : ref.getWildcardKind() == WildcardKind.SUPER && ref.getWildcardBound() != null ? "? super " + typeToString(ref.getWildcardBound()) : "?";
        } else {
            text = ref.getName();
            if (ref.getTypeArguments() != null && !ref.getTypeArguments().isEmpty()) {
                text += ref.getTypeArguments().stream().map(SpringAnalyzer::typeToString).collect(Collectors.joining(", ", "<", ">"));
            }
        }
        return text + "[]".repeat(ref.isArray() ? ref.getArrayDimensions() : 0);
    }

    static String memberId(MethodStructure method) {
        return method.getName() + method.getParameters().stream().map(p -> typeToString(p.getType())).collect(Collectors.joining(",", "(", ")"));
    }

    private static BeanIssue issue(BeanIssue.Severity severity, BeanIssue.Kind kind, String bean, String type, String message) {
        return BeanIssue.builder().severity(severity).kind(kind).beanName(bean).typeName(type).message(message).build();
    }
}
