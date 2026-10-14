package io.joshuasalcedo.pretty.plugin;

import io.joshuasalcedo.pretty.plugin.model.ExternalDocumentation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links types outside the project to their published javadoc, the way {@code javadoc -link} does:
 * each candidate site's {@code element-list} (or legacy {@code package-list}) says which packages it
 * documents. Lists are cached on disk; offline builds use the cache only.
 */
public final class ExternalDocsLinker {

    /**
     * A javadoc site to try.
     *
     * @param label   shown in the site, e.g. "gson 2.13.1"
     * @param baseUrl the javadoc root, ending with "/"
     */
    public record Candidate(String label, String baseUrl) {
        public Candidate {
            baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        }
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RETRY_MISSING_AFTER = Duration.ofDays(7);
    private static final Pattern ELEMENT_LINE = Pattern.compile("(module:)?[\\w.$-]+");
    private static final Pattern QUALIFIED_NAME =
            Pattern.compile("\"(?:qualifiedName|qualifiedDeclaringTypeName|qualifiedTypeName)\":\"([\\w.$]+)\"");

    private final Path cacheDirectory;
    private final boolean offline;
    private final Consumer<String> debug;
    private final HttpClient client;

    public ExternalDocsLinker(Path cacheDirectory, boolean offline, Consumer<String> debug) {
        this.cacheDirectory = cacheDirectory;
        this.offline = offline;
        this.debug = debug;
        this.client = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** The Java SE API docs for a feature release. */
    public static Candidate jdk(int feature) {
        return feature <= 10
                ? new Candidate("Java SE " + feature, "https://docs.oracle.com/javase/" + feature + "/docs/api/")
                : new Candidate("Java SE " + feature, "https://docs.oracle.com/en/java/javase/" + feature + "/docs/api/");
    }

    /** A dependency's javadoc as hosted by javadoc.io. */
    public static Candidate javadocIo(String groupId, String artifactId, String version) {
        return new Candidate(artifactId + " " + version, "https://javadoc.io/doc/" + groupId + "/" + artifactId + "/" + version + "/");
    }

    /**
     * Fetches the candidates' element lists and keeps, per site, only the packages that one of
     * {@code referencedTypes} lives in. Earlier candidates win when sites document the same package.
     */
    public List<ExternalDocumentation> link(List<Candidate> candidates, Set<String> referencedTypes) {
        Set<String> wanted = packagesOf(referencedTypes);
        if (wanted.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }
        List<CompletableFuture<Optional<String>>> lists = candidates.stream().map(this::elementList).toList();

        Set<String> claimed = new HashSet<>();
        List<ExternalDocumentation> result = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Optional<String> list = lists.get(i).join();
            if (list.isEmpty()) {
                continue;
            }
            Map<String, String> packages = new TreeMap<>();
            parseElementList(list.get()).forEach((pkg, module) -> {
                if (wanted.contains(pkg) && claimed.add(pkg)) {
                    packages.put(pkg, module);
                }
            });
            if (!packages.isEmpty()) {
                result.add(ExternalDocumentation.builder()
                        .label(candidates.get(i).label())
                        .baseUrl(candidates.get(i).baseUrl())
                        .packages(packages)
                        .build());
            }
        }
        return result;
    }

    /** Qualified type names that the serialized site mentions. */
    public static Set<String> referencedTypes(String siteJson) {
        Set<String> names = new HashSet<>();
        Matcher matcher = QUALIFIED_NAME.matcher(siteJson);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Every prefix of a.b.C.D that ends right before an upper-case segment: a.b (and a.b.C, harmlessly). */
    static Set<String> packagesOf(Set<String> qualifiedNames) {
        Set<String> packages = new HashSet<>();
        for (String name : qualifiedNames) {
            for (int dot = name.indexOf('.'); dot > 0 && dot < name.length() - 1; dot = name.indexOf('.', dot + 1)) {
                if (Character.isUpperCase(name.charAt(dot + 1))) {
                    packages.add(name.substring(0, dot));
                }
            }
        }
        return packages;
    }

    /** package -> module ("" when the list has no module lines). */
    static Map<String, String> parseElementList(String content) {
        Map<String, String> packages = new LinkedHashMap<>();
        String module = "";
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("module:")) {
                module = line.substring("module:".length());
            } else {
                packages.putIfAbsent(line, module);
            }
        }
        return packages;
    }

    private CompletableFuture<Optional<String>> elementList(Candidate candidate) {
        return fetchCached(candidate.baseUrl() + "element-list")
                .thenCompose(list -> list.isPresent()
                        ? CompletableFuture.completedFuture(list)
                        : fetchCached(candidate.baseUrl() + "package-list"))
                .whenComplete((list, error) -> {
                    if (list == null || list.isEmpty()) {
                        debug.accept("No javadoc found for " + candidate.label() + " at " + candidate.baseUrl());
                    }
                });
    }

    private CompletableFuture<Optional<String>> fetchCached(String url) {
        Path cached = cacheDirectory.resolve(hash(url) + ".txt");
        Path missing = cacheDirectory.resolve(hash(url) + ".missing");
        try {
            if (Files.isRegularFile(cached)) {
                return CompletableFuture.completedFuture(Optional.of(Files.readString(cached)));
            }
            if (offline || (Files.isRegularFile(missing)
                    && Files.getLastModifiedTime(missing).toInstant().isAfter(Instant.now().minus(RETRY_MISSING_AFTER)))) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
        } catch (IOException e) {
            debug.accept("Unreadable cache entry for " + url + ": " + e.getMessage());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("User-Agent", "pretty-docs-maven-plugin").GET().build();
        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .handle((response, error) -> {
                    if (error != null) {
                        debug.accept("Could not fetch " + url + ": " + error.getMessage());
                        return Optional.<String>empty(); // network trouble: do not remember as missing
                    }
                    Optional<String> body = response.statusCode() == 200 && looksLikeElementList(response.body())
                            ? Optional.of(response.body()) : Optional.empty();
                    try {
                        Files.createDirectories(cacheDirectory);
                        if (body.isPresent()) {
                            Files.writeString(cached, body.get());
                        } else {
                            Files.writeString(missing, response.statusCode() + "\n");
                        }
                    } catch (IOException e) {
                        debug.accept("Could not cache " + url + ": " + e.getMessage());
                    }
                    return body;
                });
    }

    /** javadoc.io answers unknown artifacts with an HTML page, not a 404. */
    static boolean looksLikeElementList(String body) {
        return !body.isBlank() && body.lines().map(String::strip).filter(l -> !l.isEmpty()).limit(20)
                .allMatch(l -> ELEMENT_LINE.matcher(l).matches());
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
