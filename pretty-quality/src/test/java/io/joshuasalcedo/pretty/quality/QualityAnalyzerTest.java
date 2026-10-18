package io.joshuasalcedo.pretty.quality;

import io.joshuasalcedo.pretty.quality.model.QualityFinding;
import io.joshuasalcedo.pretty.quality.model.QualityStructure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QualityAnalyzerTest {

    @TempDir
    Path dir;

    private static final String SOURCE = """
            package com.shop;

            import java.io.FileInputStream;
            import java.io.IOException;

            public class Orders {
                private String name;

                public boolean same(String other) {
                    return name == other; // compares references
                }

                public int read(String path) throws IOException {
                    FileInputStream in = new FileInputStream(path); // never closed
                    return in.read();
                }

                public int length(String text) {
                    if (text == null) {
                        System.out.println("no text");
                    }
                    return text.length(); // null when text is null
                }

                @Override
                public boolean equals(Object o) { // equals without hashCode
                    return o instanceof Orders other && other.name.equals(name);
                }
            }
            """;

    @Test
    void pmdReadsSourcesAndSpotBugsReadsClasses() throws IOException {
        Path sources = dir.resolve("src/main/java");
        Path file = sources.resolve("com/shop/Orders.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Path classes = Files.createDirectories(dir.resolve("target/classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-g", "-d", classes.toString(), file.toString()));

        List<String> log = new ArrayList<>();
        QualityStructure quality = QualityAnalyzer.analyze(
                List.of(new QualityAnalyzer.Module("com.shop:core", List.of(sources), classes)),
                new QualityAnalyzer.Options(true, true, List.of()), log::add);

        assertTrue(quality.getTools().stream().allMatch(QualityStructure.ToolRun::isRan), quality.getTools() + " " + log);
        List<String> pmd = rules(quality, "PMD");
        assertTrue(pmd.contains("CompareObjectsWithEquals"), pmd.toString());
        assertTrue(pmd.contains("CloseResource"), pmd.toString());
        assertTrue(pmd.contains("OverrideBothEqualsAndHashcode") || pmd.contains("OverrideBothEqualsAndHashCodeOnComparable"), pmd.toString());

        List<String> spotbugs = rules(quality, "SpotBugs");
        assertTrue(spotbugs.contains("HE_EQUALS_USE_HASHCODE"), spotbugs.toString());
        assertTrue(spotbugs.stream().anyMatch(r -> r.startsWith("NP_")), spotbugs.toString());

        QualityFinding np = quality.getFindings().stream().filter(f -> f.getRule().startsWith("NP_")).findFirst().orElseThrow();
        assertEquals("com.shop:core", np.getModuleId());
        assertEquals("com.shop.Orders", np.getTypeName());
        assertEquals("length", np.getMemberName());
        assertEquals(file.toAbsolutePath().normalize().toString(), np.getFile());
        assertEquals(22, np.getLine());
    }

    @Test
    void spotBugsIsSkippedWithoutClasses() throws IOException {
        Path sources = Files.createDirectories(dir.resolve("src/main/java"));
        QualityStructure quality = QualityAnalyzer.analyze(
                List.of(new QualityAnalyzer.Module("m", List.of(sources), dir.resolve("target/classes"))),
                new QualityAnalyzer.Options(false, true, List.of()), m -> { });
        QualityStructure.ToolRun spotbugs = quality.getTools().getFirst();
        assertFalse(spotbugs.isRan());
        assertTrue(spotbugs.getNote().contains("mvn compile"));
    }

    @Test
    void readableSpotBugsMessages() {
        assertEquals("Method ServiceUnit.transitionTo(ServiceUnit.LifecycleState, Instant) uses intrinsic lock of class ServiceUnit,"
                        + " exposed by static ServiceUnit.restore(…)",
                QualityAnalyzer.readable("Method swaswa.admin.serviceunit.ServiceUnit.transitionTo(ServiceUnit$LifecycleState, Instant)"
                        + " uses intrinsic lock of class swaswa.admin.serviceunit.ServiceUnit, exposed by static"
                        + " swaswa.admin.serviceunit.ServiceUnit.restore(Lswaswa/admin/serviceunit/ServiceUnitId;Ljava/lang/String;I)Lswaswa/admin/serviceunit/ServiceUnit;"));
    }

    @Test
    void nestedAndAnonymousClassNames() {
        assertEquals("a.b.Outer.Inner", QualityAnalyzer.outerClass("a.b.Outer$Inner"));
        assertEquals("a.b.Outer", QualityAnalyzer.outerClass("a.b.Outer$1"));
    }

    private static List<String> rules(QualityStructure quality, String tool) {
        return quality.getFindings().stream().filter(f -> f.getTool().equals(tool)).map(QualityFinding::getRule).distinct().toList();
    }
}
