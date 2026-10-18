package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.JavaParser;
import io.joshuasalcedo.pretty.maven.MavenParser;
import io.joshuasalcedo.pretty.plugin.model.DocumentationSite;
import io.joshuasalcedo.pretty.plugin.model.ExternalDocumentation;
import io.joshuasalcedo.pretty.plugin.model.ModuleDocumentation;
import io.joshuasalcedo.pretty.quality.model.QualityFinding;
import io.joshuasalcedo.pretty.quality.model.QualityStructure;
import io.joshuasalcedo.pretty.spring.model.BeanIssue;
import io.joshuasalcedo.pretty.spring.model.SpringStructure;
import org.apache.maven.execution.MavenSession;
import io.joshuasalcedo.pretty.maven.model.DependencyStructure;
import io.joshuasalcedo.pretty.maven.model.MavenProjectStructure;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the documentation site for every module in the current build.
 * <p>
 * Runs once per build (aggregator): from the root of a multi-module project it documents the whole
 * module tree; from inside a module (or with {@code -pl}) it documents just the modules Maven
 * selected. Nothing is compiled, so it also works on projects that do not build.
 * <pre>
 * mvn io.joshuasalcedo.pretty:pretty-docs-maven-plugin:generate
 * </pre>
 */
@Mojo(name = "generate", aggregator = true, threadSafe = true, requiresDependencyResolution = ResolutionScope.NONE)
public class GenerateMojo extends AbstractMojo {

    private static final Pattern FEATURE_VERSION = Pattern.compile("^(?:1\\.)?(\\d+)");

    @Parameter(defaultValue = "${session}", readonly = true, required = true)
    private MavenSession session;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor plugin;

    @Component
    private ProjectDependenciesResolver dependenciesResolver;

    /** Where the site is written. Replaced on each run. */
    @Parameter(property = "pretty-docs.outputDirectory", defaultValue = "${project.build.directory}/pretty-docs")
    private File outputDirectory;

    /** Lowest access level to document: public, protected, package or private. */
    @Parameter(property = "pretty-docs.visibility", defaultValue = "protected")
    private String visibility;

    /**
     * {@code resolved}: also run the JDK's javadoc engine against the dependency classpath for exact
     * type names, {@code {@inheritDoc}} and inherited members (falls back to {@code source} when it
     * cannot run). {@code source}: parse sources only; no dependency resolution.
     */
    @Parameter(property = "pretty-docs.mode", defaultValue = "resolved")
    private String mode;

    /** Include method and constructor source in the site (for "view source"). */
    @Parameter(property = "pretty-docs.includeMethodBodies", defaultValue = "true")
    private boolean includeMethodBodies;

    /** Also document test sources. */
    @Parameter(property = "pretty-docs.includeTestSources", defaultValue = "false")
    private boolean includeTestSources;

    /** Site title; defaults to the root project's name. */
    @Parameter(property = "pretty-docs.title", defaultValue = "${project.name}")
    private String title;

    /** artifactIds of modules to leave out. */
    @Parameter(property = "pretty-docs.excludeModules")
    private List<String> excludeModules = new ArrayList<>();

    /** Link JDK types to the Java SE API docs (version from maven.compiler.release/target, else the running JDK). */
    @Parameter(property = "pretty-docs.linkJdk", defaultValue = "true")
    private boolean linkJdk;

    /** Link dependency types to their javadoc on javadoc.io. */
    @Parameter(property = "pretty-docs.linkDependencies", defaultValue = "true")
    private boolean linkDependencies;

    /** Extra javadoc sites to link to (root URLs, like {@code javadoc -link}); checked before the JDK and dependencies. */
    @Parameter
    private List<String> links = new ArrayList<>();

    /** Port used in the "how to view" instructions; the serve goal reads the same property. */
    @Parameter(property = "pretty-docs.port", defaultValue = "" + StaticSiteServer.DEFAULT_PORT)
    private int port;

    /** Generate PlantUML diagrams (class diagram per package, module and Spring bean graphs). */
    @Parameter(property = "pretty-docs.diagrams", defaultValue = "true")
    private boolean diagrams;

    /** Render the diagrams to SVG (pure Java, no Graphviz); false writes only the .puml sources. */
    @Parameter(property = "pretty-docs.renderDiagrams", defaultValue = "true")
    private boolean renderDiagrams;

    /**
     * Document TypeScript projects (directories with a tsconfig.json) found in the modules' directories.
     * Uses the TypeScript compiler bundled with the plugin, run by {@code node} from the PATH; without
     * node, TypeScript is skipped with a message. Off by default: turn it on with {@code <typescript>true</typescript>}
     * or {@code -Dpretty-docs.typescript=true}.
     */
    @Parameter(property = "pretty-docs.typescript", defaultValue = "false")
    private boolean typescript;

    /** Directories to leave out of the TypeScript search only, relative to the root module (e.g. legacy); see also ignores. */
    @Parameter(property = "pretty-docs.typescriptExcludes")
    private List<String> typescriptExcludes = new ArrayList<>();

    /**
     * The TypeScript projects to document (directories with a tsconfig.json, relative to the root
     * module), instead of every one found. For example:
     * <pre>
     * &lt;typescriptProjects&gt;
     *   &lt;project&gt;applications/landing&lt;/project&gt;
     *   &lt;project&gt;swaswa-lib/ts/ui&lt;/project&gt;
     * &lt;/typescriptProjects&gt;
     * </pre>
     */
    @Parameter(property = "pretty-docs.typescriptProjects")
    private List<String> typescriptProjects = new ArrayList<>();

    /** Group TypeScript packages under their npm, yarn, pnpm or bun workspace; false lists each package on its own. */
    @Parameter(property = "pretty-docs.typescriptWorkspaces", defaultValue = "true")
    private boolean typescriptWorkspaces;

    /** Report the TypeScript compiler's errors on the Quality page (with quality on). */
    @Parameter(property = "pretty-docs.typescriptTypeCheck", defaultValue = "true")
    private boolean typescriptTypeCheck;

    /** Document TypeScript test files (*.test.ts, *.spec.ts, __tests__). By default as includeTestSources. */
    @Parameter(property = "pretty-docs.typescriptIncludeTests")
    private Boolean typescriptIncludeTests;

    /** The node executable to run the TypeScript analysis with, e.g. C:/tools/node/node.exe. By default node from the PATH. */
    @Parameter(property = "pretty-docs.node")
    private String node;

    /** Code quality findings in the site: PMD, SpotBugs and TypeScript compiler errors (each can be turned off below). */
    @Parameter(property = "pretty-docs.quality", defaultValue = "true")
    private boolean quality;

    /** Run PMD on the Java sources (likely bugs, risky concurrency and resource handling, performance, security). */
    @Parameter(property = "pretty-docs.pmd", defaultValue = "true")
    private boolean pmd;

    /** Run SpotBugs on the classes the last build compiled; modules without target/classes are skipped. */
    @Parameter(property = "pretty-docs.spotbugs", defaultValue = "true")
    private boolean spotbugs;

    /**
     * Directories to leave out entirely: Maven modules, TypeScript packages and assets inside them.
     * Relative to the root module; a single name matches anywhere; globs work. For example:
     * <pre>
     * &lt;ignores&gt;
     *   &lt;ignore&gt;legacy&lt;/ignore&gt;
     *   &lt;ignore&gt;frameworks/tauri/examples&lt;/ignore&gt;
     *   &lt;ignore&gt;fixtures&lt;/ignore&gt;
     * &lt;/ignores&gt;
     * </pre>
     * On the command line: {@code -Dpretty-docs.ignores=legacy,fixtures}.
     */
    @Parameter(property = "pretty-docs.ignores")
    private List<String> ignores = new ArrayList<>();

    /** Also leave out whatever the repository's .gitignore files ignore. */
    @Parameter(property = "pretty-docs.gitignore", defaultValue = "true")
    private boolean gitignore;

    /**
     * Logo for the site header, relative to the root module. By default a logo.svg/png in the root,
     * docs/, .github/, assets/ or public/, else the most logo-like image among the modules' assets.
     */
    @Parameter(property = "pretty-docs.logo")
    private String logo;

    /** Link to the source repository in the site header. By default the POM's scm url, else the git remote "origin". */
    @Parameter(property = "pretty-docs.repositoryUrl")
    private String repositoryUrl;

    /** Show the images in the modules' directories (icons, logos, public/ and static/ files) on an Assets page. */
    @Parameter(property = "pretty-docs.assets", defaultValue = "true")
    private boolean assets;

    @Parameter(property = "pretty-docs.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("Skipping pretty-docs");
            return;
        }
        Visibility level;
        try {
            level = Visibility.parse(visibility);
        } catch (IllegalArgumentException e) {
            throw new MojoFailureException(e.getMessage());
        }
        boolean resolvedMode = switch (mode.trim().toLowerCase(Locale.ROOT)) {
            case "resolved" -> true;
            case "source" -> false;
            default -> throw new MojoFailureException("mode must be 'resolved' or 'source' but was: " + mode);
        };

        Set<String> reactorIds = new HashSet<>();
        session.getProjects().forEach(p -> reactorIds.add(p.getGroupId() + ":" + p.getArtifactId()));
        List<MavenProject> projects = new ArrayList<>();
        List<DocumentationGenerator.ModuleInput> modules = new ArrayList<>();
        Ignores ignored = new Ignores(project.getBasedir().toPath(), ignores, gitignore);
        for (MavenProject module : session.getProjects()) {
            if (excludeModules.contains(module.getArtifactId())) {
                getLog().debug("Excluding " + module.getArtifactId());
                continue;
            }
            if (module != project && ignored.test(module.getBasedir().toPath())) {
                getLog().debug("Ignoring " + module.getArtifactId() + " (in <ignores> or .gitignore)");
                continue;
            }
            projects.add(module);
            modules.add(new DocumentationGenerator.ModuleInput(
                    module.getGroupId() + ":" + module.getArtifactId(),
                    module.getBasedir().toPath(),
                    describe(module),
                    paths(module.getCompileSourceRoots()),
                    includeTestSources ? paths(module.getTestCompileSourceRoots()) : List.of(),
                    module.getResources().stream().map(r -> Path.of(r.getDirectory())).toList(),
                    module.getDependencies().stream()
                            .filter(d -> !"test".equals(d.getScope()))
                            .map(d -> d.getGroupId() + ":" + d.getArtifactId())
                            .filter(reactorIds::contains)
                            .distinct().toList(),
                    Path.of(module.getBuild().getOutputDirectory())));
        }

        Map<String, String> dependencies = declaredDependencies(projects);
        DocumentationGenerator.ResolveOptions resolve = null;
        if (resolvedMode) {
            ReactorClasspath.Result classpath = ReactorClasspath.resolve(dependenciesResolver, session, projects, includeTestSources, getLog());
            classpath.dependencies().forEach(dependencies::putIfAbsent);
            resolve = new DocumentationGenerator.ResolveOptions(classpath.classpath(), encoding());
            getLog().debug("Resolver classpath: " + classpath.classpath());
        }

        try {
            Path cacheRoot = session.getRepositorySession().getLocalRepository().getBasedir().toPath().resolve(".cache/pretty-docs");
            DocumentationSite site = new DocumentationGenerator(level, includeMethodBodies,
                    plugin.getArtifactId() + " " + plugin.getVersion())
                    .withTypeScript(new TypeScriptModules.Options(typescript, typescriptExcludes, cacheRoot.resolve("typescript"),
                            typescriptIncludeTests == null ? includeTestSources : typescriptIncludeTests,
                            typescriptProjects, typescriptWorkspaces, typescriptTypeCheck, node))
                    .withProject(new DocumentationGenerator.ProjectOptions(ignores, gitignore, logo, repositoryUrl, assets))
                    .withQuality(quality ? new DocumentationGenerator.QualityOptions(pmd, spotbugs, typescript) : null)
                    .generate(title, project.getBasedir().toPath(), modules, resolve, getLog()::warn);
            if (site.getQuality() != null) {
                logQuality(site.getQuality());
            }
            long typescriptModules = site.getModules().stream().filter(m -> "typescript".equals(m.getLanguage()) && !m.getFiles().isEmpty()).count();
            if (typescriptModules > 0) {
                getLog().info("TypeScript: " + typescriptModules + " project(s), "
                        + site.getModules().stream().filter(m -> "typescript".equals(m.getLanguage()) && !m.getFiles().isEmpty())
                        .mapToInt(m -> m.getStatistics().getFiles()).sum() + " file(s)");
            }
            if (resolvedMode && !"resolved".equals(site.getMode())) {
                getLog().warn("Resolved mode was not available; documented from sources only");
            }
            site.setExternalDocs(externalDocs(site, dependencies));
            if (site.getSpring() != null) {
                logSpring(site.getSpring());
            }

            SiteWriter writer = new SiteWriter();
            if (!writer.hasTemplate()) {
                getLog().warn("Site template not found on the plugin classpath; writing data files only");
            }
            List<DiagramGenerator.Diagram> generated = List.of();
            if (diagrams) {
                long start = System.nanoTime();
                generated = new DiagramGenerator(renderDiagrams, getLog()::warn).generate(site);
                site.setDiagrams(generated.stream().map(DiagramGenerator.Diagram::structure).toList());
                getLog().info("Diagrams: " + generated.size() + " PlantUML diagram(s)" + (renderDiagrams ? " rendered" : " written")
                        + " in " + (System.nanoTime() - start) / 1_000_000 + " ms");
            }
            Path index = writer.write(site, outputDirectory.toPath(), generated);

            int types = site.getModules().stream().mapToInt(m -> m.getStatistics().getTypes()).sum();
            getLog().info("Documented " + types + " types in " + site.getModules().size() + " module(s) ("
                    + site.getMode() + " mode, " + site.getExternalDocs().size() + " external javadoc site(s) linked)");
            for (ModuleDocumentation module : site.getModules()) {
                getLog().debug("  " + module.getId() + ": " + module.getStatistics());
            }
            logHowToView(index.getParent());
        } catch (IOException e) {
            throw new MojoExecutionException("Could not generate documentation: " + e.getMessage(), e);
        }
    }

    private List<ExternalDocumentation> externalDocs(DocumentationSite site, Map<String, String> dependencies) {
        List<ExternalDocsLinker.Candidate> candidates = new ArrayList<>();
        links.forEach(url -> candidates.add(new ExternalDocsLinker.Candidate(url, url)));
        if (linkJdk) {
            candidates.add(ExternalDocsLinker.jdk(jdkFeatureVersion()));
        }
        if (linkDependencies) {
            dependencies.forEach((key, version) -> {
                if (version != null && !version.endsWith("-SNAPSHOT")) {
                    String[] ga = key.split(":");
                    candidates.add(ExternalDocsLinker.javadocIo(ga[0], ga[1], version));
                }
            });
        }
        Path cache = session.getRepositorySession().getLocalRepository().getBasedir().toPath()
                .resolve(".cache/pretty-docs/element-lists");
        ExternalDocsLinker linker = new ExternalDocsLinker(cache, session.isOffline(), getLog()::debug);
        return linker.link(candidates, ExternalDocsLinker.referencedTypes(JavaParser.toJson(site, false)));
    }

    /**
     * The effective model, minus "about" metadata (organization, licenses, developers, URLs, description)
     * that is only inherited from a parent outside this build, such as spring-boot-starter-parent:
     * that describes the parent's publisher, not this project.
     */
    private MavenProjectStructure describe(MavenProject module) {
        MavenProjectStructure structure = MavenParser.fromModel(module.getModel(), module.getBasedir().toPath());
        List<Model> chain = new ArrayList<>();
        for (MavenProject p = module; p != null && session.getProjects().contains(p); p = p.getParent()) {
            chain.add(p.getOriginalModel());
        }
        if (chain.stream().noneMatch(m -> m.getOrganization() != null)) {
            structure.setOrganizationName(null);
            structure.setOrganizationUrl(null);
        }
        if (chain.stream().allMatch(m -> m.getLicenses().isEmpty())) {
            structure.setLicenses(List.of());
        }
        if (chain.stream().allMatch(m -> m.getDevelopers().isEmpty())) {
            structure.setDevelopers(List.of());
        }
        if (chain.stream().noneMatch(m -> m.getUrl() != null)) {
            structure.setUrl(null);
        }
        if (chain.stream().noneMatch(m -> m.getScm() != null)) {
            structure.setScm(null);
        }
        if (chain.stream().noneMatch(m -> m.getIssueManagement() != null)) {
            structure.setIssueManagementUrl(null);
        }
        if (chain.stream().noneMatch(m -> m.getCiManagement() != null)) {
            structure.setCiManagementUrl(null);
        }
        if (chain.stream().noneMatch(m -> m.getDescription() != null)) {
            structure.setDescription(null);
        }
        if (chain.stream().noneMatch(m -> m.getInceptionYear() != null)) {
            structure.setInceptionYear(null);
        }
        structure.setManagedDependencies(ownManagedDependencies(module));
        return structure;
    }

    /**
     * The dependency management this module's own POM declares. The effective model would repeat
     * everything inherited and every imported BOM (Spring Boot's alone has ~1,300 entries) in every
     * module; imported BOMs are listed as one entry each instead.
     */
    private static List<DependencyStructure> ownManagedDependencies(MavenProject module) {
        DependencyManagement declared = module.getOriginalModel().getDependencyManagement();
        if (declared == null) {
            return List.of();
        }
        List<DependencyStructure> effective = module.getDependencyManagement() == null ? List.of()
                : MavenParser.fromModel(module.getModel(), module.getBasedir().toPath()).getManagedDependencies();
        List<DependencyStructure> result = new ArrayList<>();
        for (Dependency dependency : declared.getDependencies()) {
            String groupId = interpolate(dependency.getGroupId(), module);
            String artifactId = interpolate(dependency.getArtifactId(), module);
            if ("import".equals(dependency.getScope())) {
                result.add(DependencyStructure.builder().groupId(groupId).artifactId(artifactId)
                        .version(interpolate(dependency.getVersion(), module)).scope("import").type("pom").build());
                continue;
            }
            effective.stream()
                    .filter(d -> d.getGroupId().equals(groupId) && d.getArtifactId().equals(artifactId))
                    .findFirst().ifPresent(result::add);
        }
        return result;
    }

    private static String interpolate(String value, MavenProject module) {
        if (value == null || !value.contains("${")) {
            return value;
        }
        String result = value.replace("${project.groupId}", module.getGroupId()).replace("${project.version}", module.getVersion())
                .replace("${project.artifactId}", module.getArtifactId());
        Matcher matcher = Pattern.compile("\\$\\{([^}]+)}").matcher(result);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String property = module.getProperties().getProperty(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(property != null ? property : matcher.group()));
        }
        return matcher.appendTail(out).toString();
    }

    /** groupId:artifactId -> version of the jar dependencies the modules declare (versions are already managed). */
    private static Map<String, String> declaredDependencies(List<MavenProject> projects) {
        Set<String> reactor = new HashSet<>();
        projects.forEach(p -> reactor.add(p.getGroupId() + ":" + p.getArtifactId()));
        Map<String, String> dependencies = new TreeMap<>();
        for (MavenProject module : projects) {
            for (Dependency dependency : module.getDependencies()) {
                String key = dependency.getGroupId() + ":" + dependency.getArtifactId();
                if (!reactor.contains(key) && "jar".equals(dependency.getType()) && dependency.getClassifier() == null) {
                    dependencies.putIfAbsent(key, dependency.getVersion());
                }
            }
        }
        return dependencies;
    }

    private int jdkFeatureVersion() {
        Properties properties = project.getProperties();
        for (String name : List.of("maven.compiler.release", "maven.compiler.target", "maven.compiler.source", "java.version")) {
            String value = properties.getProperty(name);
            if (value != null) {
                Matcher matcher = FEATURE_VERSION.matcher(value.trim());
                if (matcher.find()) {
                    return Integer.parseInt(matcher.group(1));
                }
            }
        }
        return Runtime.version().feature();
    }

    private Charset encoding() {
        String encoding = project.getProperties().getProperty("project.build.sourceEncoding");
        try {
            return encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
        } catch (IllegalArgumentException e) {
            getLog().warn("Unknown source encoding " + encoding + "; using UTF-8");
            return StandardCharsets.UTF_8;
        }
    }

    /** Bean wiring problems belong in the build log too, not only in the site. */
    private void logSpring(SpringStructure spring) {
        getLog().info("Spring: " + spring.getBeans().size() + " beans, " + spring.getEndpoints().size() + " endpoints, "
                + spring.getConfigurationProperties().size() + " @ConfigurationProperties");
        for (BeanIssue issue : spring.getIssues()) {
            switch (issue.getSeverity()) {
                case ERROR, WARNING -> getLog().warn("[spring] " + issue.getMessage());
                case INFO -> getLog().debug("[spring] " + issue.getMessage());
            }
        }
    }

    /** One line per tool; high-priority findings as warnings (the rest are in the site). */
    private void logQuality(QualityStructure quality) {
        for (QualityStructure.ToolRun tool : quality.getTools()) {
            long high = quality.getFindings().stream()
                    .filter(f -> f.getTool().equals(tool.getName()) && f.getPriority() == QualityFinding.Priority.HIGH).count();
            getLog().info("Quality: " + tool.getName() + (tool.getVersion() != null ? " " + tool.getVersion() : "") + ": "
                    + (tool.isRan() ? tool.getFindings() + " finding(s), " + high + " high priority (" + tool.getNote() + ")" : "skipped, " + tool.getNote()));
        }
        quality.getFindings().stream().filter(f -> f.getPriority() == QualityFinding.Priority.HIGH).limit(50).forEach(f ->
                getLog().warn("[" + f.getTool().toLowerCase(Locale.ROOT) + "] " + f.getFile() + (f.getLine() > 0 ? ":" + f.getLine() : "")
                        + " " + f.getRule() + ": " + f.getMessage()));
    }

    /** Tells the user how to serve the site, with the link to open. */
    private void logHowToView(Path site) {
        String dir = "\"" + site + "\"";
        String serveGoal = plugin.getGroupId() + ":" + plugin.getArtifactId() + ":" + plugin.getVersion() + ":serve";
        getLog().info("");
        getLog().info("Documentation site written to " + site);
        getLog().info("Please run one of these commands to serve the static site:");
        getLog().info("  mvn " + serveGoal + (port != StaticSiteServer.DEFAULT_PORT ? " -Dpretty-docs.port=" + port : ""));
        getLog().info("  npx serve " + dir + " -l " + port);
        getLog().info("  python -m http.server " + port + " -d " + dir);
        getLog().info("Then open: http://localhost:" + port + "/");
        getLog().info("(Or open " + site.resolve("index.html").toUri() + " directly in a browser.)");
    }

    private static List<Path> paths(List<String> roots) {
        return roots.stream().map(Path::of).toList();
    }
}
