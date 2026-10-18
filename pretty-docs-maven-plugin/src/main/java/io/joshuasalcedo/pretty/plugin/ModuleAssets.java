package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.plugin.model.AssetEntry;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Finds the images (svg, png, ico, ...) in each module's directory: icons and pictures in public/,
 * assets/, src/main/resources/static/ and the like. Each belongs to the innermost module whose
 * directory holds it. Dependencies, build output, hidden and test directories are not searched.
 */
final class ModuleAssets {

    static final Set<String> EXTENSIONS = Set.of("svg", "png", "ico", "jpg", "jpeg", "gif", "webp", "avif", "bmp");
    static final long MAX_SIZE = 1024 * 1024; // larger files are photos or screenshots, not worth copying
    static final int MAX_PER_MODULE = 500;
    private static final Set<String> SKIPPED = Set.of("node_modules", "target", "dist", "build", "out", "coverage",
            "__tests__", "__snapshots__");

    /** @param copy site path -> source file, for the site writer */
    record Result(Map<String, List<AssetEntry>> byModule, Map<String, Path> copy) {
    }

    private ModuleAssets() {
    }

    /**
     * @param moduleDirs module id -> directory
     * @param ignored    directories to leave out
     */
    static Result collect(Map<String, Path> moduleDirs, Predicate<Path> ignored, Consumer<String> log) throws IOException {
        Map<Path, String> owners = new HashMap<>();
        moduleDirs.forEach((id, dir) -> owners.putIfAbsent(dir.toAbsolutePath().normalize(), id));
        Map<String, List<AssetEntry>> byModule = new LinkedHashMap<>();
        Map<String, Path> copy = new LinkedHashMap<>();
        Map<String, Integer> skipped = new LinkedHashMap<>();
        for (Path base : TypeScriptModules.outermost(new ArrayList<>(owners.keySet()))) {
            if (!Files.isDirectory(base) || ignored.test(base)) {
                continue;
            }
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    boolean test = name.equals("test") && dir.getParent() != null && "src".equals(String.valueOf(dir.getParent().getFileName()));
                    return !dir.equals(base) && (SKIPPED.contains(name) || name.startsWith(".") || test || ignored.test(dir))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    int dot = name.lastIndexOf('.');
                    if (dot < 0 || !EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT)) || attrs.size() > MAX_SIZE) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path owner = owner(file, owners.keySet());
                    if (owner == null) {
                        return FileVisitResult.CONTINUE;
                    }
                    String id = owners.get(owner);
                    List<AssetEntry> assets = byModule.computeIfAbsent(id, k -> new ArrayList<>());
                    if (assets.size() >= MAX_PER_MODULE) {
                        skipped.merge(id, 1, Integer::sum);
                        return FileVisitResult.CONTINUE;
                    }
                    String path = owner.relativize(file).toString().replace('\\', '/');
                    String sitePath = "assets/" + DiagramGenerator.slug(id) + "/" + path;
                    assets.add(AssetEntry.builder().path(path).href(encode(sitePath)).size(attrs.size()).build());
                    copy.put(sitePath, file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        byModule.values().forEach(list -> list.sort(Comparator.comparing(AssetEntry::getPath)));
        skipped.forEach((id, count) -> log.accept("Assets: " + id + " has more than " + MAX_PER_MODULE + " images; "
                + count + " left out (add its folders to <ignores> to choose)"));
        return new Result(byModule, copy);
    }

    /** The site path as a URL path: spaces, # and ? in file names would otherwise break the link. */
    static String encode(String sitePath) {
        try {
            return new java.net.URI(null, null, sitePath, null).getRawPath();
        } catch (java.net.URISyntaxException e) {
            return sitePath;
        }
    }

    private static Path owner(Path file, Set<Path> dirs) {
        Path best = null;
        for (Path dir : dirs) {
            if (file.startsWith(dir) && (best == null || dir.getNameCount() > best.getNameCount())) {
                best = dir;
            }
        }
        return best;
    }
}
