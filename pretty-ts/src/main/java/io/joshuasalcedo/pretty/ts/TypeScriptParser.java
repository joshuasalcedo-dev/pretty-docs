package io.joshuasalcedo.pretty.ts;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import io.joshuasalcedo.pretty.model.JavaFileStructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Reads TypeScript projects into the pretty-java model, so the documentation site shows them like
 * Java modules. The work is done by the TypeScript compiler API in {@code analyzer.cjs}, run by the
 * user's {@code node}: nothing is installed or built. The compiler ships inside this jar (the
 * {@code org.webjars.npm:typescript} WebJar) and is extracted once into a cache directory.
 * Without {@code node} on the PATH, TypeScript is skipped.
 */
public final class TypeScriptParser {

    private static final String WEBJAR = "META-INF/resources/webjars/typescript/";
    private static final String WEBJAR_VERSION = "META-INF/maven/org.webjars.npm/typescript/pom.properties";
    private static final String ANALYZER = "pretty-ts/analyzer.cjs";
    private static final List<String> SKIPPED = List.of("node_modules", "target", "dist", "build", "out", "coverage");
    private static volatile String node; // configured executable, null for the PATH

    /**
     * A TypeScript project: a directory with a tsconfig.json.
     *
     * @param id   identifies it in the result
     * @param name prefix of its qualified names, usually the package.json name
     */
    public record Project(String id, Path dir, String name) {
    }

    /** One project's documented files and compiler diagnostics; warnings are problems that did not stop the analysis. */
    public record ProjectFiles(List<JavaFileStructure> files, List<JavaFileStructure> testFiles, List<Diagnostic> diagnostics,
                               List<String> warnings) {
    }

    /**
     * A compiler error or warning, as {@code tsc --noEmit} reports it.
     *
     * @param file     absolute path
     * @param category error, warning or suggestion
     */
    public record Diagnostic(String file, int line, int endLine, int code, String category, String message) {
    }

    public record Result(String typescriptVersion, String nodeVersion, Map<String, ProjectFiles> projects) {
    }

    private TypeScriptParser() {
    }

    /** {@code node --version}, or empty when node is not on the PATH (or does not answer). */
    public static Optional<String> nodeVersion() {
        try {
            Process process = new ProcessBuilder(nodeCommand(), "--version").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 && output.startsWith("v") ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Directories under {@code root} that hold a TypeScript project. A tsconfig.json nested in
     * another project counts only with its own package.json (a package of a monorepo); otherwise
     * it is a variant of the enclosing project's configuration.
     */
    public static List<Path> discover(Path root, List<String> excludes) throws IOException {
        return discover(root, excluded(root, excludes));
    }

    /** @param skip directories not to enter (absolute, normalized), besides node_modules, build output and hidden ones */
    public static List<Path> discover(Path root, Predicate<Path> skip) throws IOException {
        List<Path> candidates = walk(root, skip,dir -> Files.isRegularFile(dir.resolve("tsconfig.json")));
        List<Path> projects = new ArrayList<>();
        for (Path candidate : candidates) {
            boolean nested = candidates.stream().anyMatch(other -> !other.equals(candidate) && candidate.startsWith(other));
            if (!nested || Files.isRegularFile(candidate.resolve("package.json"))) {
                projects.add(candidate);
            }
        }
        return projects;
    }

    /**
     * A package-manager workspace (monorepo root): a package.json with {@code "workspaces"}, or a
     * pnpm-workspace.yaml.
     *
     * @param manager  npm, yarn, pnpm or bun, from the configuration and lock file
     * @param patterns member package globs, relative to {@code dir}; a leading "!" excludes
     */
    public record Workspace(Path dir, String manager, List<String> patterns) {

        /** Whether the package in {@code packageDir} is one of this workspace's members. */
        public boolean contains(Path packageDir) {
            Path base = dir.toAbsolutePath().normalize();
            Path normalized = packageDir.toAbsolutePath().normalize();
            if (normalized.equals(base) || !normalized.startsWith(base)) {
                return false;
            }
            String relative = base.relativize(normalized).toString().replace('\\', '/');
            boolean member = false;
            for (String pattern : patterns) {
                boolean negated = pattern.startsWith("!");
                if (glob(negated ? pattern.substring(1) : pattern).matcher(relative).matches()) {
                    member = !negated;
                }
            }
            return member;
        }

        private static java.util.regex.Pattern glob(String pattern) {
            String p = strip(pattern.strip().replace('\\', '/'));
            if (p.startsWith("./")) {
                p = p.substring(2);
            }
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < p.length(); i++) {
                char c = p.charAt(i);
                if (c == '*' && i + 1 < p.length() && p.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                    if (i + 1 < p.length() && p.charAt(i + 1) == '/') {
                        regex.append("/?");
                        i++;
                    }
                } else if (c == '*') {
                    regex.append("[^/]*");
                } else if (c == '?') {
                    regex.append("[^/]");
                } else {
                    regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
                }
            }
            return java.util.regex.Pattern.compile(regex.toString());
        }
    }

    /** The workspaces under {@code root} (see {@link Workspace}), skipping what {@link #discover} skips. */
    public static List<Workspace> workspaces(Path root, List<String> excludes) throws IOException {
        return workspaces(root, excluded(root, excludes));
    }

    /** @param skip directories not to enter, as for {@link #discover(Path, Predicate)} */
    public static List<Workspace> workspaces(Path root, Predicate<Path> skip) throws IOException {
        List<Workspace> workspaces = new ArrayList<>();
        for (Path dir : walk(root, skip,dir -> Files.isRegularFile(dir.resolve("package.json"))
                || Files.isRegularFile(dir.resolve("pnpm-workspace.yaml")))) {
            workspace(dir).ifPresent(workspaces::add);
        }
        return workspaces;
    }

    /** The workspace rooted at {@code dir}, if it is one. */
    public static Optional<Workspace> workspace(Path dir) {
        List<String> patterns = new ArrayList<>();
        Path pnpm = dir.resolve("pnpm-workspace.yaml");
        if (Files.isRegularFile(pnpm)) {
            patterns.addAll(pnpmPackages(pnpm));
            return patterns.isEmpty() ? Optional.empty() : Optional.of(new Workspace(dir, "pnpm", patterns));
        }
        Path packageJson = dir.resolve("package.json");
        if (!Files.isRegularFile(packageJson)) {
            return Optional.empty();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(packageJson, StandardCharsets.UTF_8));
            JsonElement declared = parsed.isJsonObject() ? parsed.getAsJsonObject().get("workspaces") : null;
            // "workspaces": [...] (npm, yarn, bun) or {"packages": [...]} (yarn classic)
            if (declared != null && declared.isJsonObject()) {
                declared = declared.getAsJsonObject().get("packages");
            }
            if (declared != null && declared.isJsonArray()) {
                declared.getAsJsonArray().forEach(e -> {
                    if (e.isJsonPrimitive() && !e.getAsString().isBlank()) {
                        patterns.add(e.getAsString());
                    }
                });
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (patterns.isEmpty()) {
            return Optional.empty();
        }
        String manager = Files.exists(dir.resolve("yarn.lock")) || Files.exists(dir.resolve(".yarnrc.yml")) ? "yarn"
                : Files.exists(dir.resolve("bun.lock")) || Files.exists(dir.resolve("bun.lockb")) ? "bun"
                : "npm";
        return Optional.of(new Workspace(dir, manager, patterns));
    }

    /** The {@code packages:} list of a pnpm-workspace.yaml (block or flow style). */
    static List<String> pnpmPackages(Path file) {
        List<String> patterns = new ArrayList<>();
        try {
            boolean inPackages = false;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.replaceAll("\\s+#.*$", "");
                if (line.isBlank() || line.strip().startsWith("#")) {
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0)) && !line.startsWith("-")) {
                    inPackages = line.startsWith("packages:");
                    String rest = line.substring(line.indexOf(':') + 1).strip();
                    if (inPackages && rest.startsWith("[")) {
                        for (String item : rest.replaceAll("[\\[\\]]", "").split(",")) {
                            add(patterns, item);
                        }
                        inPackages = false;
                    }
                } else if (inPackages && line.strip().startsWith("-")) {
                    add(patterns, line.strip().substring(1));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return patterns;
    }

    private static void add(List<String> patterns, String item) {
        String value = item.strip().replaceAll("^['\"]|['\"]$", "");
        if (!value.isBlank()) {
            patterns.add(value);
        }
    }

    /** Directories under {@code root} that match, not entering node_modules, build output, hidden or excluded directories. */
    /** Directories relative to {@code root}, with everything below them, as a skip test. */
    private static Predicate<Path> excluded(Path root, List<String> excludes) {
        Path base = root.toAbsolutePath().normalize();
        return dir -> {
            String relative = base.relativize(dir).toString().replace('\\', '/');
            return excludes.stream().anyMatch(e -> !e.isBlank() && (relative.equals(strip(e)) || relative.startsWith(strip(e) + "/")));
        };
    }

    private static List<Path> walk(Path root, Predicate<Path> skip, Predicate<Path> match) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(base, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (!dir.equals(base) && (SKIPPED.contains(name) || name.startsWith(".") || skip.test(dir))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (match.test(dir)) {
                    found.add(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return found;
    }

    /**
     * Runs the analysis over the projects.
     *
     * @param workDir where the compiler is extracted (reused across runs) and requests are written
     * @return empty when node is missing or the analysis failed (the reason is logged)
     */
    public static Optional<Result> parse(List<Project> projects, boolean includeBodies, boolean includeTests, Path workDir,
                                         Consumer<String> log) throws IOException {
        return parse(projects, includeBodies, includeTests, false, workDir, log);
    }

    /** @param diagnostics also type-check the documented files (the compiler's errors and warnings) */
    public static Optional<Result> parse(List<Project> projects, boolean includeBodies, boolean includeTests, boolean diagnostics,
                                         Path workDir, Consumer<String> log) throws IOException {
        if (projects.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> node = nodeVersion();
        if (node.isEmpty()) {
            log.accept("node was not found on the PATH; skipping " + projects.size() + " TypeScript project(s)");
            return Optional.empty();
        }
        Path engine = extract(workDir);
        Gson gson = new Gson();
        JsonObject request = new JsonObject();
        request.addProperty("typescript", engine.resolve("typescript/lib/typescript.js").toString());
        request.addProperty("includeBodies", includeBodies);
        request.addProperty("includeTests", includeTests);
        request.addProperty("diagnostics", diagnostics);
        JsonArray list = new JsonArray();
        // deeper projects first: a file belongs to the innermost project that lists it
        projects.stream()
                .sorted(Comparator.comparingInt((Project p) -> p.dir().toAbsolutePath().normalize().getNameCount()).reversed())
                .forEach(p -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", p.id());
                    item.addProperty("dir", p.dir().toAbsolutePath().normalize().toString());
                    item.addProperty("name", p.name());
                    list.add(item);
                });
        request.add("projects", list);

        Path requestFile = Files.createTempFile(workDir, "request-", ".json");
        Path responseFile = Files.createTempFile(workDir, "response-", ".json");
        try {
            Files.writeString(requestFile, gson.toJson(request), StandardCharsets.UTF_8);
            Process process = new ProcessBuilder(nodeCommand(), "--max-old-space-size=4096", engine.resolve("analyzer.cjs").toString(),
                    requestFile.toString(), responseFile.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                log.accept("The TypeScript analysis did not finish in 15 minutes; skipping TypeScript");
                return Optional.empty();
            }
            if (process.exitValue() != 0 || Files.size(responseFile) == 0) {
                log.accept("The TypeScript analysis failed (exit " + process.exitValue() + "): " + abbreviate(output));
                return Optional.empty();
            }
            JsonObject response = JsonParser.parseString(Files.readString(responseFile, StandardCharsets.UTF_8)).getAsJsonObject();
            java.lang.reflect.Type fileList = new TypeToken<List<JavaFileStructure>>() { }.getType();
            java.lang.reflect.Type diagnosticList = new TypeToken<List<Diagnostic>>() { }.getType();
            Map<String, ProjectFiles> result = new LinkedHashMap<>();
            for (JsonElement element : response.getAsJsonArray("projects")) {
                JsonObject project = element.getAsJsonObject();
                List<String> warnings = new ArrayList<>();
                project.getAsJsonArray("warnings").forEach(w -> warnings.add(w.getAsString()));
                result.put(project.get("id").getAsString(), new ProjectFiles(
                        gson.fromJson(project.get("files"), fileList),
                        gson.fromJson(project.get("testFiles"), fileList),
                        project.has("diagnostics") ? gson.fromJson(project.get("diagnostics"), diagnosticList) : List.of(),
                        warnings));
            }
            return Optional.of(new Result(response.get("typescriptVersion").getAsString(), node.get(), result));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            Files.deleteIfExists(requestFile);
            Files.deleteIfExists(responseFile);
        }
    }

    /** Copies the compiler and the analyzer out of the jar, once per TypeScript and analyzer version. */
    static Path extract(Path workDir) throws IOException {
        String version = typescriptVersion();
        byte[] analyzer;
        try (InputStream in = resource(ANALYZER)) {
            analyzer = in.readAllBytes();
        }
        Path engine = workDir.resolve("engine-" + version + "-" + Integer.toHexString(Arrays.hashCode(analyzer)));
        Path complete = engine.resolve(".complete");
        if (Files.exists(complete)) {
            return engine;
        }
        Files.createDirectories(engine);
        copyResourceTree(WEBJAR + version, engine.resolve("typescript"));
        Files.write(engine.resolve("analyzer.cjs"), analyzer);
        Files.writeString(complete, version);
        return engine;
    }

    static String typescriptVersion() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = resource(WEBJAR_VERSION)) {
            properties.load(in);
        }
        return properties.getProperty("version");
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = TypeScriptParser.class.getClassLoader().getResourceAsStream(name);
        if (in == null) {
            throw new IOException(name + " is not on the classpath");
        }
        return in;
    }

    private static void copyResourceTree(String root, Path target) throws IOException {
        URL url = TypeScriptParser.class.getClassLoader().getResource(root + "/package.json");
        if (url == null) {
            throw new IOException(root + " is not on the classpath");
        }
        URI uri;
        try {
            uri = url.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Unreadable location " + url, e);
        }
        if ("jar".equals(uri.getScheme())) {
            FileSystem fs;
            boolean opened = false;
            try {
                fs = FileSystems.newFileSystem(uri, Map.of());
                opened = true;
            } catch (FileSystemAlreadyExistsException e) {
                fs = FileSystems.getFileSystem(uri);
            }
            try {
                copyTree(fs.getPath("/" + root), target);
            } finally {
                if (opened) {
                    fs.close();
                }
            }
        } else {
            copyTree(Path.of(uri).getParent(), target);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(source)) {
            paths = walk.filter(Files::isRegularFile).toList();
        }
        for (Path path : paths) {
            Path destination = target.resolve(source.relativize(path).toString());
            Files.createDirectories(destination.getParent());
            try (InputStream in = Files.newInputStream(path)) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Use this node executable instead of the one on the PATH (null or blank: the PATH again).
     * Applies to every later call in this JVM.
     */
    public static void useNode(String command) {
        node = command == null || command.isBlank() ? null : command.strip();
    }

    private static String nodeCommand() {
        if (node != null) {
            return node;
        }
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "node.exe" : "node";
    }

    private static String strip(String path) {
        return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static String abbreviate(String text) {
        return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
    }
}
