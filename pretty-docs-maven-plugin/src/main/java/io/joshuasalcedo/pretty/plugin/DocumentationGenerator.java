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
import io.joshuasalcedo.pretty.spring.SpringAnalyzer;
import io.joshuasalcedo.pretty.spring.model.SpringStructure;

import java.io.IOException;
import java.nio.charset.Charset;
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
     */
    public record ModuleInput(String id, Path basedir, MavenProjectStructure project,
                              List<Path> sourceRoots, List<Path> testSourceRoots,
                              List<Path> resourceDirs, List<String> dependsOn) {

        public ModuleInput(String id, Path basedir, MavenProjectStructure project, List<Path> sourceRoots, List<Path> testSourceRoots) {
            this(id, basedir, project, sourceRoots, testSourceRoots, List.of(basedir.resolve("src/main/resources")), List.of());
        }
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
    private final String generator;

    public DocumentationGenerator(Visibility visibility, boolean includeMethodBodies, String generator) {
        this.visibility = visibility;
        this.filter = new SourceFilter(visibility, includeMethodBodies);
        this.generator = generator;
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
                    .relativePath(relative(root, module.basedir()))
                    .project(module.project())
                    .files(files)
                    .testFiles(testFiles)
                    .statistics(statistics(files, lines))
                    .readmeHtml(rendered.map(ReadmeRenderer.Rendered::html).orElse(null))
                    .build());
        }

        return DocumentationSite.builder()
                .schemaVersion(DocumentationSite.SCHEMA_VERSION)
                .title(title)
                .generator(generator)
                .generatedAt(Instant.now().toString())
                .visibility(visibility.id())
                .mode(resolved ? "resolved" : "source")
                .externalDocs(new ArrayList<>())
                .spring(spring)
                .readmeAssets(readmeAssets)
                .rootModuleId(modules.isEmpty() ? null : modules.getFirst().id())
                .modules(documented)
                .build();
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
