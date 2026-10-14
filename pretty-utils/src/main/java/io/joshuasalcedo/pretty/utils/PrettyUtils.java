package io.joshuasalcedo.pretty.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Central utility class that provides access to various utility functions.
 * This class serves as a facade for different utility categories.
 */
public final class PrettyUtils {

    // Private constructor to prevent instantiation
    private PrettyUtils() {}

    // Singleton instances of utility classes
    private static final JsonUtilsImpl jsonUtils = new JsonUtilsImpl();
    private static final FileUtilsImpl fileUtils = new FileUtilsImpl();
    private static final GitUtilsImpl gitUtils = new GitUtilsImpl();

    /**
     * Access to JSON utility methods
     *
     * @return The JSON utilities instance
     */
    public static JsonUtils json() {
        return jsonUtils;
    }

    /**
     * Access to file utility methods
     *
     * @return The file utilities instance
     */
    public static FileUtils files() {
        return fileUtils;
    }

    /**
     * Access to Git utility methods
     *
     * @return The Git utilities instance
     */
    public static GitUtils git() {
        return gitUtils;
    }

    /**
     * Interface for JSON utility methods
     */
    public interface JsonUtils {
        /**
         * Convert any object to a JSON string
         */
        String toJson(Object obj);

        /**
         * Convert any object to a JSON string with pretty printing
         */
        String toPrettyJson(Object obj);

        /**
         * Convert any object to a JSON Map
         */
        Map<String, Object> toJsonMap(Object obj);
    }

    /**
     * Interface for file utility methods
     */
    public interface FileUtils {
        /**
         * Get the file extension.
         *
         * @param file The file
         * @return The file extension (without the dot) or empty string if none
         */
        String getFileExtension(File file);

        /**
         * Get the filename without extension.
         *
         * @param file The file
         * @return The filename without extension
         */
        String getFileBaseName(File file);

        /**
         * Get file content as string.
         *
         * @param file The file to read
         * @return The file content as string
         * @throws IOException If an I/O error occurs
         */
        String readFileAsString(File file) throws IOException;

        /**
         * Read file as bytes.
         *
         * @param file The file to read
         * @return The file content as byte array
         * @throws IOException If an I/O error occurs
         */
        byte[] readFileAsBytes(File file) throws IOException;

        /**
         * Write string content to a file.
         *
         * @param file The file to write
         * @param content The content to write
         * @throws IOException If an I/O error occurs
         */
        void writeStringToFile(File file, String content) throws IOException;

        /**
         * Create directories for a file if they don't exist.
         *
         * @param file The file whose directory structure should be created
         * @return true if directories were created or already existed
         */
        boolean createDirectoriesForFile(File file);

        /**
         * Check if a file exists and is readable.
         *
         * @param file The file to check
         * @return true if the file exists and is readable
         */
        boolean isFileReadable(File file);

        /**
         * Check if a file exists and is writable.
         *
         * @param file The file to check
         * @return true if the file exists and is writable
         */
        boolean isFileWritable(File file);

        /**
         * List all files in a directory and its subdirectories.
         *
         * @param directory Directory to search
         * @param respectGitIgnore Whether to respect .gitignore rules
         * @return List of files found
         * @throws IOException If an I/O error occurs
         */
        List<File> listAllFiles(String directory, boolean respectGitIgnore) throws IOException;

        /**
         * List files with specific extensions in a directory and its subdirectories.
         *
         * @param directory Directory to search
         * @param extensions File extensions to include (with or without leading dot)
         * @return List of files with the specified extensions
         * @throws IOException If an I/O error occurs
         */
        List<File> listFilesByExtension(String directory, String... extensions) throws IOException;

        /**
         * List all text files in a directory and its subdirectories.
         *
         * @param directory Directory to search
         * @param respectGitIgnore Whether to respect .gitignore rules
         * @return List of text files
         * @throws IOException If an I/O error occurs
         */
        List<File> listTextFiles(String directory, boolean respectGitIgnore) throws IOException;

        /**
         * List files matching a custom predicate.
         *
         * @param directory Directory to search
         * @param respectGitIgnore Whether to respect .gitignore rules
         * @param predicate Custom filter to apply to files
         * @return List of files matching the predicate
         * @throws IOException If an I/O error occurs
         */
        List<File> listFilesMatching(String directory, boolean respectGitIgnore,
                                     Predicate<File> predicate) throws IOException;

        /**
         * Get the relative path of a file from a base directory.
         *
         * @param file The file
         * @param baseDir The base directory
         * @return The relative path as a string
         */
        String getRelativePath(File file, File baseDir);

        /**
         * Determine if a file is a text file using character decoding capabilities.
         * No hard-coded extensions or MIME types are used.
         *
         * @param file The file to check
         * @return true if the file appears to be readable as text
         */
        boolean isTextFile(File file);
    }

    /**
     * Interface for Git utility methods
     */
    public interface GitUtils {
        /**
         * Check if a path is a valid Git repository.
         *
         * @param path The path to check
         * @return true if the path is a valid Git repository, false otherwise
         */
        boolean isValidRepository(String path);

        /**
         * List all files in a directory, respecting .gitignore rules if present.
         *
         * @param directory The directory to list files from
         * @return List of files, excluding those matched by .gitignore rules
         * @throws IOException If any I/O errors occur
         */
        List<File> listFilesRespectingGitignore(String directory) throws IOException;
    }

    /**
     * Implementation of JsonUtils interface
     */
    private static class JsonUtilsImpl implements JsonUtils {
        private static final Gson gson = new GsonBuilder().create();
        private static final Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();

        @Override
        public String toJson(Object obj) {
            return gson.toJson(obj);
        }

        @Override
        public String toPrettyJson(Object obj) {
            return prettyGson.toJson(obj);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> toJsonMap(Object obj) {
            String json = gson.toJson(obj);
            return gson.fromJson(json, Map.class);
        }
    }

    /**
     * Implementation of FileUtils interface
     */
    private static class FileUtilsImpl implements FileUtils {

        @Override
        public String getFileExtension(File file) {
            String name = file.getName();
            int lastDotIndex = name.lastIndexOf('.');
            return lastDotIndex > 0 ? name.substring(lastDotIndex + 1) : "";
        }

        @Override
        public String getFileBaseName(File file) {
            String name = file.getName();
            int lastDotIndex = name.lastIndexOf('.');
            return lastDotIndex > 0 ? name.substring(0, lastDotIndex) : name;
        }

        @Override
        public String readFileAsString(File file) throws IOException {
            return Files.readString(file.toPath());
        }

        @Override
        public byte[] readFileAsBytes(File file) throws IOException {
            return Files.readAllBytes(file.toPath());
        }

        @Override
        public void writeStringToFile(File file, String content) throws IOException {
            Files.writeString(file.toPath(), content);
        }

        @Override
        public boolean createDirectoriesForFile(File file) {
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) {
                return dir.mkdirs();
            }
            return true;
        }

        @Override
        public boolean isFileReadable(File file) {
            return file.exists() && file.isFile() && file.canRead();
        }

        @Override
        public boolean isFileWritable(File file) {
            return file.exists() && file.isFile() && file.canWrite();
        }

        @Override
        public List<File> listAllFiles(String directory, boolean respectGitIgnore) throws IOException {
            List<File> files;
            if (respectGitIgnore) {
                files = PrettyUtils.git().listFilesRespectingGitignore(directory);
            } else {
                files = listAllFilesRecursively(directory);
            }
            return files;
        }

        @Override
        public List<File> listFilesByExtension(String directory, String... extensions) throws IOException {
            Path dirPath = Paths.get(directory);
            List<String> normalizedExtensions = new ArrayList<>();

            // Normalize extensions to start with a dot
            for (String ext : extensions) {
                normalizedExtensions.add(ext.startsWith(".") ? ext.toLowerCase() : "." + ext.toLowerCase());
            }

            try (Stream<Path> pathStream = Files.walk(dirPath)) {
                return pathStream
                        .filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .filter(file -> {
                            String name = file.getName().toLowerCase();
                            for (String ext : normalizedExtensions) {
                                if (name.endsWith(ext)) {
                                    return true;
                                }
                            }
                            return false;
                        })
                        .collect(Collectors.toList());
            }
        }

        @Override
        public List<File> listTextFiles(String directory, boolean respectGitIgnore) throws IOException {
            List<File> allFiles = listAllFiles(directory, respectGitIgnore);
            return allFiles.stream()
                    .filter(this::isTextFile)
                    .collect(Collectors.toList());
        }

        @Override
        public List<File> listFilesMatching(String directory, boolean respectGitIgnore,
                                            Predicate<File> predicate) throws IOException {
            List<File> allFiles = listAllFiles(directory, respectGitIgnore);
            return allFiles.stream()
                    .filter(predicate)
                    .collect(Collectors.toList());
        }

        @Override
        public String getRelativePath(File file, File baseDir) {
            return baseDir.toURI().relativize(file.toURI()).getPath();
        }

        @Override
        public boolean isTextFile(File file) {
            // Don't bother with empty files
            if (file.length() == 0) return true;

            // Don't bother with very large files - likely not text
            if (file.length() > 50 * 1024 * 1024) return false; // 50MB limit

            Path path = file.toPath();

            try {
                // Sample the file if it's large
                byte[] data;
                if (file.length() > 8192) {
                    // For large files, sample the beginning, middle, and end
                    data = sampleFileContent(path);
                } else {
                    // For small files, read it all
                    data = Files.readAllBytes(path);
                }

                // Quick test: check for null bytes (common in binary files)
                for (byte b : data) {
                    if (b == 0) return false;
                }

                // Try to decode the file as text with common encodings
                return canDecodeAsText(data);

            } catch (IOException e) {
                return false;
            }
        }

        /**
         * List all files in a directory and its subdirectories without respecting .gitignore.
         *
         * @param directory Directory to search
         * @return List of files found
         * @throws IOException If an I/O error occurs
         */
        private List<File> listAllFilesRecursively(String directory) throws IOException {
            Path dirPath = Paths.get(directory);
            try (Stream<Path> pathStream = Files.walk(dirPath)) {
                return pathStream
                        .filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .collect(Collectors.toList());
            }
        }

        /**
         * Sample content from beginning, middle and end of a file.
         */
        private byte[] sampleFileContent(Path path) throws IOException {
            long fileSize = Files.size(path);
            byte[] sample = new byte[8192]; // 8KB sample

            try (var channel = Files.newByteChannel(path)) {
                ByteBuffer buffer = ByteBuffer.wrap(sample);

                // Read beginning (first 3KB)
                buffer.limit(3072);
                channel.read(buffer);

                // Read from middle (2KB)
                buffer.limit(5120);
                buffer.position(3072);
                channel.position(fileSize / 2 - 1024);
                channel.read(buffer);

                // Read from end (3KB)
                buffer.limit(8192);
                buffer.position(5120);
                channel.position(Math.max(0, fileSize - 3072));
                channel.read(buffer);
            }

            return sample;
        }

        /**
         * Try to decode the data as text with various encodings.
         * Returns true if the content can be successfully decoded.
         */
        private boolean canDecodeAsText(byte[] data) {
            // Try with common encodings
            String[] encodings = {"UTF-8", "UTF-16", "ISO-8859-1", "US-ASCII"};

            for (String encoding : encodings) {
                Charset charset = Charset.forName(encoding);
                CharsetDecoder decoder = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);

                ByteBuffer buffer = ByteBuffer.wrap(data);
                try {
                    CharBuffer charBuffer = decoder.decode(buffer);

                    // Additional check - if over 5% of the content is control characters,
                    // it's probably binary despite being decodable
                    return !hasTooManyControlCharacters(charBuffer);
                } catch (CharacterCodingException e) {
                    // Decoding failed with this charset, try another one
                    continue;
                }
            }

            // If we couldn't decode with any charset, it's likely binary
            return false;
        }

        /**
         * Check if the text contains too many control characters.
         */
        private boolean hasTooManyControlCharacters(CharBuffer buffer) {
            int controlCount = 0;
            int totalChars = buffer.length();

            // Reset buffer to beginning
            buffer.rewind();

            while (buffer.hasRemaining()) {
                char c = buffer.get();
                // Count control chars except common whitespace
                if (c < 32 && c != '\t' && c != '\n' && c != '\r') {
                    controlCount++;
                }
            }

            // If more than 5% are control characters, likely binary
            return ((double) controlCount / totalChars) > 0.05;
        }
    }

    /**
     * Implementation of GitUtils interface
     */
    private static class GitUtilsImpl implements GitUtils {

        @Override
        public boolean isValidRepository(String path) {
            File gitDir = new File(path);
            try {
                FileRepositoryBuilder builder = new FileRepositoryBuilder()
                        .setGitDir(new File(gitDir, ".git"))
                        .readEnvironment()
                        .findGitDir();

                if (builder.getGitDir() == null) {
                    return false;
                }

                try (Repository repository = builder.build()) {
                    return repository.getObjectDatabase().exists();
                }
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        public List<File> listFilesRespectingGitignore(String directory) throws IOException {
            Path dirPath = Paths.get(directory);
            File gitignoreFile = new File(directory, ".gitignore");
            File dirFile = new File(directory);

            // List to store ignore patterns
            List<String> ignorePatterns = new ArrayList<>();

            // Load .gitignore patterns if the file exists
            if (gitignoreFile.exists() && gitignoreFile.isFile()) {
                try {
                    // Use PrettyUtils files() to read the file
                    String gitignoreContent = PrettyUtils.files().readFileAsString(gitignoreFile);
                    String[] lines = gitignoreContent.split("\\r?\\n");

                    for (String line : lines) {
                        // Skip empty lines and comments
                        if (line.trim().isEmpty() || line.startsWith("#")) {
                            continue;
                        }
                        ignorePatterns.add(line.trim());
                    }
                } catch (IOException e) {
                    // If there's an error reading .gitignore, log it but continue without ignore rules
                    System.err.println("Error reading .gitignore file: " + e.getMessage());
                }
            }

            // Get all files recursively
            List<File> allFiles;
            try (Stream<Path> pathStream = Files.walk(dirPath)) {
                allFiles = pathStream
                        .filter(Files::isRegularFile)
                        .map(Path::toFile)
                        .collect(Collectors.toList());
            }

            List<File> result = new ArrayList<>();

            for (File file : allFiles) {
                // Get the relative path from the base directory
                String pathStr = PrettyUtils.files().getRelativePath(file, dirFile).replace('\\', '/');

                // Check if the file matches any ignore pattern
                boolean isIgnored = false;
                for (String pattern : ignorePatterns) {
                    if (matchesGitignorePattern(pathStr, pattern)) {
                        isIgnored = true;
                        break;
                    }
                }

                // Add the file to the result if it's not ignored
                if (!isIgnored) {
                    result.add(file);
                }
            }

            return result;
        }

        /**
         * Check if a file path matches a gitignore pattern.
         * This is a simplified implementation and doesn't handle all gitignore pattern features.
         *
         * @param path The file path to check
         * @param pattern The gitignore pattern
         * @return true if the path matches the pattern, false otherwise
         */
        private boolean matchesGitignorePattern(String path, String pattern) {
            // Handle negation (patterns starting with !)
            boolean negate = pattern.startsWith("!");
            if (negate) {
                pattern = pattern.substring(1);
            }

            // Handle directory-only patterns (ending with /)
            boolean dirOnly = pattern.endsWith("/");
            if (dirOnly) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }

            // Convert gitignore glob pattern to regex
            pattern = pattern
                    .replace(".", "\\.")   // Escape dots
                    .replace("*", ".*")    // * becomes .*
                    .replace("?", ".");    // ? becomes .

            // Match the pattern against the path
            boolean matches = path.matches(pattern);

            // Return the result, taking negation into account
            return negate ? !matches : matches;
        }
    }
}