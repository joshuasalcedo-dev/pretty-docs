package io.joshuasalcedo.pretty.plugin;

import org.eclipse.jgit.ignore.IgnoreNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Directories left out of the documentation, from the plugin's {@code <ignores>}: Maven modules,
 * TypeScript packages and assets inside them are skipped. Patterns follow .gitignore loosely:
 * <ul>
 *   <li>{@code legacy} or {@code frameworks/tauri/examples}: that path below the root module;
 *       a single name without a slash ({@code examples}) matches a directory of that name anywhere</li>
 *   <li>{@code *}, {@code ?} and {@code **} globs: {@code apps/*}{@code /e2e}, {@code **}{@code /fixtures}</li>
 * </ul>
 * Everything below a matched directory is ignored as well. Optionally, what the repository's
 * .gitignore files ignore is left out too (git's own rules, read with JGit).
 */
final class Ignores implements Predicate<Path> {

    static final Ignores NONE = new Ignores(Path.of(""), List.of(), false);

    private final Path root;
    private final List<Pattern> anchored = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<String> patterns;
    private final Path gitRoot; // where .gitignore files are read from; null when they are not used
    private final Map<Path, Optional<IgnoreNode>> gitignores = new ConcurrentHashMap<>();

    Ignores(Path root, List<String> patterns) {
        this(root, patterns, false);
    }

    /** @param gitignore also leave out what the repository's .gitignore files ignore */
    Ignores(Path root, List<String> patterns, boolean gitignore) {
        this.root = root.toAbsolutePath().normalize();
        this.gitRoot = gitignore ? gitRoot(this.root) : null;
        this.patterns = patterns.stream().map(String::strip).filter(p -> !p.isEmpty() && !p.startsWith("#")).toList();
        for (String raw : this.patterns) {
            String pattern = raw.replace('\\', '/');
            while (pattern.endsWith("/")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (pattern.startsWith("./")) {
                pattern = pattern.substring(2);
            }
            boolean anchoredPattern = pattern.startsWith("/") || pattern.contains("/");
            pattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;
            if (!anchoredPattern && !pattern.contains("*") && !pattern.contains("?")) {
                names.add(pattern);
            } else {
                anchored.add(glob(anchoredPattern ? pattern : "**/" + pattern));
            }
        }
    }

    List<String> patterns() {
        return patterns;
    }

    boolean isEmpty() {
        return patterns.isEmpty() && gitRoot == null;
    }

    /** Whether {@code path} (a directory or file) is, or lies inside, an ignored directory. */
    @Override
    public boolean test(Path path) {
        if (isEmpty()) {
            return false;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (gitRoot != null && gitIgnored(normalized)) {
            return true;
        }
        if (patterns.isEmpty() || !normalized.startsWith(root) || normalized.equals(root)) {
            return false;
        }
        String relative = root.relativize(normalized).toString().replace('\\', '/');
        String[] segments = relative.split("/");
        StringBuilder prefix = new StringBuilder();
        for (String segment : segments) {
            if (names.contains(segment)) {
                return true;
            }
            prefix.append(prefix.isEmpty() ? "" : "/").append(segment);
            String current = prefix.toString();
            if (anchored.stream().anyMatch(p -> p.matcher(current).matches())) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ .gitignore

    /** Git's rules: a path is ignored when it, or a directory above it, is; deeper .gitignore files win. */
    private boolean gitIgnored(Path path) {
        if (!path.startsWith(gitRoot) || path.equals(gitRoot)) {
            return false;
        }
        Path relative = gitRoot.relativize(path);
        Path current = gitRoot;
        for (int i = 0; i < relative.getNameCount(); i++) {
            current = current.resolve(relative.getName(i));
            boolean directory = i < relative.getNameCount() - 1 || Files.isDirectory(current);
            if (gitIgnoredEntry(current, directory)) {
                return true;
            }
        }
        return false;
    }

    private boolean gitIgnoredEntry(Path entry, boolean directory) {
        for (Path dir = entry.getParent(); dir != null && dir.startsWith(gitRoot); dir = dir.getParent()) {
            Optional<IgnoreNode> node = gitignores.computeIfAbsent(dir, Ignores::readGitignore);
            if (node.isPresent()) {
                Boolean ignored = node.get().checkIgnored(dir.relativize(entry).toString().replace('\\', '/'), directory);
                if (ignored != null) {
                    return ignored;
                }
            }
        }
        return false;
    }

    private static Optional<IgnoreNode> readGitignore(Path dir) {
        Path file = dir.resolve(".gitignore");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(file)) {
            IgnoreNode node = new IgnoreNode();
            node.parse(in);
            return node.getRules().isEmpty() ? Optional.empty() : Optional.of(node);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The enclosing git working tree (the directory holding .git), else the root module itself. */
    private static Path gitRoot(Path root) {
        for (Path dir = root; dir != null; dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) {
                return dir;
            }
        }
        return root;
    }

    private static Pattern glob(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*' && i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                i++;
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '/') {
                    regex.append("(?:.*/)?"); // **/ : any number of directories, including none
                    i++;
                } else {
                    regex.append(".*");
                }
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }
}
