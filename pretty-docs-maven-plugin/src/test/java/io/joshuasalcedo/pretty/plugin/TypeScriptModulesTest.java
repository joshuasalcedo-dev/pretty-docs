package io.joshuasalcedo.pretty.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypeScriptModulesTest {

    @TempDir
    Path dir;

    @Test
    void typeScriptFormsItsOwnTreeOfWorkspacesAndPackages() throws IOException {
        write("pom.xml", "<project/>");
        write("frontend/package.json", "{\"name\": \"frontend\", \"private\": true, \"workspaces\": [\"apps/*\", \"packages/*\"]}");
        write("frontend/apps/web/package.json", "{\"name\": \"@acme/web\", \"dependencies\": {\"@acme/ui\": \"workspace:*\"}}");
        write("frontend/apps/web/tsconfig.json", "{}");
        write("frontend/packages/ui/package.json", "{\"name\": \"@acme/ui\"}");
        write("frontend/packages/ui/tsconfig.json", "{}");
        write("frontend/packages/js-only/package.json", "{\"name\": \"js-only\"}");
        write("frontend/examples/demo/package.json", "{\"name\": \"demo\"}"); // inside the folder, not a member
        write("frontend/examples/demo/tsconfig.json", "{}");
        write("tools/cli/package.json", "{\"name\": \"cli\"}");
        write("tools/cli/tsconfig.json", "{}");
        write("scripts/package.json", "{\"workspaces\": [\"*\"]}"); // a workspace without TypeScript

        List<TypeScriptModules.Found> found = TypeScriptModules.discover(dir, List.of(dir), Ignores.NONE);
        Map<String, TypeScriptModules.Found> byId = new HashMap<>();
        found.forEach(f -> byId.put(f.id(), f));
        assertEquals(List.of("npm:@acme/ui", "npm:@acme/web", "npm:cli", "npm:demo", "npm:frontend"), byId.keySet().stream().sorted().toList());
        assertFalse(byId.get("npm:frontend").sources());
        assertEquals("npm workspace", byId.get("npm:frontend").project().getPackaging());
        assertEquals("typescript", byId.get("npm:@acme/web").project().getPackaging());

        Map<String, String> parents = TypeScriptModules.parents(found);
        assertEquals("npm:frontend", parents.get("npm:@acme/web"));
        assertEquals("npm:frontend", parents.get("npm:@acme/ui"));
        assertNull(parents.get("npm:frontend")); // never a Maven module
        assertNull(parents.get("npm:cli"));
        assertNull(parents.get("npm:demo"));
    }

    @Test
    void explicitProjectsAndNoWorkspaceGrouping() throws IOException {
        write("frontend/package.json", "{\"name\": \"frontend\", \"workspaces\": [\"apps/*\"]}");
        write("frontend/apps/web/package.json", "{\"name\": \"web\"}");
        write("frontend/apps/web/tsconfig.json", "{}");
        write("frontend/apps/admin/package.json", "{\"name\": \"admin\"}");
        write("frontend/apps/admin/tsconfig.json", "{}");
        List<String> log = new java.util.ArrayList<>();

        List<TypeScriptModules.Found> chosen = TypeScriptModules.discover(dir, List.of(dir), Ignores.NONE,
                List.of("frontend/apps/web", "missing"), true, log::add);
        assertEquals(List.of("npm:frontend", "npm:web"), chosen.stream().map(TypeScriptModules.Found::id).sorted().toList());
        assertEquals(List.of("TypeScript project missing has no tsconfig.json; left out"), log);

        List<TypeScriptModules.Found> flat = TypeScriptModules.discover(dir, List.of(dir), Ignores.NONE, List.of(), false, log::add);
        assertEquals(List.of("npm:admin", "npm:web"), flat.stream().map(TypeScriptModules.Found::id).sorted().toList());
        assertTrue(TypeScriptModules.parents(flat).values().stream().allMatch(java.util.Objects::isNull));
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
