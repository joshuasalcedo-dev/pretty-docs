package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.plugin.model.AssetEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IgnoresAndAssetsTest {

    @TempDir
    Path dir;

    @Test
    void ignoresFromThePomAndFromGitignore() throws IOException {
        Files.createDirectories(dir.resolve(".git"));
        write(".gitignore", "generated/\n*.log\n");
        write("web/.gitignore", "local-only/\n!keep.log\n");
        Ignores ignores = new Ignores(dir, List.of("legacy", "frameworks/*/examples", "**/fixtures"), true);

        assertTrue(ignores.test(dir.resolve("legacy")));
        assertTrue(ignores.test(dir.resolve("apps/legacy/src"))); // a bare name matches anywhere
        assertTrue(ignores.test(dir.resolve("frameworks/tauri/examples/api")));
        assertFalse(ignores.test(dir.resolve("frameworks/tauri/plugins")));
        assertTrue(ignores.test(dir.resolve("libs/a/fixtures/x.png")));
        assertTrue(ignores.test(dir.resolve("generated/icons")));
        assertTrue(ignores.test(dir.resolve("web/local-only/logo.png")));
        assertTrue(ignores.test(dir.resolve("web/debug.log")));
        assertFalse(ignores.test(dir.resolve("web/keep.log"))); // the nested .gitignore re-includes it
        assertFalse(ignores.test(dir.resolve("web/public/logo.svg")));
        assertFalse(new Ignores(dir, List.of(), false).test(dir.resolve("generated")));
    }

    @Test
    void assetsBelongToTheInnermostModule() throws IOException {
        write("pom.xml", "<project/>");
        write("logo.svg", "<svg/>");
        write("web/public/favicon.svg", "<svg/>");
        write("web/src-tauri/icons/32x32.png", "png");
        write("web/node_modules/lib/icon.png", "png");
        write("web/dist/copy.png", "png");
        write("web/src/test/fixture.png", "png");
        write("web/notes.txt", "no image");
        write("service/src/main/resources/static/img/hero.webp", "webp");
        Map<String, Path> modules = new LinkedHashMap<>();
        modules.put("com.acme:root", dir);
        modules.put("npm:web", dir.resolve("web"));
        modules.put("com.acme:service", dir.resolve("service"));
        List<String> log = new ArrayList<>();

        ModuleAssets.Result result = ModuleAssets.collect(modules, Ignores.NONE, log::add);

        assertEquals(List.of("logo.svg"), paths(result, "com.acme:root"));
        assertEquals(List.of("public/favicon.svg", "src-tauri/icons/32x32.png"), paths(result, "npm:web"));
        assertEquals(List.of("src/main/resources/static/img/hero.webp"), paths(result, "com.acme:service"));
        assertEquals("assets/npm-web/public/favicon.svg", result.byModule().get("npm:web").getFirst().getHref());
        assertEquals(dir.resolve("web/public/favicon.svg"), result.copy().get("assets/npm-web/public/favicon.svg"));
        assertEquals("assets/m/Component%201%23a.svg", ModuleAssets.encode("assets/m/Component 1#a.svg"));
    }

    @Test
    void repositoryUrlsForTheWeb() {
        assertEquals("https://github.com/acme/shop", SiteBranding.webUrl("git@github.com:acme/shop.git"));
        assertEquals("https://github.com/acme/shop", SiteBranding.webUrl("https://github.com/acme/shop.git"));
        assertEquals("https://gitlab.com/acme/shop", SiteBranding.webUrl("ssh://git@gitlab.com/acme/shop.git"));
        assertEquals("https://github.com/acme/shop", SiteBranding.webUrl("https://token@github.com/acme/shop"));
        assertNull(SiteBranding.webUrl("/srv/git/shop.git"));
    }

    private static List<String> paths(ModuleAssets.Result result, String module) {
        return result.byModule().getOrDefault(module, List.of()).stream().map(AssetEntry::getPath).toList();
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
