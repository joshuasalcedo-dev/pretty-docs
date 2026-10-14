package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.maven.model.DependencyStructure;
import io.joshuasalcedo.pretty.model.*;
import io.joshuasalcedo.pretty.plugin.model.DiagramStructure;
import io.joshuasalcedo.pretty.plugin.model.DocumentationSite;
import io.joshuasalcedo.pretty.plugin.model.ModuleDocumentation;
import io.joshuasalcedo.pretty.spring.model.BeanDefinition;
import io.joshuasalcedo.pretty.spring.model.InjectionPoint;
import io.joshuasalcedo.pretty.spring.model.SpringStructure;
import net.sourceforge.plantuml.FileFormat;
import net.sourceforge.plantuml.FileFormatOption;
import net.sourceforge.plantuml.SourceStringReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * PlantUML diagrams for a site: a class diagram per package, the module dependency graph, and the
 * Spring bean graph. Rendering uses PlantUML's built-in Smetana layout, so no Graphviz is needed.
 */
public final class DiagramGenerator {

    /** A diagram's source and, when rendered, its SVG. */
    public record Diagram(DiagramStructure structure, String puml, byte[] svg) {
    }

    private static final int MAX_MEMBERS = 12;
    private static final int MAX_ELEMENTS = 80;
    // Not a text block: maven-plugin-plugin's QDox cannot parse them in plugin sources.
    private static final String STYLE = String.join("\n",
            "!pragma layout smetana",
            "skinparam backgroundColor transparent",
            "skinparam shadowing false",
            "skinparam roundCorner 6",
            "skinparam defaultFontName Helvetica",
            "skinparam defaultFontSize 12",
            "skinparam defaultFontColor #454545",
            "skinparam ArrowColor #7c8189",
            "skinparam ClassBorderColor #c9ced6",
            "skinparam ClassBackgroundColor #ffffff",
            "skinparam ClassHeaderBackgroundColor #f3f6fb",
            "skinparam ClassAttributeIconSize 0",
            "skinparam ComponentBorderColor #c9ced6",
            "skinparam ComponentBackgroundColor #ffffff",
            "skinparam PackageBorderColor #e4e6ea",
            "skinparam StereotypeFontColor #2f6fde",
            "hide empty members",
            "set separator none", // a.b.c is one package, not three nested ones
            "");

    /** Spot letter and color per kind, matching the site icons (no orange). */
    private static String spot(TypeStructure type) {
        return switch (type.getKind()) {
            case INTERFACE -> "(I,#dff3ea)";
            case ENUM -> "(E,#ece4fa)";
            case RECORD -> "(R,#dcf2f1)";
            case ANNOTATION -> "(@,#eef0f3)";
            case CLASS -> type.getModifiers().stream().anyMatch(m -> m.getKind() == ModifierKind.ABSTRACT) ? "(A,#e6edf8)" : "(C,#dce8fb)";
        };
    }

    private final boolean render;
    private final Consumer<String> log;

    /**
     * @param render false to write PlantUML sources only (faster; the site then offers the .puml files)
     * @param log    receives warnings about diagrams that failed to render
     */
    public DiagramGenerator(boolean render, Consumer<String> log) {
        this.render = render;
        this.log = log;
    }

    public List<Diagram> generate(DocumentationSite site) {
        Map<String, TypeStructure> projectTypes = new HashMap<>();
        Map<String, String> stereotypes = stereotypes(site.getSpring());
        for (ModuleDocumentation module : site.getModules()) {
            for (JavaFileStructure file : module.getFiles()) {
                file.getTypes().forEach(t -> index(t, projectTypes));
            }
        }

        List<Diagram> diagrams = new ArrayList<>();
        moduleDiagram(site).ifPresent(diagrams::add);
        for (ModuleDocumentation module : site.getModules()) {
            Map<String, List<TypeStructure>> byPackage = new TreeMap<>();
            for (JavaFileStructure file : module.getFiles()) {
                byPackage.computeIfAbsent(file.getPackageName(), k -> new ArrayList<>()).addAll(file.getTypes());
            }
            byPackage.forEach((pkg, types) -> {
                if (!types.isEmpty()) {
                    diagrams.add(packageDiagram(module, pkg, types, projectTypes, stereotypes));
                }
            });
        }
        if (site.getSpring() != null && !site.getSpring().getBeans().isEmpty()) {
            diagrams.add(springDiagram(site.getSpring()));
        }
        return diagrams;
    }

    private static void index(TypeStructure type, Map<String, TypeStructure> types) {
        types.put(type.getQualifiedName(), type);
        type.getNestedTypes().forEach(nested -> index(nested, types));
    }

    // -------------------------------------------------------- class diagrams

    private Diagram packageDiagram(ModuleDocumentation module, String pkg, List<TypeStructure> topLevel,
                                   Map<String, TypeStructure> projectTypes, Map<String, String> stereotypes) {
        List<TypeStructure> types = new ArrayList<>();
        topLevel.forEach(t -> flatten(t, types));
        Set<String> inPackage = types.stream().map(TypeStructure::getQualifiedName).collect(Collectors.toSet());
        Set<String> related = new TreeSet<>(); // supertypes and field types outside the package
        List<String> relations = new ArrayList<>();

        StringBuilder puml = new StringBuilder("@startuml\n").append(STYLE);
        puml.append("package \"").append(pkg.isEmpty() ? "(default)" : pkg).append("\" {\n");
        int shown = 0;
        for (TypeStructure type : types) {
            if (shown++ >= MAX_ELEMENTS) {
                puml.append("  note \"").append(types.size() - MAX_ELEMENTS).append(" more types not shown\" as more\n");
                break;
            }
            declare(puml, type, stereotypes.get(type.getQualifiedName()), "  ");
            String id = alias(type.getQualifiedName());
            if (type.getSuperclass() != null) {
                String target = target(type.getSuperclass().getQualifiedName(), type.getSuperclass().getName(), inPackage, projectTypes, related, true);
                if (target != null) relations.add(target + " <|-- " + id);
            }
            for (TypeStructure parent : type.getInterfaces()) {
                String target = target(parent.getQualifiedName(), parent.getName(), inPackage, projectTypes, related, true);
                if (target != null) relations.add(target + (type.getKind() == TypeKind.INTERFACE ? " <|-- " : " <|.. ") + id);
            }
            Set<String> associated = new LinkedHashSet<>();
            for (FieldStructure field : type.getFields()) {
                if (field.getModifiers().stream().anyMatch(m -> m.getKind() == ModifierKind.STATIC)) continue;
                for (String fieldType : referencedTypes(field.getType())) {
                    String target = target(fieldType, null, inPackage, projectTypes, related, false);
                    if (target != null && !target.equals(id) && associated.add(target + field.getName())) {
                        relations.add(id + " --> " + target + " : " + field.getName());
                    }
                }
            }
            for (TypeStructure nested : type.getNestedTypes()) {
                relations.add(id + " +-- " + alias(nested.getQualifiedName()));
            }
        }
        puml.append("}\n");
        for (String name : related) {
            TypeStructure type = projectTypes.get(name);
            String keyword = type == null ? "class" : keyword(type);
            String spot = type == null ? "(C,#eef0f3)" : spot(type);
            puml.append(keyword).append(" \"").append(simple(name)).append("\" as ").append(alias(name))
                    .append(" << ").append(spot).append(" >> #f6f7f9\n");
        }
        relations.forEach(r -> puml.append(r).append('\n'));
        puml.append("@enduml\n");

        String id = "package-" + slug(module.getProject().getArtifactId() + "-" + (pkg.isEmpty() ? "default" : pkg));
        return render(DiagramStructure.builder()
                .id(id)
                .kind(DiagramStructure.Kind.PACKAGE_CLASSES)
                .title(pkg.isEmpty() ? "(default package)" : pkg)
                .moduleId(module.getId())
                .packageName(pkg)
                .elementCount(types.size())
                .build(), puml.toString());
    }

    private static void flatten(TypeStructure type, List<TypeStructure> into) {
        into.add(type);
        type.getNestedTypes().forEach(nested -> flatten(nested, into));
    }

    private static void declare(StringBuilder puml, TypeStructure type, String stereotype, String indent) {
        puml.append(indent).append(keyword(type)).append(" \"").append(displayName(type)).append("\" as ").append(alias(type.getQualifiedName()));
        puml.append(" << ").append(spot(type)).append(stereotype != null ? " " + stereotype : "").append(" >>");
        puml.append(" {\n");
        int members = 0;
        for (EnumConstantStructure constant : type.getEnumConstants()) {
            if (members++ >= MAX_MEMBERS) break;
            puml.append(indent).append("  ").append(constant.getName()).append('\n');
        }
        for (RecordComponentStructure component : type.getRecordComponents()) {
            if (members++ >= MAX_MEMBERS) break;
            puml.append(indent).append("  ").append(component.getName()).append(" : ").append(typeText(component.getType())).append('\n');
        }
        for (FieldStructure field : type.getFields()) {
            if (members++ >= MAX_MEMBERS) break;
            puml.append(indent).append("  ").append(visibility(field.getModifiers())).append(staticMark(field.getModifiers()))
                    .append(field.getName()).append(" : ").append(typeText(field.getType())).append('\n');
        }
        for (MethodStructure method : type.getMethods()) {
            if (members++ >= MAX_MEMBERS) break;
            puml.append(indent).append("  ").append(visibility(method.getModifiers())).append(staticMark(method.getModifiers()))
                    .append(abstractMark(method.getModifiers())).append(method.getName()).append('(')
                    .append(method.getParameters().stream().map(p -> typeText(p.getType())).collect(Collectors.joining(", ")))
                    .append(')').append(method.getReturnType() == null ? "" : " : " + typeText(method.getReturnType())).append('\n');
        }
        int total = type.getEnumConstants().size() + type.getRecordComponents().size() + type.getFields().size() + type.getMethods().size();
        if (total > MAX_MEMBERS) {
            puml.append(indent).append("  .. ").append(total - MAX_MEMBERS).append(" more ..\n");
        }
        puml.append(indent).append("}\n");
    }

    /** The alias to draw an arrow to, declaring a dimmed placeholder for types outside the package. */
    private static String target(String qualified, String simpleName, Set<String> inPackage, Map<String, TypeStructure> projectTypes,
                                 Set<String> related, boolean includeLibraries) {
        if (qualified == null) {
            return null;
        }
        if (inPackage.contains(qualified)) {
            return alias(qualified);
        }
        if (projectTypes.containsKey(qualified) || (includeLibraries && !qualified.startsWith("java.lang."))) {
            related.add(qualified);
            return alias(qualified);
        }
        return null;
    }

    /** Qualified names in a type, including generic arguments: List<Order> -> Order. */
    private static List<String> referencedTypes(TypeReference ref) {
        List<String> names = new ArrayList<>();
        if (ref == null) return names;
        if (ref.getQualifiedName() != null && !ref.isPrimitive()) names.add(ref.getQualifiedName());
        if (ref.getTypeArguments() != null) ref.getTypeArguments().forEach(a -> names.addAll(referencedTypes(a)));
        if (ref.getWildcardBound() != null) names.addAll(referencedTypes(ref.getWildcardBound()));
        return names;
    }

    private static String keyword(TypeStructure type) {
        return switch (type.getKind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case ANNOTATION -> "annotation";
            case RECORD -> "record";
            case CLASS -> type.getModifiers().stream().anyMatch(m -> m.getKind() == ModifierKind.ABSTRACT) ? "abstract class" : "class";
        };
    }

    private static String visibility(List<ModifierStructure> modifiers) {
        for (ModifierStructure modifier : modifiers) {
            switch (modifier.getKind()) {
                case PUBLIC -> { return "+"; }
                case PROTECTED -> { return "#"; }
                case PRIVATE -> { return "-"; }
                case PACKAGE_PRIVATE -> { return "~"; }
                default -> { }
            }
        }
        return "+"; // interface members
    }

    private static String staticMark(List<ModifierStructure> modifiers) {
        return modifiers.stream().anyMatch(m -> m.getKind() == ModifierKind.STATIC) ? "{static} " : "";
    }

    private static String abstractMark(List<ModifierStructure> modifiers) {
        return modifiers.stream().anyMatch(m -> m.getKind() == ModifierKind.ABSTRACT) ? "{abstract} " : "";
    }

    private static String typeText(TypeReference ref) {
        if (ref == null) return "";
        String text = ref.isWildcard() ? "?" : ref.getName();
        if (ref.getTypeArguments() != null && !ref.getTypeArguments().isEmpty()) {
            text += ref.getTypeArguments().stream().map(DiagramGenerator::typeText).collect(Collectors.joining(", ", "<", ">"));
        }
        return text + "[]".repeat(ref.isArray() ? ref.getArrayDimensions() : 0);
    }

    // ----------------------------------------------------- module diagram

    private Optional<Diagram> moduleDiagram(DocumentationSite site) {
        if (site.getModules().size() < 2) {
            return Optional.empty();
        }
        Map<String, ModuleDocumentation> byId = new LinkedHashMap<>();
        site.getModules().forEach(m -> byId.put(m.getId(), m));
        List<String> edges = new ArrayList<>();
        Set<String> connected = new HashSet<>();
        for (ModuleDocumentation module : site.getModules()) {
            for (DependencyStructure dependency : module.getProject().getDependencies()) {
                String target = dependency.getGroupId() + ":" + dependency.getArtifactId();
                if (byId.containsKey(target) && !target.equals(module.getId())) {
                    edges.add(alias(module.getId()) + " --> " + alias(target)
                            + ("compile".equals(dependency.getScope()) ? "" : " : " + dependency.getScope()));
                    connected.add(module.getId());
                    connected.add(target);
                }
            }
        }
        // Smetana's layout crashes on graphs mixing connected and unconnected nodes, so modules without
        // dependencies either way go into the legend (unless no module has any).
        List<ModuleDocumentation> shown = edges.isEmpty() ? site.getModules()
                : site.getModules().stream().filter(m -> connected.contains(m.getId())).toList();
        List<String> unconnected = site.getModules().stream().filter(m -> !shown.contains(m)).map(DiagramGenerator::name).toList();

        StringBuilder puml = new StringBuilder("@startuml\n").append(STYLE);
        for (ModuleDocumentation module : shown) {
            puml.append("component \"").append(name(module)).append("\\n<size:10>").append(module.getProject().getPackaging())
                    .append(" · ").append(module.getStatistics().getTypes()).append(" types</size>\" as ").append(alias(module.getId())).append('\n');
        }
        edges.forEach(edge -> puml.append(edge).append('\n'));
        if (!unconnected.isEmpty()) {
            puml.append("legend bottom left\nNo dependencies on other modules: ").append(String.join(", ", unconnected)).append("\nendlegend\n");
        }
        puml.append("@enduml\n");
        return Optional.of(render(DiagramStructure.builder()
                .id("modules")
                .kind(DiagramStructure.Kind.MODULE_DEPENDENCIES)
                .title("Module dependencies")
                .elementCount(site.getModules().size())
                .build(), puml.toString()));
    }

    // ----------------------------------------------------- spring diagram

    private Diagram springDiagram(SpringStructure spring) {
        StringBuilder puml = new StringBuilder("@startuml\n").append(STYLE);
        List<BeanDefinition> beans = spring.getBeans().stream().limit(MAX_ELEMENTS).toList();
        Set<String> shown = beans.stream().map(BeanDefinition::getName).collect(Collectors.toSet());
        Map<String, List<BeanDefinition>> byKind = new TreeMap<>();
        beans.forEach(b -> byKind.computeIfAbsent(b.getKind().name(), k -> new ArrayList<>()).add(b));
        byKind.forEach((kind, group) -> {
            puml.append("package \"").append(kind.toLowerCase(Locale.ROOT).replace('_', ' ')).append("\" {\n");
            group.forEach(b -> puml.append("  component \"").append(b.getName()).append("\\n<size:10>").append(simple(b.getTypeName()))
                    .append("</size>\" as ").append(alias("bean:" + b.getName())).append(b.isInComponentScan() ? "" : " #fde8e8").append('\n'));
            puml.append("}\n");
        });
        for (BeanDefinition bean : beans) {
            for (InjectionPoint point : bean.getDependencies()) {
                if (point.getResolvedTo() != null && shown.contains(point.getResolvedTo())) {
                    puml.append(alias("bean:" + bean.getName())).append(point.getKind() == InjectionPoint.Kind.FIELD ? " ..> " : " --> ")
                            .append(alias("bean:" + point.getResolvedTo())).append('\n');
                } else if (point.getResolvedTo() == null && !point.isCollection() && point.getCandidates().size() > 1) {
                    puml.append("note right of ").append(alias("bean:" + bean.getName())).append(" #fff4d6 : ").append(point.getName())
                            .append(": ambiguous ").append(simple(point.getTypeName())).append('\n');
                }
            }
        }
        puml.append("@enduml\n");
        return render(DiagramStructure.builder()
                .id("spring-beans")
                .kind(DiagramStructure.Kind.SPRING_BEANS)
                .title("Spring bean dependencies")
                .elementCount(beans.size())
                .build(), puml.toString());
    }

    private static Map<String, String> stereotypes(SpringStructure spring) {
        Map<String, String> result = new HashMap<>();
        if (spring == null) return result;
        for (BeanDefinition bean : spring.getBeans()) {
            if (bean.getDeclaringMember() == null) {
                String kind = bean.getKind().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                result.put(bean.getTypeName(), Character.toUpperCase(kind.charAt(0)) + kind.substring(1));
            }
        }
        return result;
    }

    // ------------------------------------------------------------ helpers

    private Diagram render(DiagramStructure structure, String puml) {
        structure.setPumlPath("diagrams/" + structure.getId() + ".puml");
        byte[] svg = null;
        if (render) {
            // PlantUML prints its own stack traces when a layout crashes; keep them out of the build log.
            java.io.PrintStream stderr = System.err;
            try {
                System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream()));
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                new SourceStringReader(puml, StandardCharsets.UTF_8).outputImage(out, new FileFormatOption(FileFormat.SVG));
                String text = out.toString(StandardCharsets.UTF_8);
                // On a crash PlantUML still returns an SVG: a picture of the error.
                boolean failed = !text.contains("<svg") || text.contains(">Syntax Error?<") || text.contains("has crashed")
                        || text.contains("An error has occured") || text.contains("An error has occurred");
                if (!failed) {
                    svg = out.toByteArray();
                    structure.setSvgPath("diagrams/" + structure.getId() + ".svg");
                } else {
                    log.accept("PlantUML could not lay out \"" + structure.getTitle() + "\"; the site offers its source instead ("
                            + structure.getPumlPath() + ")");
                }
            } catch (IOException | RuntimeException | StackOverflowError e) {
                log.accept("PlantUML failed on \"" + structure.getTitle() + "\": " + e);
            } finally {
                System.setErr(stderr);
            }
        }
        return new Diagram(structure, puml, svg);
    }

    private static String name(ModuleDocumentation module) {
        return Optional.ofNullable(module.getProject().getName()).orElse(module.getProject().getArtifactId()).replace("\"", "'");
    }

    private static String displayName(TypeStructure type) {
        String qualified = type.getQualifiedName();
        String[] parts = qualified.split("\\.");
        int first = 0;
        while (first < parts.length - 1 && !parts[first].isEmpty() && Character.isLowerCase(parts[first].charAt(0))) first++;
        return String.join(".", Arrays.copyOfRange(parts, first, parts.length));
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** PlantUML-safe identifier. */
    static String alias(String name) {
        return "e_" + name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    static String slug(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
