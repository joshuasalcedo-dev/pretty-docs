package io.joshuasalcedo.pretty.maven;

import io.joshuasalcedo.pretty.maven.model.*;
import org.apache.maven.model.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Maven project metadata for documentation.
 * <p>
 * {@link #read(Path)} parses raw {@code pom.xml} files without running Maven: groupId/version and
 * properties are inherited from parents that are part of the same module tree, and {@code ${...}}
 * placeholders are interpolated from those properties. Parents outside the tree (e.g. a published
 * parent POM) are not downloaded. Inside a Maven build, use {@link #fromModel} with the effective
 * model instead, which is complete.
 */
public final class MavenParser {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    private MavenParser() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Read a project and, recursively, the modules it declares.
     *
     * @param pomOrDirectory A {@code pom.xml} file or a directory containing one
     * @return The project with {@code children} filled in
     * @throws IOException if a POM cannot be read or parsed
     */
    public static MavenProjectStructure read(Path pomOrDirectory) throws IOException {
        return read(pomFile(pomOrDirectory), null, Map.of(), new HashSet<>());
    }

    /**
     * Convert an already-built Maven model (such as {@code MavenProject.getModel()}) without reading modules.
     *
     * @param model   The model, ideally the effective (interpolated, inherited) one
     * @param basedir The project directory
     * @return The project with empty {@code children}
     */
    public static MavenProjectStructure fromModel(Model model, Path basedir) {
        return convert(model, basedir, UnaryOperator.identity());
    }

    private static MavenProjectStructure read(Path pom, MavenProjectStructure parent, Map<String, String> inheritedProperties,
                                              Set<Path> visited) throws IOException {
        Path normalized = pom.toAbsolutePath().normalize();
        if (!visited.add(normalized)) {
            throw new IOException("Module cycle detected at " + normalized);
        }
        Model model = parse(normalized);
        Path basedir = normalized.getParent();

        // Inherit coordinates the way Maven does, but only from parents inside this tree.
        boolean parentInTree = parent != null && model.getParent() != null
                && Objects.equals(model.getParent().getArtifactId(), parent.getArtifactId());
        if (model.getGroupId() == null && model.getParent() != null) {
            model.setGroupId(model.getParent().getGroupId());
        }
        if (model.getVersion() == null && model.getParent() != null) {
            model.setVersion(model.getParent().getVersion());
        }

        Map<String, String> properties = new LinkedHashMap<>(parentInTree ? inheritedProperties : Map.of());
        model.getProperties().forEach((k, v) -> properties.put(k.toString(), v.toString()));
        properties.put("project.groupId", String.valueOf(model.getGroupId()));
        properties.put("project.artifactId", model.getArtifactId());
        properties.put("project.version", String.valueOf(model.getVersion()));
        properties.put("project.basedir", basedir.toString());
        properties.put("basedir", basedir.toString());
        if (model.getParent() != null) {
            properties.put("project.parent.version", String.valueOf(model.getParent().getVersion()));
            properties.put("project.parent.groupId", String.valueOf(model.getParent().getGroupId()));
        }

        MavenProjectStructure project = convert(model, basedir, value -> interpolate(value, properties));
        project.setChildren(new ArrayList<>());
        for (String module : model.getModules()) {
            Path modulePom = pomFile(basedir.resolve(module));
            project.getChildren().add(read(modulePom, project, properties, visited));
        }
        return project;
    }

    private static Model parse(Path pom) throws IOException {
        try (Reader reader = Files.newBufferedReader(pom)) {
            return new MavenXpp3Reader().read(reader, false);
        } catch (XmlPullParserException e) {
            throw new IOException("Invalid POM " + pom + ": " + e.getMessage(), e);
        }
    }

    private static Path pomFile(Path pomOrDirectory) throws IOException {
        Path pom = Files.isDirectory(pomOrDirectory) ? pomOrDirectory.resolve("pom.xml") : pomOrDirectory;
        if (!Files.isRegularFile(pom)) {
            throw new IOException("No pom.xml at " + pomOrDirectory);
        }
        return pom;
    }

    private static MavenProjectStructure convert(Model model, Path basedir, UnaryOperator<String> value) {
        Parent parent = model.getParent();
        Organization organization = model.getOrganization();
        Scm scm = model.getScm();

        Map<String, String> properties = new TreeMap<>();
        model.getProperties().forEach((k, v) -> properties.put(k.toString(), value.apply(v.toString())));

        List<DependencyStructure> managed = new ArrayList<>();
        if (model.getDependencyManagement() != null) {
            model.getDependencyManagement().getDependencies().forEach(d -> managed.add(dependency(d, value)));
        }

        List<PluginStructure> plugins = new ArrayList<>();
        if (model.getBuild() != null) {
            for (Plugin plugin : model.getBuild().getPlugins()) {
                Set<String> goals = new LinkedHashSet<>();
                plugin.getExecutions().forEach(execution -> goals.addAll(execution.getGoals()));
                plugins.add(PluginStructure.builder()
                        .groupId(plugin.getGroupId())
                        .artifactId(plugin.getArtifactId())
                        .version(value.apply(plugin.getVersion()))
                        .goals(new ArrayList<>(goals))
                        .build());
            }
        }

        return MavenProjectStructure.builder()
                .groupId(value.apply(model.getGroupId()))
                .artifactId(model.getArtifactId())
                .version(value.apply(model.getVersion()))
                .packaging(model.getPackaging())
                .name(value.apply(model.getName()))
                .description(value.apply(trim(model.getDescription())))
                .url(value.apply(model.getUrl()))
                .inceptionYear(model.getInceptionYear())
                .organizationName(organization == null ? null : organization.getName())
                .organizationUrl(organization == null ? null : organization.getUrl())
                .parent(parent == null ? null : MavenCoordinates.builder()
                        .groupId(parent.getGroupId())
                        .artifactId(parent.getArtifactId())
                        .version(value.apply(parent.getVersion()))
                        .build())
                .licenses(model.getLicenses().stream()
                        .map(l -> LicenseStructure.builder().name(l.getName()).url(l.getUrl()).build())
                        .toList())
                .developers(model.getDevelopers().stream()
                        .map(d -> DeveloperStructure.builder()
                                .id(d.getId())
                                .name(d.getName())
                                .email(d.getEmail())
                                .url(d.getUrl())
                                .organization(d.getOrganization())
                                .roles(List.copyOf(d.getRoles()))
                                .build())
                        .toList())
                .scm(scm == null ? null : ScmStructure.builder()
                        .url(value.apply(scm.getUrl()))
                        .connection(value.apply(scm.getConnection()))
                        .developerConnection(value.apply(scm.getDeveloperConnection()))
                        .tag(scm.getTag())
                        .build())
                .issueManagementUrl(model.getIssueManagement() == null ? null : model.getIssueManagement().getUrl())
                .ciManagementUrl(model.getCiManagement() == null ? null : model.getCiManagement().getUrl())
                .properties(properties)
                .dependencies(model.getDependencies().stream().map(d -> dependency(d, value)).toList())
                .managedDependencies(managed)
                .plugins(plugins)
                .modules(List.copyOf(model.getModules()))
                .basedir(basedir.toAbsolutePath().normalize().toString())
                .children(new ArrayList<>())
                .build();
    }

    private static DependencyStructure dependency(Dependency d, UnaryOperator<String> value) {
        return DependencyStructure.builder()
                .groupId(value.apply(d.getGroupId()))
                .artifactId(value.apply(d.getArtifactId()))
                .version(value.apply(d.getVersion()))
                .scope(d.getScope() == null ? "compile" : d.getScope())
                .type(d.getType())
                .classifier(d.getClassifier())
                .optional(d.isOptional())
                .build();
    }

    /** Replaces {@code ${name}} placeholders, repeatedly so properties may refer to other properties. */
    static String interpolate(String value, Map<String, String> properties) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (int pass = 0; pass < 10 && result.contains("${"); pass++) {
            Matcher matcher = PLACEHOLDER.matcher(result);
            StringBuilder next = new StringBuilder();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                changed |= replacement != null;
                matcher.appendReplacement(next, Matcher.quoteReplacement(replacement != null ? replacement : matcher.group()));
            }
            matcher.appendTail(next);
            result = next.toString();
            if (!changed) {
                break;
            }
        }
        return result;
    }

    private static String trim(String value) {
        return value == null ? null : value.strip().replaceAll("\\s*\\R\\s*", " ");
    }
}
