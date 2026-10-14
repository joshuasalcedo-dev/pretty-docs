package io.joshuasalcedo.pretty.maven;

import io.joshuasalcedo.pretty.maven.model.DependencyStructure;
import io.joshuasalcedo.pretty.maven.model.MavenProjectStructure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MavenParserTest {

    @Test
    void readsModuleTreeWithInheritanceAndInterpolation(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>shop-parent</artifactId>
                  <version>${revision}</version>
                  <packaging>pom</packaging>
                  <name>Shop</name>
                  <description>
                    An example
                    shop.
                  </description>
                  <properties>
                    <revision>2.1.0</revision>
                    <lib.version>${revision}-lib</lib.version>
                  </properties>
                  <licenses><license><name>MIT</name></license></licenses>
                  <developers><developer><id>dev</id><roles><role>owner</role></roles></developer></developers>
                  <scm><url>https://example.com/${project.artifactId}</url></scm>
                  <modules>
                    <module>core</module>
                    <module>apps</module>
                  </modules>
                </project>
                """);
        module(dir, "core", """
                <project>
                  <parent><groupId>com.example</groupId><artifactId>shop-parent</artifactId><version>${revision}</version></parent>
                  <artifactId>shop-core</artifactId>
                  <dependencies>
                    <dependency><groupId>com.example</groupId><artifactId>lib</artifactId><version>${lib.version}</version></dependency>
                    <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
                  </dependencies>
                  <build><plugins><plugin><artifactId>maven-jar-plugin</artifactId>
                    <executions><execution><goals><goal>jar</goal><goal>test-jar</goal></goals></execution></executions>
                  </plugin></plugins></build>
                </project>
                """);
        module(dir, "apps", """
                <project>
                  <parent><groupId>com.example</groupId><artifactId>shop-parent</artifactId><version>${revision}</version></parent>
                  <artifactId>shop-apps</artifactId>
                  <packaging>pom</packaging>
                  <modules><module>web</module></modules>
                </project>
                """);
        module(dir.resolve("apps"), "web", """
                <project>
                  <parent><groupId>com.example</groupId><artifactId>shop-apps</artifactId><version>${revision}</version></parent>
                  <artifactId>shop-web</artifactId>
                </project>
                """);

        MavenProjectStructure root = MavenParser.read(dir);

        assertEquals("2.1.0", root.getVersion());
        assertEquals("An example shop.", root.getDescription());
        assertEquals("https://example.com/shop-parent", root.getScm().getUrl());
        assertEquals("MIT", root.getLicenses().getFirst().getName());
        assertEquals(List.of("owner"), root.getDevelopers().getFirst().getRoles());
        assertEquals(List.of("shop-core", "shop-apps"), root.getChildren().stream().map(MavenProjectStructure::getArtifactId).toList());

        MavenProjectStructure core = root.getChildren().getFirst();
        assertEquals("com.example", core.getGroupId(), "groupId is inherited");
        assertEquals("2.1.0", core.getVersion(), "version is inherited and interpolated");
        assertEquals("2.1.0", core.getParent().getVersion());
        DependencyStructure lib = core.getDependencies().getFirst();
        assertEquals("2.1.0-lib", lib.getVersion(), "parent properties are visible to modules");
        assertEquals("compile", lib.getScope());
        assertEquals("test", core.getDependencies().get(1).getScope());
        assertEquals(List.of("jar", "test-jar"), core.getPlugins().getFirst().getGoals());
        assertEquals(dir.resolve("core").toAbsolutePath().normalize().toString(), core.getBasedir());

        MavenProjectStructure web = root.getChildren().get(1).getChildren().getFirst();
        assertEquals("shop-web", web.getArtifactId());
        assertEquals("2.1.0", web.getVersion(), "properties are inherited through two levels");
    }

    @Test
    void reportsMissingModules(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                  <modules><module>missing</module></modules></project>
                """);

        IOException error = assertThrows(IOException.class, () -> MavenParser.read(dir));
        assertTrue(error.getMessage().contains("missing"));
    }

    @Test
    void leavesUnknownPlaceholdersAlone() {
        assertEquals("${unknown}-1", MavenParser.interpolate("${unknown}-${v}", Map.of("v", "1")));
        assertEquals("a-b-c", MavenParser.interpolate("${x}", Map.of("x", "a-${y}", "y", "b-${z}", "z", "c")));
    }

    @Test
    void readsThisRepository() throws IOException {
        MavenProjectStructure root = MavenParser.read(Path.of("..").toAbsolutePath().normalize());

        assertEquals("pretty-docs-parent", root.getArtifactId());
        assertTrue(root.getChildren().stream().anyMatch(c -> c.getArtifactId().equals("pretty-maven")));
        assertFalse(root.getVersion().contains("${"), "revision is interpolated");
    }

    private static void module(Path parent, String name, String pom) throws IOException {
        Files.writeString(Files.createDirectories(parent.resolve(name)).resolve("pom.xml"), pom);
    }
}
