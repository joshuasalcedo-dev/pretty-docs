package io.joshuasalcedo.pretty.plugin;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Renders a module's README.md to HTML with GitHub-flavoured extensions. Fenced code blocks keep
 * their {@code language-xxx} class so the site can highlight them. Images referenced by relative
 * path (inside the module directory) are collected as assets and re-pointed at
 * {@code readme-assets/<module>/...}, so they show up in the generated site.
 */
final class ReadmeRenderer {

    /**
     * @param html   the rendered README
     * @param assets site path (relative to the site root) -> source file to copy there
     */
    record Rendered(String html, Map<String, Path> assets) {
    }

    private static final List<String> NAMES = List.of("README.md", "readme.md", "Readme.md", "README.markdown");
    private static final List<Extension> EXTENSIONS = List.of(
            TablesExtension.create(), StrikethroughExtension.create(), AutolinkExtension.create(), HeadingAnchorExtension.create());

    private final Parser parser = Parser.builder().extensions(EXTENSIONS).build();
    private final HtmlRenderer renderer = HtmlRenderer.builder().extensions(EXTENSIONS).sanitizeUrls(true).build();

    Optional<String> render(Path moduleDirectory) throws IOException {
        return render(moduleDirectory, "readme").map(Rendered::html);
    }

    /** @param assetFolder unique folder name for this module's images, e.g. its artifactId */
    Optional<Rendered> render(Path moduleDirectory, String assetFolder) throws IOException {
        for (String name : NAMES) {
            Path file = moduleDirectory.resolve(name);
            if (Files.isRegularFile(file)) {
                return Optional.of(render(Files.readString(file, StandardCharsets.UTF_8), moduleDirectory, assetFolder));
            }
        }
        return Optional.empty();
    }

    String render(String markdown) {
        return renderer.render(parser.parse(markdown));
    }

    private Rendered render(String markdown, Path moduleDirectory, String assetFolder) {
        Node document = parser.parse(markdown);
        Map<String, Path> assets = new LinkedHashMap<>();
        Path base = moduleDirectory.toAbsolutePath().normalize();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Image image) {
                String destination = image.getDestination();
                if (destination != null && isRelative(destination)) {
                    String path = URLDecoder.decode(destination.split("[?#]")[0], StandardCharsets.UTF_8);
                    Path source = base.resolve(path).normalize();
                    // Only files inside the module; never let a README pull in arbitrary files.
                    if (source.startsWith(base) && Files.isRegularFile(source)) {
                        String sitePath = "readme-assets/" + assetFolder + "/" + base.relativize(source).toString().replace('\\', '/');
                        assets.put(sitePath, source);
                        image.setDestination(sitePath);
                    }
                }
                visitChildren(image);
            }
        });
        return new Rendered(renderer.render(document), assets);
    }

    private static boolean isRelative(String destination) {
        String lower = destination.toLowerCase(Locale.ROOT);
        return !lower.contains(":") && !lower.startsWith("/") && !lower.startsWith("#");
    }
}
