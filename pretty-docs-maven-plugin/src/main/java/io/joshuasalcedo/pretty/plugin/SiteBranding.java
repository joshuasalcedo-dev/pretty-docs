package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.maven.model.MavenProjectStructure;
import io.joshuasalcedo.pretty.plugin.model.AssetEntry;
import io.joshuasalcedo.pretty.plugin.model.ModuleDocumentation;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The project's logo and source repository link, for the site header. */
final class SiteBranding {

    private static final List<String> LOGOS = List.of(
            "logo.svg", "logo.png", "docs/logo.svg", "docs/logo.png", ".github/logo.svg", ".github/logo.png",
            "assets/logo.svg", "assets/logo.png", "public/logo.svg", "public/logo.png",
            "src/main/resources/static/logo.svg", "src/main/resources/static/logo.png",
            "public/favicon.svg", "src/main/resources/static/favicon.svg");
    /** Asset names worth taking as the logo, best first. */
    private static final List<Pattern> ASSET_LOGOS = List.of(
            Pattern.compile("logo\\.(svg|png|webp)"), Pattern.compile("[\\w-]*logo[\\w-]*\\.(svg|png|webp)"),
            Pattern.compile("favicon\\.(svg|png)"), Pattern.compile("icon\\.(svg|png)"));
    private static final Pattern SSH = Pattern.compile("^(?:ssh://)?[^@/]+@([^:/]+)[:/](.+?)(?:\\.git)?/?$");
    private static final Pattern HTTP = Pattern.compile("^(https?)://(?:[^@/]+@)?(.+?)(?:\\.git)?/?$");

    private SiteBranding() {
    }

    /**
     * The configured logo, else a logo.svg/png in the usual places, else the most logo-like image
     * among the modules' assets (the root module's first).
     */
    static Optional<Path> logo(Path root, String configured, List<ModuleDocumentation> modules, Consumer<String> log) {
        if (configured != null && !configured.isBlank()) {
            Path file = root.resolve(configured).normalize();
            if (Files.isRegularFile(file)) {
                return Optional.of(file);
            }
            log.accept("Logo " + file + " was not found; looking for one instead");
        }
        for (String candidate : LOGOS) {
            Path file = root.resolve(candidate);
            if (Files.isRegularFile(file)) {
                return Optional.of(file);
            }
        }
        for (Pattern pattern : ASSET_LOGOS) {
            Optional<Path> match = modules.stream()
                    .flatMap(m -> (m.getAssets() == null ? List.<AssetEntry>of() : m.getAssets()).stream()
                            .filter(a -> pattern.matcher(a.getPath().substring(a.getPath().lastIndexOf('/') + 1).toLowerCase()).matches())
                            .map(a -> root.resolve(m.getRelativePath()).resolve(a.getPath()).normalize()))
                    .min(Comparator.comparingInt((Path p) -> p.getNameCount()).thenComparing(Path::toString));
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    /** The configured URL, else the root POM's scm url, else the git remote "origin" as a web URL. */
    static Optional<String> repositoryUrl(Path root, String configured, MavenProjectStructure project) {
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured.strip());
        }
        if (project != null && project.getScm() != null && project.getScm().getUrl() != null
                && project.getScm().getUrl().matches("https?://.+")) {
            return Optional.of(project.getScm().getUrl());
        }
        for (Path dir = root.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            Path config = dir.resolve(".git/config");
            if (Files.isRegularFile(config)) {
                try {
                    Config git = new Config();
                    git.fromText(Files.readString(config, StandardCharsets.UTF_8));
                    return Optional.ofNullable(webUrl(git.getString("remote", "origin", "url")));
                } catch (IOException | ConfigInvalidException e) {
                    return Optional.empty();
                }
            }
            if (Files.exists(dir.resolve(".git"))) {
                return Optional.empty(); // a worktree or submodule: .git is a file pointing elsewhere
            }
        }
        return Optional.empty();
    }

    /** git@github.com:o/r.git, ssh://git@host/o/r and https://user@host/o/r.git as https://host/o/r. */
    static String webUrl(String remote) {
        if (remote == null || remote.isBlank()) {
            return null;
        }
        Matcher http = HTTP.matcher(remote.strip());
        if (http.matches()) {
            return "https://" + http.group(2);
        }
        Matcher ssh = SSH.matcher(remote.strip());
        return ssh.matches() ? "https://" + ssh.group(1) + "/" + ssh.group(2) : null;
    }
}
