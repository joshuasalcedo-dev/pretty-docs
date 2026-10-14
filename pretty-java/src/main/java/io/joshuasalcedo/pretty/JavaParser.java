package io.joshuasalcedo.pretty;


import io.joshuasalcedo.pretty.model.JavaFileStructure;
import io.joshuasalcedo.pretty.model.TypeStructure;
import io.joshuasalcedo.pretty.utils.PrettyUtils;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Utility class for parsing Java files and extracting structural information.
 * <p>
 * Parsing is syntax-only (Eclipse JDT, no classpath), so it works on any source tree but qualified
 * names are best-effort: types from the same package or from wildcard imports have a null
 * {@code qualifiedName}. Files with syntax errors are parsed with recovery rather than rejected.
 */
public final class JavaParser {
    // Prevent instantiation
    private JavaParser() {
        throw new AssertionError("Utility class should not be instantiated");
    }

    /**
     * Find all Java files in a directory and its subdirectories.
     *
     * @param directory The directory to search
     * @return List of Java files
     */
    public static List<File> findJavaFiles(File directory) {
        try {
            return PrettyUtils.files().listFilesByExtension(directory.getAbsolutePath(), "java");
        } catch (java.io.IOException e) {
            // Log the error and return an empty list
            System.err.println("Error finding Java files: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * Parse a Java source file.
     *
     * @param file The Java file
     * @return The file's structure
     * @throws IOException if the file cannot be read
     */
    public static JavaFileStructure parse(Path file) throws IOException {
        return parseFile(file).structure();
    }

    /**
     * Parse Java source code held in memory.
     *
     * @param source   The Java source code
     * @param fileName The name to report for the file, e.g. {@code Foo.java}
     * @return The file's structure, with a null {@code filePath}
     */
    public static JavaFileStructure parse(String source, String fileName) {
        ParsedFile parsed = parseSource(source, fileName, null);
        linkUsedBy(List.of(parsed));
        return parsed.structure();
    }

    /**
     * Parse every Java file under a directory and link {@code usedBy} between the types found.
     *
     * @param directory The root directory
     * @return One structure per file, ordered by path
     * @throws IOException if the directory cannot be listed or a file cannot be read
     */
    public static List<JavaFileStructure> parseDirectory(Path directory) throws IOException {
        return parseDirectories(List.of(directory)).get(directory);
    }

    /**
     * Parse every Java file under several directories (e.g. the source roots of all modules in a
     * project) and link {@code usedBy} across all of them.
     *
     * @param directories The root directories; missing directories yield an empty list
     * @return The structures per directory, in the given order, each ordered by path
     * @throws IOException if a directory cannot be listed or a file cannot be read
     */
    public static Map<Path, List<JavaFileStructure>> parseDirectories(List<Path> directories) throws IOException {
        Map<Path, List<ParsedFile>> parsedByDirectory = new LinkedHashMap<>();
        for (Path directory : directories) {
            List<ParsedFile> parsed = new ArrayList<>();
            if (Files.isDirectory(directory)) {
                List<File> files = new ArrayList<>(PrettyUtils.files().listFilesByExtension(directory.toAbsolutePath().toString(), "java"));
                files.sort(Comparator.comparing(File::getPath));
                for (File file : files) {
                    parsed.add(parseFile(file.toPath()));
                }
            }
            parsedByDirectory.put(directory, parsed);
        }
        linkUsedBy(parsedByDirectory.values().stream().flatMap(List::stream).toList());

        Map<Path, List<JavaFileStructure>> result = new LinkedHashMap<>();
        parsedByDirectory.forEach((directory, parsed) -> result.put(directory, parsed.stream().map(ParsedFile::structure).toList()));
        return result;
    }

    /**
     * Convert a parsed structure (or list of structures) to JSON.
     *
     * @param structure   The object to convert
     * @param prettyPrint Whether to format the JSON with indentation
     * @return JSON representation
     */
    public static String toJson(Object structure, boolean prettyPrint) {
        return prettyPrint ? PrettyUtils.json().toPrettyJson(structure) : PrettyUtils.json().toJson(structure);
    }

    private static ParsedFile parseFile(Path file) throws IOException {
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (MalformedInputException e) {
            source = Files.readString(file, StandardCharsets.ISO_8859_1);
        }
        ParsedFile parsed = parseSource(source, file.getFileName().toString(), file.toAbsolutePath().toString());
        return parsed;
    }

    private static ParsedFile parseSource(String source, String fileName, String filePath) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setStatementsRecovery(true);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        CompilationUnitConverter converter = new CompilationUnitConverter(source, unit);
        JavaFileStructure structure = converter.convert(fileName, filePath);
        return new ParsedFile(structure, converter.typeReferences());
    }

    /** Records, on each type, the qualified names of the other parsed types that reference it. */
    private static void linkUsedBy(List<ParsedFile> files) {
        Map<String, TypeStructure> typesByName = new HashMap<>();
        for (ParsedFile file : files) {
            file.structure().getTypes().forEach(type -> index(type, typesByName));
        }
        for (ParsedFile file : files) {
            file.typeReferences().forEach((user, referenced) -> {
                for (String name : referenced) {
                    TypeStructure target = typesByName.get(name);
                    if (target != null && !name.equals(user) && !target.getUsedBy().contains(user)) {
                        target.getUsedBy().add(user);
                    }
                }
            });
        }
    }

    private static void index(TypeStructure type, Map<String, TypeStructure> typesByName) {
        typesByName.put(type.getQualifiedName(), type);
        type.getNestedTypes().forEach(nested -> index(nested, typesByName));
    }

    private record ParsedFile(JavaFileStructure structure, Map<String, Set<String>> typeReferences) {
    }
}
