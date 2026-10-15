package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.spring.ConfigurationFiles;
import io.joshuasalcedo.pretty.spring.ProjectLayout;
import io.joshuasalcedo.pretty.spring.model.ConfigurationFileStructure;
import io.joshuasalcedo.pretty.spring.model.ConfigurationKeyMetadata;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Collects what the Spring analysis needs from the build besides sources: which module each source
 * root belongs to, module dependencies, the auto-configurations modules register, their
 * configuration files, and the configuration keys libraries on the classpath document.
 */
final class SpringLayoutReader {

    static final String AUTO_CONFIGURATION_IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
    static final String SPRING_FACTORIES = "META-INF/spring.factories";
    private static final String ENABLE_AUTO_CONFIGURATION = "org.springframework.boot.autoconfigure.EnableAutoConfiguration";
    private static final List<String> METADATA = List.of(
            "META-INF/spring-configuration-metadata.json", "META-INF/additional-spring-configuration-metadata.json");

    private SpringLayoutReader() {
    }

    static ProjectLayout read(Path root, List<DocumentationGenerator.ModuleInput> modules, List<Path> classpath, Consumer<String> log)
            throws IOException {
        Map<String, String> moduleOfSourceRoot = new LinkedHashMap<>();
        Map<String, List<String>> dependencies = new LinkedHashMap<>();
        Map<String, List<String>> autoConfigurations = new LinkedHashMap<>();
        List<ConfigurationFileStructure> files = new ArrayList<>();
        Map<String, ConfigurationKeyMetadata> keys = new TreeMap<>();
        for (DocumentationGenerator.ModuleInput module : modules) {
            for (Path sourceRoot : concat(module.sourceRoots(), module.testSourceRoots())) {
                moduleOfSourceRoot.put(sourceRoot.toAbsolutePath().normalize().toString().replace('\\', '/'), module.id());
            }
            dependencies.put(module.id(), module.dependsOn());
            List<String> registered = new ArrayList<>();
            for (Path resources : module.resourceDirs()) {
                registered.addAll(autoConfigurations(resources));
                for (String name : METADATA) {
                    Path metadata = resources.resolve(name);
                    if (Files.isRegularFile(metadata)) {
                        try (Reader reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
                            readMetadata(reader, keys);
                        } catch (RuntimeException e) {
                            log.accept("Could not read " + metadata + ": " + e.getMessage());
                        }
                    }
                }
            }
            autoConfigurations.put(module.id(), registered.stream().distinct().toList());
            files.addAll(ConfigurationFiles.read(module.id(), module.resourceDirs(), root));
        }
        for (Path entry : classpath) {
            if (Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".jar")) {
                try (ZipFile jar = new ZipFile(entry.toFile())) {
                    for (String name : METADATA) {
                        ZipEntry metadata = jar.getEntry(name);
                        if (metadata != null) {
                            try (InputStream in = jar.getInputStream(metadata)) {
                                readMetadata(new java.io.InputStreamReader(in, StandardCharsets.UTF_8), keys);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    log.accept("Could not read configuration metadata from " + entry.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return new ProjectLayout(moduleOfSourceRoot, dependencies, autoConfigurations, files, keys);
    }

    /** Classes listed in AutoConfiguration.imports, plus the EnableAutoConfiguration entry of spring.factories. */
    static List<String> autoConfigurations(Path resources) throws IOException {
        List<String> result = new ArrayList<>();
        Path imports = resources.resolve(AUTO_CONFIGURATION_IMPORTS);
        if (Files.isRegularFile(imports)) {
            for (String line : Files.readAllLines(imports, StandardCharsets.UTF_8)) {
                String name = line.replaceAll("#.*", "").strip();
                if (!name.isEmpty()) {
                    result.add(name);
                }
            }
        }
        Path factories = resources.resolve(SPRING_FACTORIES);
        if (Files.isRegularFile(factories)) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(factories, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            String listed = properties.getProperty(ENABLE_AUTO_CONFIGURATION, "");
            Arrays.stream(listed.split(",")).map(String::strip).filter(s -> !s.isEmpty()).forEach(result::add);
        }
        return result;
    }

    /**
     * spring-configuration-metadata.json. JSON is (nearly) YAML, so SnakeYAML reads it once the one
     * escape YAML lacks, {@code \/}, is rewritten.
     */
    @SuppressWarnings("unchecked")
    static void readMetadata(Reader reader, Map<String, ConfigurationKeyMetadata> into) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        String json = new java.io.BufferedReader(reader).lines().collect(java.util.stream.Collectors.joining("\n"));
        json = json.replaceAll("(?<!\\\\)((?:\\\\\\\\)*)\\\\/", "$1/");
        Object loaded = new Yaml(options).load(json);
        if (!(loaded instanceof Map<?, ?> document) || !(document.get("properties") instanceof List<?> properties)) {
            return;
        }
        for (Object item : properties) {
            if (item instanceof Map<?, ?> raw && raw.get("name") instanceof String name) {
                Map<String, Object> property = (Map<String, Object>) raw;
                Object defaultValue = property.get("defaultValue");
                into.putIfAbsent(name, ConfigurationKeyMetadata.builder()
                        .name(name)
                        .type((String) property.get("type"))
                        .description((String) property.get("description"))
                        .defaultValue(defaultValue == null ? null : String.valueOf(defaultValue))
                        .deprecated(Boolean.TRUE.equals(property.get("deprecated")) || property.get("deprecation") != null)
                        .sourceType((String) property.get("sourceType"))
                        .build());
            }
        }
    }

    private static List<Path> concat(List<Path> a, List<Path> b) {
        List<Path> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
