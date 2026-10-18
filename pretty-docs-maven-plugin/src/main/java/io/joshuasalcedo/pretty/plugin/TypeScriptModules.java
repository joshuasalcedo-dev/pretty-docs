package io.joshuasalcedo.pretty.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.joshuasalcedo.pretty.maven.model.DependencyStructure;
import io.joshuasalcedo.pretty.maven.model.LicenseStructure;
import io.joshuasalcedo.pretty.maven.model.MavenProjectStructure;
import io.joshuasalcedo.pretty.ts.TypeScriptParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * TypeScript projects inside the build (a directory with a tsconfig.json, see
 * {@link TypeScriptParser#discover}) and the npm, yarn, pnpm or bun workspaces that hold them,
 * documented as modules of their own with metadata from package.json. They form their own tree,
 * separate from the Maven modules: see {@link #parents}.
 */
final class TypeScriptModules {

    /**
     * @param enabled      false to leave TypeScript out
     * @param excludes     directories to skip for TypeScript only (see {@link Ignores} for the patterns)
     * @param workDir      where the TypeScript compiler is extracted, reused across builds
     * @param includeTests document *.test.ts / *.spec.ts / __tests__ as test files
     * @param projects     project directories to document, relative to the root module; empty to find them all
     * @param workspaces   group packages under their npm, yarn, pnpm or bun workspace
     * @param typeCheck    report the compiler's errors as quality findings
     * @param node         the node executable; null or blank for the one on the PATH
     */
    record Options(boolean enabled, List<String> excludes, Path workDir, boolean includeTests, List<String> projects,
                   boolean workspaces, boolean typeCheck, String node) {

        Options(boolean enabled, List<String> excludes, Path workDir, boolean includeTests) {
            this(enabled, excludes, workDir, includeTests, List.of(), true, true, null);
        }
    }

    /**
     * A discovered package: its module id ("npm:" + package name) and metadata.
     *
     * @param sources   it has a tsconfig.json, so the analyzer reads its sources
     * @param workspace it is a workspace root, whose members are documented below it; else null
     */
    record Found(Path dir, String id, MavenProjectStructure project, boolean sources, TypeScriptParser.Workspace workspace) {
    }

    private TypeScriptModules() {
    }

    /**
     * The TypeScript packages and the workspaces that hold them. A workspace without a TypeScript
     * member is left out.
     */
    static List<Found> discover(Path root, List<Path> moduleDirs, Predicate<Path> ignored) throws IOException {
        return discover(root, moduleDirs, ignored, List.of(), true, message -> { });
    }

    /**
     * @param projects       explicit project directories (relative to {@code root}) instead of searching
     * @param withWorkspaces false to document every package on its own, without workspace grouping
     */
    static List<Found> discover(Path root, List<Path> moduleDirs, Predicate<Path> ignored, List<String> projects,
                                boolean withWorkspaces, Consumer<String> log) throws IOException {
        Set<Path> dirs = new LinkedHashSet<>();
        Map<Path, TypeScriptParser.Workspace> workspaces = new LinkedHashMap<>();
        for (String project : projects) {
            Path dir = root.resolve(project).toAbsolutePath().normalize();
            if (Files.isRegularFile(dir.resolve("tsconfig.json"))) {
                dirs.add(dir);
            } else {
                log.accept("TypeScript project " + project + " has no tsconfig.json; left out");
            }
        }
        for (Path base : outermost(moduleDirs)) {
            if (!ignored.test(base) && Files.isDirectory(base)) {
                if (projects.isEmpty()) {
                    TypeScriptParser.discover(base, ignored).forEach(d -> dirs.add(d.toAbsolutePath().normalize()));
                }
                if (withWorkspaces) {
                    TypeScriptParser.workspaces(base, ignored).forEach(w -> workspaces.putIfAbsent(w.dir().toAbsolutePath().normalize(), w));
                }
            }
        }
        // a workspace counts when it holds a TypeScript package, directly or through a nested workspace
        Set<Path> kept = new LinkedHashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TypeScriptParser.Workspace workspace : workspaces.values()) {
                Path dir = workspace.dir().toAbsolutePath().normalize();
                if (!kept.contains(dir) && (dirs.stream().anyMatch(workspace::contains) || kept.stream().anyMatch(workspace::contains))) {
                    changed = kept.add(dir);
                }
            }
        }
        Set<Path> all = new TreeSet<>(Comparator.comparingInt(Path::getNameCount).thenComparing(Path::toString));
        all.addAll(dirs);
        all.addAll(kept);
        List<Found> found = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Path dir : all) {
            TypeScriptParser.Workspace workspace = kept.contains(dir) ? workspaces.get(dir) : null;
            MavenProjectStructure project = describe(dir, root);
            if (workspace != null) {
                project.setPackaging(workspace.manager() + " workspace");
            }
            String id = "npm:" + project.getArtifactId();
            if (!ids.add(id)) {
                id = "npm:" + relative(root, dir);
                ids.add(id);
            }
            found.add(new Found(dir, id, project, dirs.contains(dir), workspace));
        }
        return found;
    }

    /** The directories not inside another one of them: walking these covers all, each once. */
    static List<Path> outermost(List<Path> dirs) {
        List<Path> normalized = dirs.stream().map(d -> d.toAbsolutePath().normalize()).distinct().toList();
        return normalized.stream().filter(d -> normalized.stream().noneMatch(o -> !o.equals(d) && d.startsWith(o))).toList();
    }

    /**
     * Each package's parent among the documented ones: the innermost workspace it is a member of,
     * else the innermost documented non-workspace package whose directory holds it. Never a Maven module, so
     * TypeScript forms a tree of its own; top-level packages map to null.
     */
    static Map<String, String> parents(List<Found> documented) {
        Map<String, String> parents = new LinkedHashMap<>();
        for (Found found : documented) {
            Path dir = found.dir().toAbsolutePath().normalize();
            Found workspace = null;
            Found enclosing = null;
            for (Found candidate : documented) {
                Path candidateDir = candidate.dir().toAbsolutePath().normalize();
                if (candidate == found || candidateDir.equals(dir) || !dir.startsWith(candidateDir)) {
                    continue;
                }
                if (candidate.workspace() != null && candidate.workspace().contains(dir) && deeper(candidate, workspace)) {
                    workspace = candidate;
                }
                // a workspace adopts only its members: a package merely inside its folder is not part of it
                if (candidate.workspace() == null && deeper(candidate, enclosing)) {
                    enclosing = candidate;
                }
            }
            Found parent = workspace != null ? workspace : enclosing;
            parents.put(found.id(), parent == null ? null : parent.id());
        }
        return parents;
    }

    private static boolean deeper(Found candidate, Found best) {
        return best == null || candidate.dir().toAbsolutePath().normalize().getNameCount() > best.dir().toAbsolutePath().normalize().getNameCount();
    }

    /** package.json as project metadata; the directory name when there is none. */
    static MavenProjectStructure describe(Path dir, Path root) {
        JsonObject pkg = new JsonObject();
        Path file = dir.resolve("package.json");
        if (Files.isRegularFile(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (parsed.isJsonObject()) {
                    pkg = parsed.getAsJsonObject();
                }
            } catch (IOException | RuntimeException e) {
                // unreadable package.json: document the sources anyway
            }
        }
        String name = string(pkg, "name").orElse(relative(root, dir));
        List<DependencyStructure> dependencies = new ArrayList<>();
        for (String[] section : new String[][]{{"dependencies", "compile"}, {"peerDependencies", "peer"}, {"devDependencies", "dev"}}) {
            if (pkg.get(section[0]) instanceof JsonObject declared) {
                declared.entrySet().forEach(e -> dependencies.add(DependencyStructure.builder()
                        .groupId("npm")
                        .artifactId(e.getKey())
                        .version(e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : null)
                        .scope(section[1])
                        .type("npm")
                        .build()));
            }
        }
        Map<String, String> scripts = new TreeMap<>();
        if (pkg.get("scripts") instanceof JsonObject declared) {
            declared.entrySet().forEach(e -> scripts.put("script." + e.getKey(), e.getValue().isJsonPrimitive() ? e.getValue().getAsString() : ""));
        }
        List<LicenseStructure> licenses = string(pkg, "license").map(l -> List.of(LicenseStructure.builder().name(l).build())).orElse(List.of());
        return MavenProjectStructure.builder()
                .groupId("npm")
                .artifactId(name)
                .version(string(pkg, "version").orElse(""))
                .packaging("typescript")
                .name(name)
                .description(string(pkg, "description").orElse(null))
                .url(string(pkg, "homepage").orElse(null))
                .licenses(licenses)
                .developers(List.of())
                .properties(scripts)
                .dependencies(dependencies)
                .managedDependencies(List.of())
                .plugins(List.of())
                .modules(List.of())
                .children(List.of())
                .basedir(dir.toString())
                .build();
    }

    private static Optional<String> string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && !value.getAsString().isBlank() ? Optional.of(value.getAsString()) : Optional.empty();
    }

    static String relative(Path root, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path base = root.toAbsolutePath().normalize();
        return (normalized.startsWith(base) ? base.relativize(normalized) : normalized).toString().replace('\\', '/');
    }
}
