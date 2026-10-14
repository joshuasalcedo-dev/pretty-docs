package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.plugin.model.ExternalDocumentation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExternalDocsLinkerTest {

    @TempDir
    Path cache;

    @Test
    void parsesModularAndLegacyElementLists() {
        assertEquals(Map.of("java.io", "java.base", "java.sql", "java.sql"),
                ExternalDocsLinker.parseElementList("module:java.base\njava.io\n\nmodule:java.sql\njava.sql\n"));
        assertEquals(Map.of("com.google.gson", ""), ExternalDocsLinker.parseElementList("com.google.gson\r\n"));
        assertTrue(ExternalDocsLinker.looksLikeElementList("module:a\na.b\n"));
        assertFalse(ExternalDocsLinker.looksLikeElementList("<!doctype html><html>"));
    }

    @Test
    void findsPackagesOfQualifiedNames() {
        assertEquals(Set.of("java.util", "java.util.Map"), ExternalDocsLinker.packagesOf(Set.of("java.util.Map.Entry")));
        assertEquals(Set.of(), ExternalDocsLinker.packagesOf(Set.of("String", "lowercase.only")));
    }

    @Test
    void linksOnlyReferencedPackagesFirstSiteWins() throws Exception {
        ExternalDocsLinker.Candidate jdk = ExternalDocsLinker.jdk(21);
        ExternalDocsLinker.Candidate gson = ExternalDocsLinker.javadocIo("com.google.code.gson", "gson", "2.13.1");
        ExternalDocsLinker.Candidate missing = ExternalDocsLinker.javadocIo("x", "y", "1");
        cache(jdk.baseUrl() + "element-list", "module:java.base\njava.lang\njava.util\njava.io\n");
        cache(gson.baseUrl() + "element-list", "module:com.google.gson\ncom.google.gson\njava.util\n");

        List<ExternalDocumentation> docs = new ExternalDocsLinker(cache, true, m -> { })
                .link(List.of(jdk, gson, missing), Set.of("java.util.List", "java.lang.String", "com.google.gson.Gson", "com.example.Own"));

        assertEquals(2, docs.size());
        assertEquals("https://docs.oracle.com/en/java/javase/21/docs/api/", docs.get(0).getBaseUrl());
        assertEquals(Map.of("java.lang", "java.base", "java.util", "java.base"), docs.get(0).getPackages(), "java.io is not referenced");
        assertEquals(Map.of("com.google.gson", "com.google.gson"), docs.get(1).getPackages(), "java.util was claimed by the JDK");
        assertEquals("gson 2.13.1", docs.get(1).getLabel());
    }

    @Test
    void extractsReferencedTypesFromSiteJson() {
        assertEquals(Set.of("java.util.List", "a.B"),
                ExternalDocsLinker.referencedTypes("{\"qualifiedName\":\"java.util.List\",\"x\":{\"qualifiedDeclaringTypeName\":\"a.B\"}}"));
    }

    @Test
    void rendersReadmeWithGfm(@TempDir Path module) throws IOException {
        Files.writeString(module.resolve("README.md"), """
                # Title

                | a | b |
                |---|---|
                | 1 | 2 |

                ```java
                class A {}
                ```
                Visit https://example.com
                """);

        String html = new ReadmeRenderer().render(module).orElseThrow();

        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("<code class=\"language-java\">"));
        assertTrue(html.contains("href=\"https://example.com\""), html);
        assertTrue(html.contains("id=\"title\""));
        assertTrue(new ReadmeRenderer().render(module.resolve("missing")).isEmpty());
    }

    /** Same cache key as {@link ExternalDocsLinker}. */
    private void cache(String url, String content) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
        Files.writeString(cache.resolve(HexFormat.of().formatHex(digest, 0, 16) + ".txt"), content);
    }
}
