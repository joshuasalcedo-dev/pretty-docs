package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.maven.MavenParser;
import io.joshuasalcedo.pretty.maven.model.MavenProjectStructure;
import io.joshuasalcedo.pretty.model.TypeStructure;
import io.joshuasalcedo.pretty.plugin.model.DocumentationSite;
import io.joshuasalcedo.pretty.plugin.model.ModuleDocumentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentationGeneratorTest {

    @TempDir
    Path dir;

    @Test
    void documentsMultiModuleProject() throws IOException {
        DocumentationSite site = generate(Visibility.PROTECTED, true);

        assertEquals(DocumentationSite.SCHEMA_VERSION, site.getSchemaVersion());
        assertEquals("com.example:shop", site.getRootModuleId());
        assertEquals(List.of("com.example:shop", "com.example:core", "com.example:web"),
                site.getModules().stream().map(ModuleDocumentation::getId).toList());

        ModuleDocumentation core = module(site, "com.example:core");
        assertEquals("com.example:shop", core.getParentId());
        assertEquals("core", core.getRelativePath());
        assertEquals("core/src/main/java/com/example/core/Engine.java", core.getFiles().getFirst().getFilePath());

        TypeStructure engine = core.getFiles().getFirst().getTypes().getFirst();
        assertEquals(List.of("start"), engine.getMethods().stream().map(m -> m.getName()).toList(),
                "the private helper is below protected visibility");
        assertTrue(engine.getFields().isEmpty());
        assertEquals(List.of("com.example.web.Garage"), engine.getUsedBy(), "usedBy spans modules");
        assertNotNull(engine.getMethods().getFirst().getBody());

        assertEquals(1, core.getStatistics().getTypes());
        assertEquals(2, core.getStatistics().getDocumentable(), "type + start()");
        assertEquals(2, core.getStatistics().getDocumented());

        ModuleDocumentation web = module(site, "com.example:web");
        assertEquals(List.of("Garage"), web.getFiles().stream().flatMap(f -> f.getTypes().stream()).map(TypeStructure::getName).toList(),
                "package-private Hidden is dropped and its file with it");
    }

    @Test
    void privateVisibilityKeepsEverythingAndBodiesCanBeDropped() throws IOException {
        DocumentationSite site = generate(Visibility.PRIVATE, false);

        TypeStructure engine = module(site, "com.example:core").getFiles().getFirst().getTypes().getFirst();
        assertEquals(2, engine.getMethods().size());
        assertEquals(1, engine.getFields().size());
        assertNull(engine.getMethods().getFirst().getBody());
        assertEquals(2, module(site, "com.example:web").getFiles().size());
    }

    @Test
    void writesAndServesSite() throws Exception {
        DocumentationSite site = generate(Visibility.PROTECTED, true);
        Path out = dir.resolve("target/pretty-docs");

        Path index = new SiteWriter().write(site, out);

        assertTrue(Files.exists(index));
        assertTrue(Files.readString(out.resolve("docs-data.js")).startsWith("window.__PRETTY_DOCS__ = {"));
        assertTrue(Files.readString(out.resolve("docs.json")).contains("\"rootModuleId\": \"com.example:shop\""));

        new SiteWriter().write(site, out); // regenerating over our own output is allowed

        try (StaticSiteServer server = new StaticSiteServer(out, "localhost", 0)) {
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://localhost:" + server.port();
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"));
            HttpResponse<String> data = client.send(HttpRequest.newBuilder(URI.create(base + "/docs-data.js")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(data.body().contains("com.example:core"));
            HttpResponse<String> escape = client.send(HttpRequest.newBuilder(URI.create(base + "/../pom.xml")).build(), HttpResponse.BodyHandlers.ofString());
            assertNotEquals(200, escape.statusCode());
        }
    }

    @Test
    void refusesToOverwriteForeignDirectory() throws IOException {
        Path out = Files.createDirectories(dir.resolve("important"));
        Files.writeString(out.resolve("notes.txt"), "keep me");

        assertThrows(IOException.class, () -> new SiteWriter().write(generate(Visibility.PUBLIC, true), out));
        assertTrue(Files.exists(out.resolve("notes.txt")));
    }

    @Test
    void resolvedModeLinksAcrossModulesAndReadsReadme() throws IOException {
        write("README.md", "# Shop\n\nThe *shop*.");
        write("web/src/main/java/com/example/web/Garage.java", """
                package com.example.web;
                import com.example.core.*;
                public class Garage { public Engine engine() { return new Engine(); } }
                """);
        DocumentationSite site = generateResolved();

        assertEquals("resolved", site.getMode());
        TypeStructure garage = module(site, "com.example:web").getFiles().getFirst().getTypes().getFirst();
        assertEquals("com.example.core.Engine", garage.getMethods().getFirst().getReturnType().getQualifiedName(),
                "wildcard import into another module, resolved from source");
        assertTrue(garage.getInheritedMethods().stream().anyMatch(m -> m.getMethodName().equals("toString")));
        assertTrue(module(site, "com.example:shop").getReadmeHtml().contains("<em>shop</em>"));
        assertNull(module(site, "com.example:core").getReadmeHtml());
    }

    @Test
    void generatesAndRendersPlantUmlDiagrams() throws IOException {
        DocumentationSite site = generate(Visibility.PROTECTED, true);
        List<String> warnings = new ArrayList<>();

        List<DiagramGenerator.Diagram> diagrams = new DiagramGenerator(true, warnings::add).generate(site);

        assertEquals(List.of("modules", "package-core-com-example-core", "package-web-com-example-web"),
                diagrams.stream().map(d -> d.structure().getId()).toList());
        DiagramGenerator.Diagram modules = diagrams.getFirst();
        assertTrue(modules.puml().contains("component \"web\\n<size:10>jar · 1 types</size>\" as e_com_example_web"), modules.puml());
        assertFalse(modules.puml().contains("-->"), "the fixture POMs declare no dependencies between modules");
        DiagramGenerator.Diagram web = diagrams.get(2);
        assertTrue(web.puml().contains("set separator none"));
        assertTrue(web.puml().contains("class \"Garage\" as e_com_example_web_Garage << (C,#dce8fb) >>"), web.puml());
        assertNotNull(web.svg(), "rendered without Graphviz: " + warnings);
        assertTrue(new String(web.svg(), java.nio.charset.StandardCharsets.UTF_8).contains("Garage"));
        assertEquals("diagrams/package-web-com-example-web.svg", web.structure().getSvgPath());
        assertTrue(warnings.isEmpty(), warnings.toString());

        Path out = dir.resolve("target/site-with-diagrams");
        new SiteWriter().write(site, out, diagrams);
        assertTrue(Files.exists(out.resolve("diagrams/package-web-com-example-web.puml")));
        assertTrue(Files.exists(out.resolve("diagrams/package-web-com-example-web.svg")));
    }

    @Test
    void moduleGraphWithUnconnectedModulesRenders() throws IOException {
        DocumentationSite site = generate(Visibility.PROTECTED, true);
        // web -> core is connected; the parent POM has no dependency edges: the mix Smetana crashed on
        module(site, "com.example:web").getProject().setDependencies(List.of(
                io.joshuasalcedo.pretty.maven.model.DependencyStructure.builder()
                        .groupId("com.example").artifactId("core").version("1.0").scope("compile").build()));
        List<String> warnings = new ArrayList<>();

        DiagramGenerator.Diagram modules = new DiagramGenerator(true, warnings::add).generate(site).getFirst();

        assertTrue(modules.puml().contains("e_com_example_web --> e_com_example_core"), modules.puml());
        assertFalse(modules.puml().contains("as e_com_example_shop"), "unconnected modules are not graph nodes");
        assertTrue(modules.puml().contains("No dependencies on other modules: shop"));
        assertNotNull(modules.svg(), warnings.toString());
        assertFalse(new String(modules.svg(), java.nio.charset.StandardCharsets.UTF_8).contains("has crashed"));
    }

    @Test
    void countsLinesPerModule() throws IOException {
        ModuleDocumentation core = module(generate(Visibility.PUBLIC, true), "com.example:core");

        assertEquals(8, core.getStatistics().getTotalLines());
        assertEquals(6, core.getStatistics().getCodeLines(), "private members still count as code");
        assertEquals(2, core.getStatistics().getCommentLines());
        assertEquals(java.util.Map.of("CLASS", 1), core.getStatistics().getTypesByKind());
    }

    @Test
    void parsesVisibility() {
        assertEquals(Visibility.PACKAGE, Visibility.parse(" Package "));
        assertThrows(IllegalArgumentException.class, () -> Visibility.parse("friends"));
    }

    /** Like {@link #generate} but keeps files already written by the test (e.g. a wildcard-import Garage). */
    private DocumentationSite generateResolved() throws IOException {
        String garage = Files.readString(dir.resolve("web/src/main/java/com/example/web/Garage.java"));
        generate(Visibility.PROTECTED, true); // writes the fixture project (overwrites Garage)
        write("web/src/main/java/com/example/web/Garage.java", garage);
        MavenProjectStructure root = MavenParser.read(dir);
        List<DocumentationGenerator.ModuleInput> inputs = new ArrayList<>();
        inputs.add(input(root));
        root.getChildren().forEach(child -> inputs.add(input(child)));
        return new DocumentationGenerator(Visibility.PROTECTED, true, "test").generate("Shop", dir, inputs,
                new DocumentationGenerator.ResolveOptions(List.of(), java.nio.charset.StandardCharsets.UTF_8), m -> { });
    }

    private DocumentationSite generate(Visibility visibility, boolean bodies) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0</version>
                  <packaging>pom</packaging><modules><module>core</module><module>web</module></modules></project>
                """);
        write("core/pom.xml", """
                <project><parent><groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0</version></parent>
                  <artifactId>core</artifactId></project>
                """);
        write("core/src/main/java/com/example/core/Engine.java", """
                package com.example.core;
                /** An engine. */
                public class Engine {
                    private int rpm;
                    /** Starts it. */
                    public void start() { spin(); }
                    private void spin() { rpm++; }
                }
                """);
        write("web/pom.xml", """
                <project><parent><groupId>com.example</groupId><artifactId>shop</artifactId><version>1.0</version></parent>
                  <artifactId>web</artifactId></project>
                """);
        write("web/src/main/java/com/example/web/Garage.java", """
                package com.example.web;
                import com.example.core.Engine;
                public class Garage { public Engine engine() { return new Engine(); } }
                """);
        write("web/src/main/java/com/example/web/Hidden.java", "package com.example.web;\nclass Hidden {}\n");

        MavenProjectStructure root = MavenParser.read(dir);
        List<DocumentationGenerator.ModuleInput> inputs = new ArrayList<>();
        inputs.add(input(root));
        root.getChildren().forEach(child -> inputs.add(input(child)));
        return new DocumentationGenerator(visibility, bodies, "test").generate("Shop", dir, inputs);
    }

    private static DocumentationGenerator.ModuleInput input(MavenProjectStructure project) {
        Path basedir = Path.of(project.getBasedir());
        return new DocumentationGenerator.ModuleInput(project.getGroupId() + ":" + project.getArtifactId(), basedir, project,
                List.of(basedir.resolve("src/main/java")), List.of());
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static ModuleDocumentation module(DocumentationSite site, String id) {
        return site.getModules().stream().filter(m -> m.getId().equals(id)).findFirst().orElseThrow();
    }
}
