package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.spring.model.*;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Cross-references the keys configuration files set with what reads them: the project's
 * {@code @ConfigurationProperties} and {@code @Value}, and the keys libraries document in
 * {@code spring-configuration-metadata.json}. Reports keys nothing reads (with the known key they
 * are closest to) and {@code @Value} placeholders without a default that no file sets.
 */
final class ConfigurationReport {

    record Result(List<ConfigurationKey> keys, List<BeanIssue> issues) {
    }

    /** What reads a key, or every key under it when it is a map, list or nested object. */
    private record Known(String key, ConfigurationKey.Status status, String description, String typeName, String defaultValue,
                         boolean deprecated, String boundTo) {
    }

    private static final Pattern CONTAINER = Pattern.compile(
            "^(java\\.util\\.)?(Map|SortedMap|HashMap|LinkedHashMap|TreeMap|List|ArrayList|Set|SortedSet|Collection|Properties)\\b.*|.*\\[]$");
    private static final Set<String> SCALARS = Set.of(
            "boolean", "byte", "short", "int", "long", "float", "double", "char", "String", "Boolean", "Byte", "Short", "Integer",
            "Long", "Float", "Double", "Character", "BigDecimal", "BigInteger", "Duration", "Period", "DataSize", "Charset", "Locale",
            "File", "Path", "URI", "URL", "Class", "Pattern", "InetAddress", "UUID", "Instant", "LocalDate", "LocalDateTime",
            "LocalTime", "Resource", "ZoneId", "TimeZone", "Object");

    private ConfigurationReport() {
    }

    static Result analyze(List<ConfigurationPropertiesStructure> properties, List<PropertyUsage> usages,
                          List<ConfigurationFileStructure> files, Map<String, ConfigurationKeyMetadata> library) {
        Map<String, List<Known>> exact = new HashMap<>(); // canonical key -> readers
        Map<String, List<Known>> prefixes = new HashMap<>(); // canonical prefix -> readers of anything under it
        for (ConfigurationPropertiesStructure type : properties) {
            for (ConfigurationProperty property : type.getProperties()) {
                Known known = new Known(property.getKey(), ConfigurationKey.Status.PROJECT, property.getDescription(), property.getTypeName(),
                        property.getDefaultValue(), false, type.getTypeName());
                add(exact, property.getKey(), known);
                String raw = rawType(property.getTypeName());
                if (CONTAINER.matcher(property.getTypeName()).matches() || !SCALARS.contains(raw)) {
                    add(prefixes, property.getKey(), known); // a map, list or object the analysis did not expand
                }
            }
        }
        for (PropertyUsage usage : usages) {
            add(exact, usage.getKey(), new Known(usage.getKey(), ConfigurationKey.Status.PROJECT, null, null, usage.getDefaultValue(), false,
                    usage.getTypeName() + (usage.getMemberName() != null ? "#" + usage.getMemberName() : "")));
        }
        for (ConfigurationKeyMetadata metadata : library.values()) {
            Known known = new Known(metadata.getName(), ConfigurationKey.Status.LIBRARY, metadata.getDescription(), metadata.getType(),
                    metadata.getDefaultValue(), metadata.isDeprecated(), metadata.getSourceType());
            add(exact, metadata.getName(), known);
            if (metadata.getType() != null && CONTAINER.matcher(metadata.getType()).matches()) {
                add(prefixes, metadata.getName(), known);
            }
        }

        Map<String, ConfigurationKey> keys = new LinkedHashMap<>();
        Map<String, ConfigurationValue> firstSeen = new HashMap<>();
        Set<String> commented = new HashSet<>();
        for (ConfigurationFileStructure file : files) {
            for (ConfigurationEntry entry : file.getEntries()) {
                String canonical = ConfigurationFiles.canonical(entry.getKey());
                ConfigurationValue value = ConfigurationValue.builder()
                        .moduleId(file.getModuleId())
                        .path(file.getPath())
                        .profile(entry.getProfile())
                        .line(entry.getLine())
                        .value(entry.getValue())
                        .build();
                ConfigurationKey key = keys.computeIfAbsent(canonical, c -> describe(entry.getKey(), readers(c, exact, prefixes), library.isEmpty()));
                key.getValues().add(value);
                firstSeen.putIfAbsent(canonical, value);
                if (entry.getComment() != null && commented.add(canonical)) {
                    key.setDescription(entry.getComment()); // the project's own comment says more than generic docs
                }
            }
        }

        List<BeanIssue> issues = new ArrayList<>();
        for (Map.Entry<String, ConfigurationKey> entry : keys.entrySet()) {
            ConfigurationKey key = entry.getValue();
            if (key.getStatus() != ConfigurationKey.Status.UNKNOWN) {
                continue;
            }
            key.setSuggestion(closest(entry.getKey(), exact));
            ConfigurationValue at = firstSeen.get(entry.getKey());
            issues.add(BeanIssue.builder()
                    .severity(key.getSuggestion() != null ? BeanIssue.Severity.WARNING : BeanIssue.Severity.INFO)
                    .kind(BeanIssue.Kind.UNKNOWN_PROPERTY)
                    .message(key.getKey() + " (" + at.getPath() + ":" + at.getLine() + ") is not read by any @ConfigurationProperties, @Value or library"
                            + (key.getSuggestion() != null ? "; did you mean " + key.getSuggestion() + "?" : ""))
                    .file(at.getPath())
                    .line(at.getLine())
                    .applications(new ArrayList<>())
                    .build());
        }

        boolean importsConfiguration = keys.containsKey(ConfigurationFiles.canonical("spring.config.import"));
        Set<String> reported = new HashSet<>();
        for (PropertyUsage usage : usages) {
            String canonical = ConfigurationFiles.canonical(usage.getKey());
            ConfigurationKeyMetadata documented = library.get(usage.getKey());
            boolean set = keys.containsKey(canonical) || documented != null && documented.getDefaultValue() != null;
            if (usage.isRequired() && !set && reported.add(canonical + "|" + usage.getTypeName())) {
                issues.add(BeanIssue.builder()
                        .severity(importsConfiguration ? BeanIssue.Severity.INFO : BeanIssue.Severity.WARNING)
                        .kind(BeanIssue.Kind.MISSING_PROPERTY)
                        .typeName(usage.getTypeName())
                        .message(simple(usage.getTypeName()) + (usage.getMemberName() != null ? "." + usage.getMemberName() : "") + " reads ${"
                                + usage.getKey() + "} without a default, and no configuration file sets it: startup fails unless the environment"
                                + (importsConfiguration ? " or the imported configuration (spring.config.import)" : "") + " provides it")
                        .applications(new ArrayList<>())
                        .build());
            }
        }
        return new Result(new ArrayList<>(keys.values()), issues);
    }

    private static void add(Map<String, List<Known>> into, String key, Known known) {
        into.computeIfAbsent(ConfigurationFiles.canonical(key), k -> new ArrayList<>()).add(known);
    }

    /** Readers of a key: exact matches, else the nearest map/list/object property above it. */
    private static List<Known> readers(String canonical, Map<String, List<Known>> exact, Map<String, List<Known>> prefixes) {
        List<Known> found = exact.get(canonical);
        if (found != null) {
            return found;
        }
        for (String prefix = parent(canonical); prefix != null; prefix = parent(prefix)) {
            if (prefixes.containsKey(prefix)) {
                return prefixes.get(prefix);
            }
        }
        return List.of();
    }

    private static String parent(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? null : key.substring(0, dot);
    }

    private static ConfigurationKey describe(String key, List<Known> readers, boolean noLibraryMetadata) {
        Known project = readers.stream().filter(k -> k.status() == ConfigurationKey.Status.PROJECT).findFirst().orElse(null);
        Known first = project != null ? project : readers.isEmpty() ? null : readers.getFirst();
        ConfigurationKey.Status status = first != null ? first.status()
                : noLibraryMetadata ? ConfigurationKey.Status.UNCHECKED : ConfigurationKey.Status.UNKNOWN;
        return ConfigurationKey.builder()
                .key(key)
                .status(status)
                .description(readers.stream().map(Known::description).filter(Objects::nonNull).findFirst().orElse(null))
                .typeName(first == null ? null : first.typeName())
                .defaultValue(readers.stream().map(Known::defaultValue).filter(Objects::nonNull).findFirst().orElse(null))
                .deprecated(readers.stream().anyMatch(Known::deprecated))
                .boundTo(readers.stream().map(Known::boundTo).filter(Objects::nonNull).distinct().toList())
                .values(new ArrayList<>())
                .build();
    }

    /** The known key within a small edit distance of this one, as written by its reader. */
    private static String closest(String canonical, Map<String, List<Known>> exact) {
        int limit = canonical.length() > 20 ? 3 : 2;
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<String, List<Known>> known : exact.entrySet()) {
            String candidate = known.getKey();
            if (Math.abs(candidate.length() - canonical.length()) > limit) {
                continue;
            }
            int distance = distance(canonical, candidate, limit);
            if (distance <= limit && distance < bestDistance) {
                bestDistance = distance;
                best = known.getValue().getFirst().key();
            }
        }
        return best;
    }

    /** Levenshtein distance, giving up (limit + 1) once it exceeds the limit. */
    static int distance(String a, String b, int limit) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static String rawType(String typeName) {
        String raw = typeName == null ? "" : typeName.replaceAll("<.*", "");
        return raw.substring(raw.lastIndexOf('.') + 1);
    }

    private static String simple(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
