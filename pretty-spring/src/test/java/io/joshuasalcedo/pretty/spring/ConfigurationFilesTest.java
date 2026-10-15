package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.spring.model.ConfigurationEntry;
import io.joshuasalcedo.pretty.spring.model.ConfigurationFileStructure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationFilesTest {

    @Test
    void yamlKeepsCommentsListsAndProfileDocuments() {
        ConfigurationFileStructure file = ConfigurationFiles.parse("application.yml", """
                # Port the API listens on.
                server:
                  port: ${PORT:9000}
                app:
                  admins:
                    - alice
                    - bob
                  secret: ${APP_SECRET}
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                server:
                  port: 80
                """);
        assertEquals("yaml", file.getFormat());
        ConfigurationEntry port = entry(file, "server.port", null);
        assertEquals("Port the API listens on.", port.getComment());
        assertEquals("PORT", port.getEnvironmentVariable());
        assertEquals("9000", port.getPlaceholderDefault());
        assertEquals(3, port.getLine());
        assertEquals("bob", entry(file, "app.admins[1]", null).getValue());
        assertEquals("80", entry(file, "server.port", "prod").getValue());

        Map<String, String> defaults = ConfigurationFiles.effectiveValues(List.of(file), List.of());
        assertEquals("9000", defaults.get("server.port"));
        assertEquals("${APP_SECRET}", defaults.get("app.secret"), "no default: left to the environment");
        assertEquals("80", ConfigurationFiles.effectiveValues(List.of(file), List.of("prod")).get("server.port"));
    }

    @Test
    void propertiesWithCommentsContinuationsAndDocuments() {
        ConfigurationFileStructure file = ConfigurationFiles.parse("application-dev.properties", """
                # Where uploads go.
                app.upload-dir=/tmp/uploads
                app.motd = Hello \\
                  world
                #---
                spring.config.activate.on-profile=local
                app.upload-dir: ./uploads
                """);
        assertEquals("dev", file.getProfile());
        assertEquals("Where uploads go.", entry(file, "app.upload-dir", "dev").getComment());
        assertEquals("Hello world", entry(file, "app.motd", "dev").getValue());
        assertEquals("./uploads", entry(file, "app.upload-dir", "local").getValue());
    }

    @Test
    void relaxedBinding() {
        assertEquals(ConfigurationFiles.canonical("app.mailServer.retry_count"), ConfigurationFiles.canonical("app.mail-server.retry-count"));
        assertEquals("app.accounts.email", ConfigurationFiles.canonical("app.accounts[0].e-mail"));
        assertEquals(1, ConfigurationReport.distance("lib.featur.timeout", "lib.feature.timeout", 2));
        assertEquals(3, ConfigurationReport.distance("abc", "xyz123", 2));
    }

    @Test
    void invalidYamlStillShowsTheFile() {
        ConfigurationFileStructure file = ConfigurationFiles.parse("application.yml", "a: [unclosed\n");
        assertEquals("a: [unclosed\n", file.getContent());
        assertTrue(file.getEntries().isEmpty());
    }

    private static ConfigurationEntry entry(ConfigurationFileStructure file, String key, String profile) {
        return file.getEntries().stream()
                .filter(e -> e.getKey().equals(key) && java.util.Objects.equals(e.getProfile(), profile))
                .findFirst().orElseThrow(() -> new AssertionError("no " + key + " in " + profile + ": " + file.getEntries()));
    }
}
