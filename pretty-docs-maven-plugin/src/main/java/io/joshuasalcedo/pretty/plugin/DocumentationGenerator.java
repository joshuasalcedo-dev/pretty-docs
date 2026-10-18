package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.JavaParser;
import io.joshuasalcedo.pretty.maven.model.MavenProjectStructure;
import io.joshuasalcedo.pretty.model.JavaFileStructure;
import io.joshuasalcedo.pretty.model.TypeStructure;
import io.joshuasalcedo.pretty.plugin.model.DocumentationSite;
import io.joshuasalcedo.pretty.plugin.model.ModuleDocumentation;
import io.joshuasalcedo.pretty.plugin.model.ModuleStatistics;
import io.joshuasalcedo.pretty.resolve.JavadocResolver;
import io.joshuasalcedo.pretty.resolve.Resolution;
import io.joshuasalcedo.pretty.spring.ProjectLayout;
import io.joshuasalcedo.pretty.quality.QualityAnalyzer;
import io.joshuasalcedo.pretty.quality.model.QualityFinding;
import io.joshuasalcedo.pretty.quality.model.QualityStructure;
import io.joshuasalcedo.pretty.spring.SpringAnalyzer;
import io.joshuasalcedo.pretty.ts.TypeScriptParser;
import io.joshuasalcedo.pretty.spring.model.SpringStructure;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * Builds a {@link DocumentationSite} from a set of modules. Independent of the Maven runtime so it
 * can be driven by the Mojo or by tests.
 */
public final class DocumentationGenerator {

    /**
     * One module to document.
     *
     * @param id              groupId:artifactId
     * @param basedir         module directory
     * @param project         POM metadata
     * @param sourceRoots     main source directories (missing ones are ignored)
     * @param testSourceRoots test source directories, empty to leave tests out
     * @param resourceDirs    main resource directories (configuration files, auto-configuration imports)
     * @param dependsOn       ids of the build modules it depends on
     * @param classesDir      compiled classes of the last build (for SpotBugs); may not exist
     */
    public record ModuleInput(String id, Path basedir, MavenProjectStructure project,
                              List<Path> sourceRoots, List<Path> testSourceRoots,
                              List<Path> resourceDirs, List<String> dependsOn, Path classesDir) {

        public ModuleInput(String id, Path basedir, MavenProjectStructure project, List<Path> sourceRoots, List<Path> testSourceRoots) {
            this(id, basedir, project, sourceRoots, testSourceRoots, List.of(basedir.resolve("src/main/resources")), List.of(),
                    basedir.resolve("target/classes"));
        }
    }

    /**
     * Code quality checks to run.
     *
     * @param pmd        PMD on Java sources
     * @param spotbugs   SpotBugs on the classes of the last build, where they exist
     * @param typescript the TypeScript compiler's errors (with TypeScript documentation on)
     */
    public record QualityOptions(boolean pmd, boolean spotbugs, boolean typescript) {
    }

    /**
     * Turns on resolved mode: the javadoc engine fills in qualified names, inherited docs and inherited members.
     *
     * @param classpath external dependencies (jars, class directories); modules of the build are read from source
     * @param encoding  source encoding
     */
    public record ResolveOptions(List<Path> classpath, Charset encoding) {
    }

    private final Visibility visibility;
    private final SourceFilter filter;
    private final boolean includeMethodBodies;
    private final String generator;
    private TypeScriptModules.Options typescript; // null: TypeScript is left out
    private QualityOptions quality; // null: no code quality checks
    private ProjectOptions project = new ProjectOptions(List.of(), false, null, null, true);

    /**
     * @param ignores       directories to leave out (see {@link Ignores})
     * @param gitignore     also leave out what .gitignore files ignore
     * @param logo          image for the site header, relative to the root module; null to look for one
     * @param repositoryUrl source repository link; null to take it from the POM's scm or the git remote
     * @param assets        collect the modules' images (icons, logos, ...) into the site
     */
    public record ProjectOptions(List<String> ignores, boolean gitignore, String logo, String repositoryUrl, boolean assets) {
    }

    DocumentationGenerator withProject(ProjectOptions options) {
        this.project = options;
        return this;
    }

    public DocumentationGenerator(Visibility visibility, boolean includeMethodBodies, String generator) {
        this.visibility = visibility;
        this.filter = new SourceFilter(visibility, includeMethodBodies);
        this.includeMethodBodies = includeMethodBodies;
        this.generator = generator;
    }

    /** Also documents the TypeScript projects inside the modules' directories (needs node on the PATH). */
    DocumentationGenerator withTypeScript(TypeScriptModules.Options options) {
        this.typescript = options;
        return this;
    }

    /** Also runs code quality checks; their findings go into the site. */
    DocumentationGenerator withQuality(QualityOptions options) {
        this.quality = options;
        return this;
    }

    /**
     * @param title   Site title
     * @param rootDir Directory of the root (execution) module; paths in the site are relative to it
     * @param modules Modules in build order, the root module first
     */
    public DocumentationSite generate(String title, Path rootDir, List<ModuleInput> modules) throws IOException {
        return generate(title, rootDir, modules, null, message -> { });
    }

    /**
     * @param resolve null for source-only mode
     * @param log     receives warnings, e.g. symbols the javadoc engine could not resolve
     */
    public DocumentationSite generate(String title, Path rootDir, List<ModuleInput> modules, ResolveOptions resolve,
                                      Consumer<String> log) throws IOException {
        Path root = rootDir.toAbsolutePath().normalize();

        // Parse everything in one pass so usedBy links span modules.
        List<Path> allRoots = new ArrayList<>();
        modules.forEach(m -> {
            allRoots.addAll(m.sourceRoots());
            allRoots.addAll(m.testSourceRoots());
        });
        Map<Path, List<JavaFileStructure>> parsed = JavaParser.parseDirectories(allRoots.stream().distinct().toList());
        boolean resolved = resolve != null && resolve(parsed, resolve, log);
        // Before visibility filtering: private constructors and fields decide how beans are injected.
        ProjectLayout layout = SpringLayoutReader.read(root, modules, resolve == null ? List.of() : resolve.classpath(), log);
        SpringStructure spring = SpringAnalyzer.analyze(parsed.values().stream().flatMap(List::stream).toList(), layout).orElse(null);
        ReadmeRenderer readme = new ReadmeRenderer();
        List<QualityStructure.ToolRun> qualityTools = new ArrayList<>();
        List<QualityFinding> findings = new ArrayList<>();
        if (quality != null && (quality.pmd() || quality.spotbugs())) {
            QualityStructure java = QualityAnalyzer.analyze(
                    modules.stream().map(m -> new QualityAnalyzer.Module(m.id(), m.sourceRoots(), m.classesDir())).toList(),
                    new QualityAnalyzer.Options(quality.pmd(), quality.spotbugs(), resolve == null ? List.of() : resolve.classpath()), log);
            qualityTools.addAll(java.getTools());
            java.getFindings().forEach(finding -> {
                if (finding.getFile() != null && Path.of(finding.getFile()).isAbsolute()) {
                    finding.setFile(relative(root, Path.of(finding.getFile())));
                }
                findings.add(finding);
            });
        }

        List<ModuleDocumentation> documented = new ArrayList<>();
        Map<String, Path> readmeAssets = new LinkedHashMap<>();
        for (ModuleInput module : modules) {
            Optional<ReadmeRenderer.Rendered> rendered = readme.render(module.basedir(), DiagramGenerator.slug(module.id()));
            rendered.ifPresent(r -> readmeAssets.putAll(r.assets()));
            List<JavaFileStructure> sources = gather(module.sourceRoots(), parsed, root);
            int[] lines = lineCounts(sources); // all code counts, whatever its visibility
            List<JavaFileStructure> files = filter.apply(sources);
            List<JavaFileStructure> testFiles = filter.apply(gather(module.testSourceRoots(), parsed, root));
            documented.add(ModuleDocumentation.builder()
                    .id(module.id())
                    .parentId(parentId(module, modules))
                    .language("java")
                    .relativePath(relative(root, module.basedir()))
                    .project(module.project())
                    .files(files)
                    .testFiles(testFiles)
                    .statistics(statistics(files, lines))
                    .readmeHtml(rendered.map(ReadmeRenderer.Rendered::html).orElse(null))
                    .build());
        }
        Ignores ignored = new Ignores(root, project.ignores(), project.gitignore());
        if (typescript != null && typescript.enabled()) {
            List<String> typescriptIgnores = new ArrayList<>(project.ignores());
            typescriptIgnores.addAll(typescript.excludes());
            documented.addAll(typescriptModules(root, modules, new Ignores(root, typescriptIgnores, project.gitignore()),
                    readme, readmeAssets, findings, qualityTools, log));
        }
        if (project.assets()) {
            Map<String, Path> dirs = new LinkedHashMap<>();
            documented.forEach(m -> dirs.put(m.getId(), root.resolve(m.getRelativePath())));
            ModuleAssets.Result assets = ModuleAssets.collect(dirs, ignored, log);
            documented.forEach(m -> m.setAssets(assets.byModule().getOrDefault(m.getId(), List.of())));
            readmeAssets.putAll(assets.copy());
        }
        String logo = SiteBranding.logo(root, project.logo(), documented, log).map(source -> {
            String name = source.getFileName().toString();
            String sitePath = "logo" + name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
            readmeAssets.put(sitePath, source);
            return sitePath;
        }).orElse(null);
        String repositoryUrl = SiteBranding.repositoryUrl(root, project.repositoryUrl(),
                modules.isEmpty() ? null : modules.getFirst().project()).orElse(null);
        findings.sort(Comparator.comparing(QualityFinding::getPriority)
                .thenComparing(f -> String.valueOf(f.getFile())).thenComparingInt(QualityFinding::getLine));

        return DocumentationSite.builder()
                .schemaVersion(DocumentationSite.SCHEMA_VERSION)
                .title(title)
                .generator(generator)
                .generatedAt(Instant.now().toString())
                .visibility(visibility.id())
                .mode(resolved ? "resolved" : "source")
                .externalDocs(new ArrayList<>())
                .spring(spring)
                .quality(quality == null ? null : QualityStructure.builder().tools(qualityTools).findings(findings).build())
                .readmeAssets(readmeAssets)
                .logo(logo)
                .repositoryUrl(repositoryUrl)
                .rootModuleId(modules.isEmpty() ? null : modules.getFirst().id())
                .modules(documented)
                .build();
    }

    /**
     * TypeScript projects as modules. They come after the Maven modules and are not part of the
     * Spring analysis (decorators such as Angular's @Component are not Spring stereotypes).
     */
    private List<ModuleDocumentation> typescriptModules(Path root, List<ModuleInput> modules, Ignores ignored, ReadmeRenderer readme,
                                                        Map<String, Path> readmeAssets, List<QualityFinding> findings,
                                                        List<QualityStructure.ToolRun> qualityTools, Consumer<String> log) throws IOException {
        boolean diagnostics = quality != null && quality.typescript() && typescript.typeCheck();
        TypeScriptParser.useNode(typescript.node());
        List<TypeScriptModules.Found> found = TypeScriptModules.discover(root, modules.stream().map(ModuleInput::basedir).toList(), ignored,
                typescript.projects(), typescript.workspaces(), log);
        if (found.isEmpty()) {
            return List.of();
        }
        Files.createDirectories(typescript.workDir());
        Optional<TypeScriptParser.Result> result = TypeScriptParser.parse(
                found.stream().filter(TypeScriptModules.Found::sources)
                        .map(f -> new TypeScriptParser.Project(f.id(), f.dir(), f.project().getArtifactId())).toList(),
                includeMethodBodies, typescript.includeTests(), diagnostics, typescript.workDir(), log);
        if (result.isEmpty()) {
            if (diagnostics) {
                qualityTools.add(QualityStructure.ToolRun.builder().name("TypeScript").ran(false)
                        .note(TypeScriptParser.nodeVersion().isPresent() ? "the analysis failed (see the build log)"
                                : typescript.node() == null || typescript.node().isBlank() ? "node was not found on the PATH"
                                : "node was not found at " + typescript.node()).build());
            }
            return List.of();
        }
        int typescriptFindings = 0;
        TypeScriptParser.ProjectFiles none = new TypeScriptParser.ProjectFiles(List.of(), List.of(), List.of(), List.of());
        // documented: packages with sources, and the workspaces that still hold one of them
        List<TypeScriptModules.Found> kept = new ArrayList<>(found.stream().filter(f -> {
            TypeScriptParser.ProjectFiles files = result.get().projects().get(f.id());
            return files != null && !(files.files().isEmpty() && files.testFiles().isEmpty());
        }).toList());
        for (TypeScriptModules.Found workspace : found.reversed()) { // inner workspaces first
            if (workspace.workspace() != null && !kept.contains(workspace)
                    && kept.stream().anyMatch(k -> workspace.workspace().contains(k.dir()))) {
                kept.add(workspace);
            }
        }
        Map<String, String> parents = TypeScriptModules.parents(kept);

        List<ModuleDocumentation> documented = new ArrayList<>();
        for (TypeScriptModules.Found project : found) {
            if (!kept.contains(project)) {
                continue;
            }
            TypeScriptParser.ProjectFiles files = result.get().projects().getOrDefault(project.id(), none);
            files.warnings().stream().limit(5).forEach(w -> log.accept("TypeScript " + project.id() + ": " + w));
            Optional<ReadmeRenderer.Rendered> rendered = readme.render(project.dir(), DiagramGenerator.slug(project.id()));
            rendered.ifPresent(r -> readmeAssets.putAll(r.assets()));
            List<JavaFileStructure> sources = relativized(files.files(), root);
            for (TypeScriptParser.Diagnostic diagnostic : files.diagnostics()) {
                String file = relative(root, Path.of(diagnostic.file()));
                boolean error = diagnostic.category().equals("error");
                findings.add(QualityFinding.builder()
                        .tool("TypeScript")
                        .rule("TS" + diagnostic.code())
                        .category(error ? "Type error" : "Compiler warning")
                        .priority(error ? QualityFinding.Priority.HIGH : QualityFinding.Priority.MEDIUM)
                        .message(diagnostic.message())
                        .moduleId(project.id())
                        .file(file)
                        .line(diagnostic.line())
                        .endLine(diagnostic.endLine())
                        .typeName(typeAt(sources, file, diagnostic.line()))
                        .url("https://typescript.tv/errors/ts" + diagnostic.code())
                        .build());
                typescriptFindings++;
            }
            int[] lines = lineCounts(sources);
            List<JavaFileStructure> main = filter.apply(sources);
            documented.add(ModuleDocumentation.builder()
                    .id(project.id())
                    .parentId(parents.get(project.id()))
                    .language("typescript")
                    .relativePath(relative(root, project.dir()))
                    .project(project.project())
                    .files(main)
                    .testFiles(filter.apply(relativized(files.testFiles(), root)))
                    .statistics(statistics(main, lines))
                    .readmeHtml(rendered.map(ReadmeRenderer.Rendered::html).orElse(null))
                    .build());
        }
        if (diagnostics) {
            qualityTools.add(QualityStructure.ToolRun.builder().name("TypeScript").version(result.get().typescriptVersion()).ran(true)
                    .note(found.stream().filter(TypeScriptModules.Found::sources).count() + " project(s) type-checked with node " + result.get().nodeVersion()).findings(typescriptFindings).build());
        }
        return documented;
    }

    /** The innermost declaration covering a line of a file, by qualified name. */
    private static String typeAt(List<JavaFileStructure> files, String file, int line) {
        for (JavaFileStructure candidate : files) {
            if (file.equals(candidate.getFilePath())) {
                return typeAt(candidate.getTypes(), line);
            }
        }
        return null;
    }

    private static String typeAt(List<TypeStructure> types, int line) {
        for (TypeStructure type : types) {
            if (type.getStartLine() <= line && line <= type.getEndLine()) {
                String nested = typeAt(type.getNestedTypes(), line);
                return nested != null ? nested : type.getQualifiedName();
            }
        }
        return null;
    }

    private static List<JavaFileStructure> relativized(List<JavaFileStructure> files, Path root) {
        files.forEach(file -> file.setFilePath(file.getFilePath() == null ? null : relative(root, Path.of(file.getFilePath()))));
        return files;
    }

    /** Runs the javadoc engine over every parsed file at once, so types from other modules resolve from source. */
    private static boolean resolve(Map<Path, List<JavaFileStructure>> parsed, ResolveOptions options, Consumer<String> log)
            throws IOException {
        List<JavaFileStructure> all = parsed.values().stream().flatMap(List::stream).toList();
        List<Path> sources = all.stream().map(JavaFileStructure::getFilePath).filter(Objects::nonNull).map(Path::of).toList();
        Optional<Resolution> resolution = JavadocResolver.resolve(sources, options.classpath(), options.encoding(), log);
        resolution.ifPresent(r -> JavadocResolver.apply(r, all));
        return resolution.isPresent();
    }

    private static List<JavaFileStructure> gather(List<Path> roots, Map<Path, List<JavaFileStructure>> parsed, Path root) {
        List<JavaFileStructure> files = new ArrayList<>();
        for (Path sourceRoot : roots) {
            files.addAll(parsed.getOrDefault(sourceRoot, List.of()));
        }
        files.forEach(file -> file.setFilePath(file.getFilePath() == null ? null : relative(root, Path.of(file.getFilePath()))));
        return files;
    }

    /** The nearest other module whose directory contains this module's directory. */
    private static String parentId(ModuleInput module, List<ModuleInput> modules) {
        Path dir = module.basedir().toAbsolutePath().normalize();
        ModuleInput best = null;
        for (ModuleInput candidate : modules) {
            Path candidateDir = candidate.basedir().toAbsolutePath().normalize();
            if (candidate != module && !candidateDir.equals(dir) && dir.startsWith(candidateDir)
                    && (best == null || candidateDir.getNameCount() > best.basedir().toAbsolutePath().normalize().getNameCount())) {
                best = candidate;
            }
        }
        return best == null ? null : best.id();
    }

    private static String relative(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return normalized.startsWith(root)
                ? root.relativize(normalized).toString().replace('\\', '/')
                : normalized.toString().replace('\\', '/');
    }

    /** {total, code, comment, blank} lines over the files. */
    private static int[] lineCounts(List<JavaFileStructure> files) {
        int[] lines = new int[4];
        for (JavaFileStructure file : files) {
            lines[0] += file.getTotalLines();
            lines[1] += file.getCodeLines();
            lines[2] += file.getCommentLines();
            lines[3] += file.getBlankLines();
        }
        return lines;
    }

    private static ModuleStatistics statistics(List<JavaFileStructure> files, int[] lines) {
        int[] counts = new int[5]; // types, methods, fields, documented, documentable
        Set<String> packages = new HashSet<>();
        Map<String, Integer> byKind = new TreeMap<>();
        List<Integer> complexities = new ArrayList<>();
        for (JavaFileStructure file : files) {
            packages.add(file.getPackageName());
            for (TypeStructure type : file.getTypes()) {
                count(type, counts, byKind, complexities);
            }
        }
        return ModuleStatistics.builder()
                .files(files.size())
                .packages(packages.size())
                .types(counts[0])
                .methods(counts[1])
                .fields(counts[2])
                .documented(counts[3])
                .documentable(counts[4])
                .linesOfCode(lines[1])
                .totalLines(lines[0])
                .codeLines(lines[1])
                .commentLines(lines[2])
                .blankLines(lines[3])
                .typesByKind(byKind)
                .averageComplexity(complexities.isEmpty() ? 0
                        : Math.round(complexities.stream().mapToInt(Integer::intValue).average().orElse(0) * 100) / 100.0)
                .maxComplexity(complexities.stream().mapToInt(Integer::intValue).max().orElse(0))
                .build();
    }

    private static void count(TypeStructure type, int[] counts, Map<String, Integer> byKind, List<Integer> complexities) {
        counts[0]++;
        byKind.merge(type.getKind().name(), 1, Integer::sum);
        type.getMethods().stream().filter(m -> m.getBody() != null || m.getLineCount() > 1)
                .forEach(m -> complexities.add(m.getCyclomaticComplexity()));
        counts[1] += type.getMethods().size() + type.getConstructors().size();
        counts[2] += type.getFields().size();
        counts[4] += 1 + type.getMethods().size() + type.getConstructors().size() + type.getFields().size();
        counts[3] += (type.getJavadoc() != null ? 1 : 0)
                + (int) type.getMethods().stream().filter(m -> m.getJavadoc() != null).count()
                + (int) type.getConstructors().stream().filter(c -> c.getJavadoc() != null).count()
                + (int) type.getFields().stream().filter(f -> f.getJavadoc() != null).count();
        type.getNestedTypes().forEach(nested -> count(nested, counts, byKind, complexities));
    }
}
