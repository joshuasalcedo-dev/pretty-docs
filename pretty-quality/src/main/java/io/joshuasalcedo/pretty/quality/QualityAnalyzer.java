package io.joshuasalcedo.pretty.quality;

import edu.umd.cs.findbugs.*;
import edu.umd.cs.findbugs.config.UserPreferences;
import io.joshuasalcedo.pretty.quality.model.QualityFinding;
import io.joshuasalcedo.pretty.quality.model.QualityStructure;
import net.sourceforge.pmd.PMDConfiguration;
import net.sourceforge.pmd.PmdAnalysis;
import net.sourceforge.pmd.lang.rule.Rule;
import net.sourceforge.pmd.reporting.Report;
import net.sourceforge.pmd.reporting.RuleViolation;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Runs code quality tools over Java modules: PMD on the sources (no build needed) and SpotBugs on
 * the classes the last build compiled (skipped for modules without them).
 */
public final class QualityAnalyzer {

    static final String PMD_RULES = "pretty-quality/pmd-rules.xml";
    private static final String SPOTBUGS_DOCS = "https://spotbugs.readthedocs.io/en/stable/bugDescriptions.html";
    /**
     * Patterns left out: exposing or storing mutable fields (records, injected collaborators and
     * Lombok accessors trip them constantly) and constructors that throw (finalizer-attack hardening).
     */
    static final Set<String> SPOTBUGS_EXCLUDED = Set.of("EI_EXPOSE_REP", "EI_EXPOSE_REP2", "CT_CONSTRUCTOR_THROW");

    /**
     * A Java module to check.
     *
     * @param classesDir compiled classes (target/classes); null or missing to skip SpotBugs for it
     */
    public record Module(String id, List<Path> sourceRoots, Path classesDir) {
    }

    /**
     * @param pmd       run PMD
     * @param spotbugs  run SpotBugs where classes exist
     * @param classpath libraries the code uses, for type resolution (PMD) and analysis (SpotBugs)
     */
    public record Options(boolean pmd, boolean spotbugs, List<Path> classpath) {
    }

    private QualityAnalyzer() {
    }

    public static QualityStructure analyze(List<Module> modules, Options options, Consumer<String> log) {
        List<QualityFinding> findings = new ArrayList<>();
        List<QualityStructure.ToolRun> tools = new ArrayList<>();
        if (options.pmd()) {
            tools.add(pmd(modules, options.classpath(), findings, log));
        }
        if (options.spotbugs()) {
            tools.add(spotbugs(modules, options.classpath(), findings, log));
        }
        findings.sort(Comparator.comparing(QualityFinding::getPriority)
                .thenComparing(f -> String.valueOf(f.getFile())).thenComparingInt(QualityFinding::getLine));
        return QualityStructure.builder().tools(tools).findings(findings).build();
    }

    // ------------------------------------------------------------------ PMD

    static QualityStructure.ToolRun pmd(List<Module> modules, List<Path> classpath, List<QualityFinding> into, Consumer<String> log) {
        Map<Path, String> moduleOfRoot = new LinkedHashMap<>();
        modules.forEach(m -> m.sourceRoots().stream().filter(Files::isDirectory)
                .forEach(root -> moduleOfRoot.put(root.toAbsolutePath().normalize(), m.id())));
        String version = net.sourceforge.pmd.PMDVersion.VERSION;
        if (moduleOfRoot.isEmpty()) {
            return QualityStructure.ToolRun.builder().name("PMD").version(version).ran(false).note("no Java sources").build();
        }
        PMDConfiguration config = new PMDConfiguration();
        moduleOfRoot.keySet().forEach(config::addInputPath);
        config.addRuleSet(PMD_RULES);
        config.setIgnoreIncrementalAnalysis(true);
        config.setThreads(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
        // classes of the build's own modules (when compiled) and its libraries: PMD resolves types with them
        List<String> aux = new ArrayList<>();
        modules.stream().map(Module::classesDir).filter(d -> d != null && Files.isDirectory(d)).forEach(d -> aux.add(d.toString()));
        classpath.forEach(p -> aux.add(p.toString()));
        if (!aux.isEmpty()) {
            config.prependAuxClasspath(String.join(File.pathSeparator, aux));
        }
        int before = into.size();
        try (PmdAnalysis pmd = PmdAnalysis.create(config)) {
            Report report = pmd.performAnalysisAndCollectReport();
            for (RuleViolation violation : report.getViolations()) {
                Rule rule = violation.getRule();
                Path file = Path.of(violation.getFileId().getAbsolutePath()).toAbsolutePath().normalize();
                Map<String, String> info = violation.getAdditionalInfo();
                String pkg = info.getOrDefault(RuleViolation.PACKAGE_NAME, "");
                String cls = info.get(RuleViolation.CLASS_NAME);
                String member = info.getOrDefault(RuleViolation.METHOD_NAME, info.get(RuleViolation.VARIABLE_NAME));
                into.add(QualityFinding.builder()
                        .tool("PMD")
                        .rule(rule.getName())
                        .category(category(rule.getRuleSetName()))
                        .priority(switch (rule.getPriority().getPriority()) {
                            case 1, 2 -> QualityFinding.Priority.HIGH;
                            case 3 -> QualityFinding.Priority.MEDIUM;
                            default -> QualityFinding.Priority.LOW;
                        })
                        .message(violation.getDescription())
                        .moduleId(moduleOf(file, moduleOfRoot))
                        .file(file.toString())
                        .line(violation.getBeginLine())
                        .endLine(violation.getEndLine())
                        .typeName(cls == null || cls.isBlank() ? null : (pkg.isEmpty() ? "" : pkg + ".") + cls.replace('$', '.'))
                        .memberName(member == null || member.isBlank() ? null : member)
                        .url(rule.getExternalInfoUrl())
                        .build());
            }
            int errors = report.getProcessingErrors().size();
            if (errors > 0) {
                log.accept("PMD could not analyze " + errors + " file(s); first: " + report.getProcessingErrors().getFirst().getMsg());
            }
        } catch (RuntimeException e) {
            log.accept("PMD failed: " + e);
            return QualityStructure.ToolRun.builder().name("PMD").version(version).ran(false).note("failed: " + e.getMessage()).build();
        }
        return QualityStructure.ToolRun.builder().name("PMD").version(version).ran(true)
                .note(moduleOfRoot.size() + " source folder(s); rules: errorprone, bestpractices, multithreading, performance, security")
                .findings(into.size() - before).build();
    }

    private static String category(String ruleSet) {
        String name = ruleSet == null ? "" : ruleSet.replace("category/java/", "").replace(".xml", "");
        return switch (name.toLowerCase(Locale.ROOT).replace(" ", "")) {
            case "errorprone" -> "Error prone";
            case "bestpractices" -> "Best practices";
            case "multithreading" -> "Multithreading";
            case "performance" -> "Performance";
            case "security" -> "Security";
            case "design" -> "Design";
            case "codestyle" -> "Code style";
            default -> name.isEmpty() ? "Other" : name;
        };
    }

    // ------------------------------------------------------------ SpotBugs

    static QualityStructure.ToolRun spotbugs(List<Module> modules, List<Path> classpath, List<QualityFinding> into, Consumer<String> log) {
        String version = Version.VERSION_STRING;
        List<Module> compiled = modules.stream().filter(m -> m.classesDir() != null && hasClasses(m.classesDir())).toList();
        if (compiled.isEmpty()) {
            return QualityStructure.ToolRun.builder().name("SpotBugs").version(version).ran(false)
                    .note("no compiled classes: build the project (e.g. mvn compile) to include SpotBugs").build();
        }
        Project project = new Project();
        compiled.forEach(m -> project.addFile(m.classesDir().toString()));
        classpath.forEach(p -> project.addAuxClasspathEntry(p.toString()));
        compiled.forEach(m -> project.addSourceDirs(m.sourceRoots().stream().map(Path::toString).toList()));

        BugCollectionBugReporter reporter = new BugCollectionBugReporter(project);
        reporter.setPriorityThreshold(Priorities.NORMAL_PRIORITY);
        FindBugs2 engine = new FindBugs2();
        engine.setBugReporter(reporter);
        engine.setProject(project);
        engine.setDetectorFactoryCollection(DetectorFactoryCollection.instance());
        UserPreferences preferences = UserPreferences.createDefaultUserPreferences();
        preferences.setEffort(UserPreferences.EFFORT_DEFAULT);
        engine.setUserPreferences(preferences);
        engine.setNoClassOk(true);
        int before = into.size();
        try {
            engine.finishSettings();
            engine.execute();
        } catch (Exception | LinkageError e) {
            log.accept("SpotBugs failed: " + e);
            return QualityStructure.ToolRun.builder().name("SpotBugs").version(version).ran(false).note("failed: " + e.getMessage()).build();
        } finally {
            try {
                engine.close();
            } catch (Exception ignored) {
                // nothing to release
            }
        }
        for (BugInstance bug : reporter.getBugCollection().getCollection()) {
            if (SPOTBUGS_EXCLUDED.contains(bug.getType())) {
                continue;
            }
            SourceLineAnnotation source = bug.getPrimarySourceLineAnnotation();
            String className = source.getClassName();
            Module module = moduleOfClass(className, compiled);
            Path file = module == null ? null : sourceFile(module, source.getSourcePath());
            MethodAnnotation method = bug.getPrimaryMethod();
            FieldAnnotation field = bug.getPrimaryField();
            BugPattern pattern = bug.getBugPattern();
            int rank = BugRanker.findRank(bug);
            into.add(QualityFinding.builder()
                    .tool("SpotBugs")
                    .rule(bug.getType())
                    .category(I18N.instance().getBugCategoryDescription(pattern.getCategory()))
                    .priority(rank <= 9 ? QualityFinding.Priority.HIGH : rank <= 14 ? QualityFinding.Priority.MEDIUM : QualityFinding.Priority.LOW)
                    .message(readable(bug.getMessageWithoutPrefix()))
                    .moduleId(module == null ? null : module.id())
                    .file(file == null ? source.getSourcePath() : file.toString())
                    .line(Math.max(source.getStartLine(), 0))
                    .endLine(Math.max(source.getEndLine(), 0))
                    .typeName(outerClass(className))
                    .memberName(method != null ? method.getMethodName() : field != null ? field.getFieldName() : null)
                    .url(SPOTBUGS_DOCS + "#" + anchor(pattern))
                    .build());
        }
        return QualityStructure.ToolRun.builder().name("SpotBugs").version(version).ran(true)
                .note(compiled.size() + " compiled module(s), " + reporter.getBugCollection().getProjectStats().getNumClasses()
                        + " classes from the last build")
                .findings(into.size() - before).build();
    }

    private static boolean hasClasses(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(p -> p.toString().endsWith(".class"));
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static Module moduleOfClass(String className, List<Module> modules) {
        String file = className.replace('.', '/') + ".class";
        return modules.stream().filter(m -> Files.isRegularFile(m.classesDir().resolve(file))).findFirst().orElse(null);
    }

    private static Path sourceFile(Module module, String sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        return module.sourceRoots().stream().map(root -> root.resolve(sourcePath)).filter(Files::isRegularFile).findFirst()
                .map(p -> p.toAbsolutePath().normalize()).orElse(null);
    }

    /**
     * SpotBugs messages name things the JVM way; this keeps them readable: JVM method descriptors
     * become (…), qualified class names their simple names, Outer$Inner Outer.Inner.
     */
    static String readable(String message) {
        return message
                .replaceAll("\\((\\[*(L[\\w/$]+;|[ZBCSIJFD]))+\\)(\\[*(L[\\w/$]+;|[ZBCSIJFDV]))?", "(…)")
                .replaceAll("\\b(?:[a-z_][\\w]*\\.)+([A-Z][\\w$]*)", "$1")
                .replace('$', '.');
    }

    /** a.b.Outer$Inner -> a.b.Outer.Inner; synthetic/anonymous parts ($1) are dropped. */
    static String outerClass(String className) {
        return Arrays.stream(className.split("\\$")).takeWhile(part -> !part.isEmpty() && !Character.isDigit(part.charAt(0)))
                .collect(Collectors.joining("."));
    }

    /** The bug's anchor in SpotBugs' bug descriptions page, e.g. "np-null-pointer-dereference-np-always-null". */
    private static String anchor(BugPattern pattern) {
        String text = (pattern.getShortDescription() + " " + pattern.getType()).toLowerCase(Locale.ROOT);
        return pattern.getAbbrev().toLowerCase(Locale.ROOT) + "-" + text.replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static String moduleOf(Path file, Map<Path, String> moduleOfRoot) {
        String best = null;
        int depth = -1;
        for (Map.Entry<Path, String> entry : moduleOfRoot.entrySet()) {
            if (file.startsWith(entry.getKey()) && entry.getKey().getNameCount() > depth) {
                best = entry.getValue();
                depth = entry.getKey().getNameCount();
            }
        }
        return best;
    }
}
