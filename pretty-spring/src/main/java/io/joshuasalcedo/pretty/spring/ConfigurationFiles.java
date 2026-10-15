package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.spring.model.ConfigurationEntry;
import io.joshuasalcedo.pretty.spring.model.ConfigurationFileStructure;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentLine;
import org.yaml.snakeyaml.nodes.*;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds and reads Spring Boot configuration files: {@code application*} and {@code bootstrap*} in
 * .yml, .yaml or .properties, at the root of a resource directory or under {@code config/}.
 * YAML keeps comments (as key descriptions), multiple documents and their
 * {@code spring.config.activate.on-profile}; values keep their placeholders.
 */
public final class ConfigurationFiles {

    private static final Pattern FILE = Pattern.compile("(application|bootstrap)(-([\\w.-]+))?\\.(ya?ml|properties)");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:}]+)(?::(.*))?}$");

    private ConfigurationFiles() {
    }

    /**
     * @param moduleId     module the resources belong to
     * @param resourceDirs the module's resource directories (missing ones are ignored)
     * @param root         the documented project root; paths are reported relative to it
     */
    public static List<ConfigurationFileStructure> read(String moduleId, List<Path> resourceDirs, Path root) throws IOException {
        List<ConfigurationFileStructure> files = new ArrayList<>();
        for (Path dir : resourceDirs) {
            for (Path candidate : List.of(dir, dir.resolve("config"))) {
                if (!Files.isDirectory(candidate)) {
                    continue;
                }
                try (Stream<Path> list = Files.list(candidate)) {
                    for (Path file : list.filter(Files::isRegularFile).sorted().toList()) {
                        Matcher name = FILE.matcher(file.getFileName().toString());
                        if (name.matches()) {
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            ConfigurationFileStructure parsed = parse(file.getFileName().toString(), content);
                            parsed.setModuleId(moduleId);
                            Path absolute = file.toAbsolutePath().normalize();
                            Path base = root.toAbsolutePath().normalize();
                            parsed.setPath((absolute.startsWith(base) ? base.relativize(absolute) : absolute).toString().replace('\\', '/'));
                            files.add(parsed);
                        }
                    }
                }
            }
        }
        return files;
    }

    /** Parses one file's content; the profile comes from the name (application-prod.yml). */
    public static ConfigurationFileStructure parse(String fileName, String content) {
        Matcher name = FILE.matcher(fileName);
        String profile = name.matches() ? name.group(3) : null;
        boolean yaml = fileName.endsWith(".yml") || fileName.endsWith(".yaml");
        List<ConfigurationEntry> entries = yaml ? parseYaml(content, profile) : parseProperties(content, profile);
        return ConfigurationFileStructure.builder()
                .fileName(fileName)
                .format(yaml ? "yaml" : "properties")
                .profile(profile)
                .content(content)
                .entries(entries)
                .build();
    }

    // ------------------------------------------------------------------ YAML

    private static List<ConfigurationEntry> parseYaml(String content, String fileProfile) {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        List<ConfigurationEntry> entries = new ArrayList<>();
        try {
            for (Node document : new Yaml(options).composeAll(new StringReader(content))) {
                List<ConfigurationEntry> documentEntries = new ArrayList<>();
                flatten(document, "", null, documentEntries);
                String documentProfile = documentEntries.stream()
                        .filter(e -> e.getKey().equals("spring.config.activate.on-profile") || e.getKey().equals("spring.profiles"))
                        .map(ConfigurationEntry::getValue).findFirst().orElse(fileProfile);
                documentEntries.forEach(e -> e.setProfile(documentProfile));
                entries.addAll(documentEntries);
            }
        } catch (RuntimeException e) {
            // Invalid YAML: show the file without keys rather than failing the documentation build.
        }
        return entries;
    }

    private static void flatten(Node node, String prefix, String comment, List<ConfigurationEntry> into) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (!(tuple.getKeyNode() instanceof ScalarNode keyNode)) {
                    continue;
                }
                String key = prefix.isEmpty() ? keyNode.getValue() : prefix + "." + keyNode.getValue();
                String keyComment = comment(keyNode.getBlockComments());
                Node value = tuple.getValueNode();
                if (value instanceof ScalarNode scalar) {
                    into.add(entry(key, scalar.getValue(), keyNode.getStartMark().getLine() + 1, keyComment != null ? keyComment : comment));
                } else {
                    flatten(value, key, keyComment, into);
                }
            }
        } else if (node instanceof SequenceNode sequence) {
            List<Node> items = sequence.getValue();
            for (int i = 0; i < items.size(); i++) {
                Node item = items.get(i);
                String key = prefix + "[" + i + "]";
                if (item instanceof ScalarNode scalar) {
                    into.add(entry(key, scalar.getValue(), item.getStartMark().getLine() + 1, comment));
                } else {
                    flatten(item, key, i == 0 ? comment : null, into);
                }
            }
        }
    }

    private static String comment(List<CommentLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return null;
        }
        String text = lines.stream().map(CommentLine::getValue).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "));
        return text.isEmpty() ? null : text;
    }

    // ------------------------------------------------------------ properties

    private static List<ConfigurationEntry> parseProperties(String content, String fileProfile) {
        List<ConfigurationEntry> entries = new ArrayList<>();
        List<String> lines = content.lines().toList();
        StringBuilder comment = new StringBuilder();
        String documentProfile = fileProfile;
        int documentStart = 0;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.equals("#---") || line.equals("!---")) { // multi-document properties
                documentProfile = fileProfile;
                documentStart = entries.size();
                comment.setLength(0);
                continue;
            }
            if (line.isEmpty()) {
                comment.setLength(0);
                continue;
            }
            if (line.startsWith("#") || line.startsWith("!")) {
                comment.append(comment.isEmpty() ? "" : " ").append(line.substring(1).strip());
                continue;
            }
            int lineNumber = i + 1;
            StringBuilder logical = new StringBuilder(line);
            while (logical.toString().endsWith("\\") && i + 1 < lines.size()) {
                logical.setLength(logical.length() - 1);
                logical.append(lines.get(++i).strip());
            }
            String text = logical.toString();
            int separator = -1;
            for (int c = 0; c < text.length(); c++) {
                char ch = text.charAt(c);
                if (ch == '\\') {
                    c++;
                } else if (ch == '=' || ch == ':' || Character.isWhitespace(ch)) {
                    separator = c;
                    break;
                }
            }
            String key = (separator < 0 ? text : text.substring(0, separator)).replace("\\", "").strip();
            String value = separator < 0 ? "" : text.substring(separator + 1).replaceFirst("^[\\s=:]*", "").strip();
            entries.add(entry(key, value, lineNumber, comment.isEmpty() ? null : comment.toString()));
            comment.setLength(0);
            if (key.equals("spring.config.activate.on-profile") || key.equals("spring.profiles")) {
                documentProfile = value;
                for (int e = documentStart; e < entries.size(); e++) {
                    entries.get(e).setProfile(documentProfile);
                }
            }
            entries.getLast().setProfile(documentProfile);
        }
        return entries;
    }

    private static ConfigurationEntry entry(String key, String value, int line, String comment) {
        Matcher placeholder = PLACEHOLDER.matcher(value == null ? "" : value.strip());
        boolean single = placeholder.matches();
        return ConfigurationEntry.builder()
                .key(key)
                .value(value == null ? "" : value)
                .line(line)
                .comment(comment)
                .environmentVariable(single ? placeholder.group(1) : null)
                .placeholderDefault(single ? placeholder.group(2) : null)
                .build();
    }

    // -------------------------------------------------------------- lookups

    /**
     * Spring's relaxed binding, approximately: case, dashes and underscores do not matter, and list
     * indexes are ignored ({@code accounts[0].e-mail} matches {@code accounts.email}).
     */
    public static String canonical(String key) {
        return key.replaceAll("\\[\\d+]", "").replace("-", "").replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * The values a module's configuration gives for the active profiles: the default document(s)
     * first, then each active profile in order, later ones overriding.
     */
    public static Map<String, String> effectiveValues(List<ConfigurationFileStructure> files, List<String> activeProfiles) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        order.add(null);
        order.addAll(activeProfiles);
        for (String profile : order) {
            for (ConfigurationFileStructure file : files) {
                if (!file.getFileName().startsWith("application")) {
                    continue;
                }
                for (ConfigurationEntry entry : file.getEntries()) {
                    if (Objects.equals(entry.getProfile(), profile)) {
                        values.put(canonical(entry.getKey()), resolved(entry));
                    }
                }
            }
        }
        return values;
    }

    /**
     * The value with a ${VAR:default} placeholder replaced by its default; a placeholder without a
     * default stays as written (only the environment knows it).
     */
    static String resolved(ConfigurationEntry entry) {
        return entry.getEnvironmentVariable() != null && entry.getPlaceholderDefault() != null ? entry.getPlaceholderDefault() : entry.getValue();
    }
}
