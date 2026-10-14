package io.joshuasalcedo.prettyjavadocs.parser;

import io.joshuasalcedo.prettyjavadocs.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Java source files.
 * This class extracts structural information from Java source code.
 */
public class JavaSourceParser {
    
    // Regular expression patterns
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("package\\s+([\\w.]+)\\s*;");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(public|private|protected)?\\s*(static)?\\s*(class|interface|enum)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("(public|private|protected)?\\s*(static)?\\s*(\\w+(?:<.*>)?)\\s+(\\w+)\\s*\\(([^)]*)\\)");
    private static final Pattern METHOD_BODY_PATTERN = Pattern.compile("(public|private|protected)?\\s*(static)?\\s*(\\w+(?:<.*>)?)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w,\\s.]+)?\\s*\\{");
    private static final Pattern JAVADOC_PATTERN = Pattern.compile("/\\*\\*(.*?)\\*/", Pattern.DOTALL);
    private static final Pattern SINGLE_LINE_COMMENT_PATTERN = Pattern.compile("//(.*)$", Pattern.MULTILINE);
    private static final Pattern MULTI_LINE_COMMENT_PATTERN = Pattern.compile("/\\*(.*?)\\*/", Pattern.DOTALL);
    private static final Pattern JAVADOC_TAG_PATTERN = Pattern.compile("@(\\w+)\\s+(.*?)(?=\\s*@|\\s*\\*/|$)", Pattern.DOTALL);
    
    /**
     * Parses a Java source file and returns a ClassInfo object.
     *
     * @param path The path to the Java source file
     * @return The parsed ClassInfo
     * @throws IOException If an I/O error occurs
     */
    public ClassInfo parseJavaFile(Path path) throws IOException {
        String content = Files.readString(path);
        String fileName = path.getFileName().toString();
        return extractClassStructure(fileName, content);
    }
    
    /**
     * Extracts the class structure from Java file content.
     *
     * @param fileName The Java file name
     * @param content The Java file content
     * @return ClassInfo object
     */
    private ClassInfo extractClassStructure(String fileName, String content) {
        String packageName = extractPackageName(content);
        
        // Extract class name and type
        String className = fileName.replace(".java", "");
        String classType = "class";
        
        Matcher classMatcher = CLASS_PATTERN.matcher(content);
        if (classMatcher.find()) {
            className = classMatcher.group(4);
            classType = classMatcher.group(3); // class, interface, or enum
        }
        
        // Extract methods
        List<MethodInfo> methods = extractMethods(content);
        
        // Extract JavaDoc and comments
        JavadocInfo javadoc = extractClassJavadoc(content);
        List<String> comments = extractClassComments(content);
        
        return new ClassInfoImpl(className, packageName, classType, methods, javadoc, comments);
    }
    
    /**
     * Extract the package name from Java file content.
     *
     * @param content The Java file content
     * @return The package name or "default" if not found
     */
    private String extractPackageName(String content) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        return matcher.find() ? matcher.group(1) : "default";
    }
    
    /**
     * Extract the class JavaDoc from Java file content.
     *
     * @param content The Java file content
     * @return JavadocInfo object or null if not found
     */
    private JavadocInfo extractClassJavadoc(String content) {
        // Find class declaration position
        Matcher classMatcher = CLASS_PATTERN.matcher(content);
        if (!classMatcher.find()) {
            return null;
        }
        
        // Get position of class declaration
        int classPosition = classMatcher.start();
        
        // Search for JavaDoc before class declaration
        String contentBeforeClass = content.substring(0, classPosition);
        Matcher javadocMatcher = JAVADOC_PATTERN.matcher(contentBeforeClass);
        
        // Find the last JavaDoc before class declaration
        JavadocInfo javadoc = null;
        while (javadocMatcher.find()) {
            String javadocText = javadocMatcher.group(1).trim();
            javadoc = parseJavadoc(javadocText);
        }
        
        return javadoc;
    }
    
    /**
     * Extract all non-JavaDoc comments around the class declaration.
     *
     * @param content The Java file content
     * @return List of comments
     */
    private List<String> extractClassComments(String content) {
        List<String> comments = new ArrayList<>();
        
        // Find class declaration position
        Matcher classMatcher = CLASS_PATTERN.matcher(content);
        if (!classMatcher.find()) {
            return comments;
        }
        
        // Get position of class declaration
        int classPosition = classMatcher.start();
        
        // Define a window around the class declaration (200 chars before and after)
        int startPos = Math.max(0, classPosition - 200);
        int endPos = Math.min(content.length(), classPosition + 200);
        String windowContent = content.substring(startPos, endPos);
        
        // Extract single-line comments
        Matcher singleLineCommentMatcher = SINGLE_LINE_COMMENT_PATTERN.matcher(windowContent);
        while (singleLineCommentMatcher.find()) {
            comments.add(singleLineCommentMatcher.group(1).trim());
        }
        
        // Extract multi-line comments (excluding JavaDoc)
        Matcher multiLineCommentMatcher = MULTI_LINE_COMMENT_PATTERN.matcher(windowContent);
        while (multiLineCommentMatcher.find()) {
            String commentText = multiLineCommentMatcher.group(1).trim();
            // If it doesn't start with *, it's not a JavaDoc
            if (!commentText.startsWith("*")) {
                comments.add(commentText);
            }
        }
        
        return comments;
    }
    
    /**
     * Extract all methods from a Java file content.
     *
     * @param content The Java file content
     * @return List of MethodInfo objects
     */
    private List<MethodInfo> extractMethods(String content) {
        List<MethodInfo> methods = new ArrayList<>();
        Matcher methodMatcher = METHOD_PATTERN.matcher(content);
        
        while (methodMatcher.find()) {
            String accessModifier = methodMatcher.group(1);
            String staticModifier = methodMatcher.group(2);
            String returnType = methodMatcher.group(3);
            String methodName = methodMatcher.group(4);
            String parameters = methodMatcher.group(5);
            
            // Extract method comments and JavaDoc
            int methodStart = methodMatcher.start();
            JavadocInfo javadoc = extractMethodJavadoc(content, methodStart);
            List<String> comments = extractMethodComments(content, methodStart);
            
            // Find the method body
            String body = "";
            String methodSignature = methodMatcher.group(0);
            methodStart = content.indexOf(methodSignature);
            if (methodStart >= 0) {
                // Position after the method signature
                int bodyStart = methodStart + methodSignature.length();
                
                // Search for the opening brace if not part of the signature
                if (!methodSignature.trim().endsWith("{")) {
                    bodyStart = content.indexOf("{", bodyStart);
                    if (bodyStart >= 0) {
                        // Find the matching closing brace
                        int bodyEnd = findMatchingBrace(content, bodyStart);
                        if (bodyEnd > bodyStart) {
                            // Extract the method body including braces
                            body = content.substring(bodyStart, bodyEnd + 1);
                        }
                    }
                }
            }
            
            MethodInfo method = new MethodInfoImpl(
                methodName,
                returnType,
                accessModifier != null ? accessModifier : "",
                staticModifier != null,
                parseParameters(parameters),
                body,
                javadoc,
                comments
            );
            
            methods.add(method);
        }
        
        return methods;
    }
    
    /**
     * Extract JavaDoc for a method.
     *
     * @param content The Java file content
     * @param methodPosition Position of method in content
     * @return JavadocInfo object or null if not found
     */
    private JavadocInfo extractMethodJavadoc(String content, int methodPosition) {
        // Search for JavaDoc before method
        int searchStart = Math.max(0, methodPosition - 500); // Look up to 500 chars before method
        String contentBefore = content.substring(searchStart, methodPosition);
        
        Matcher javadocMatcher = JAVADOC_PATTERN.matcher(contentBefore);
        
        // Find the last JavaDoc before method
        JavadocInfo javadoc = null;
        while (javadocMatcher.find()) {
            String javadocText = javadocMatcher.group(1).trim();
            javadoc = parseJavadoc(javadocText);
        }
        
        return javadoc;
    }
    
    /**
     * Extract non-JavaDoc comments for a method.
     *
     * @param content The Java file content
     * @param methodPosition Position of method in content
     * @return List of comments
     */
    private List<String> extractMethodComments(String content, int methodPosition) {
        List<String> comments = new ArrayList<>();
        
        // Define a window around the method declaration
        int startPos = Math.max(0, methodPosition - 200);
        int endPos = Math.min(content.length(), methodPosition + 100);
        String windowContent = content.substring(startPos, endPos);
        
        // Extract single-line comments
        Matcher singleLineCommentMatcher = SINGLE_LINE_COMMENT_PATTERN.matcher(windowContent);
        while (singleLineCommentMatcher.find()) {
            comments.add(singleLineCommentMatcher.group(1).trim());
        }
        
        // Extract multi-line comments (excluding JavaDoc)
        Matcher multiLineCommentMatcher = MULTI_LINE_COMMENT_PATTERN.matcher(windowContent);
        while (multiLineCommentMatcher.find()) {
            String commentText = multiLineCommentMatcher.group(1).trim();
            // If it doesn't start with *, it's not a JavaDoc
            if (!commentText.startsWith("*")) {
                comments.add(commentText);
            }
        }
        
        return comments;
    }
    
    /**
     * Parse a JavaDoc comment text into a structured representation.
     *
     * @param javadocText The JavaDoc text to parse
     * @return JavadocInfo object
     */
    private JavadocInfo parseJavadoc(String javadocText) {
        // Clean up the JavaDoc text by removing leading asterisks and extra whitespace
        String cleanJavadoc = javadocText.replaceAll("^\\s*\\*\\s*", "")
                .replaceAll("\\n\\s*\\*\\s*", "\n")
                .trim();
        
        // Split into main description and tags
        int firstTagIndex = cleanJavadoc.indexOf("@");
        String description = "";
        
        if (firstTagIndex == -1) {
            // No tags, whole text is description
            description = cleanJavadoc;
        } else {
            // Extract description (text before first @tag)
            description = cleanJavadoc.substring(0, firstTagIndex).trim();
        }
        
        // Extract tags
        List<JavadocTag> tags = new ArrayList<>();
        if (firstTagIndex != -1) {
            Matcher tagMatcher = JAVADOC_TAG_PATTERN.matcher(cleanJavadoc);
            
            while (tagMatcher.find()) {
                String tagName = tagMatcher.group(1);
                String tagValue = tagMatcher.group(2).trim();
                
                JavadocTag tag = new JavadocTagImpl(tagName, tagValue);
                tags.add(tag);
            }
        }
        
        return new JavadocInfoImpl(description, tags);
    }
    
    /**
     * Find the position of the matching closing brace for an opening brace.
     *
     * @param content The text to search in
     * @param openingBracePos Position of the opening brace
     * @return Position of the matching closing brace or -1 if not found
     */
    private int findMatchingBrace(String content, int openingBracePos) {
        int count = 1;
        for (int i = openingBracePos + 1; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                count++;
            } else if (c == '}') {
                count--;
                if (count == 0) {
                    return i;
                }
            }
        }
        return -1; // No matching brace found
    }
    
    /**
     * Parse a single parameter string into a ParameterInfo object.
     *
     * @param parameterString The single parameter string (e.g., "String name")
     * @return ParameterInfo object or null if parsing fails
     */
    private ParameterInfo parseParameter(String parameterString) {
        if (parameterString == null || parameterString.trim().isEmpty()) {
            return null;
        }
        
        String cleanParam = parameterString.trim();
        String[] parts = cleanParam.split("\\s+");
        
        if (parts.length >= 2) {
            String name = parts[parts.length - 1].replace(")", "");
            String type = parts[0];
            
            // If there are more than 2 parts, the type might include generics or be a fully qualified name
            if (parts.length > 2) {
                StringBuilder typeBuilder = new StringBuilder(parts[0]);
                for (int i = 1; i < parts.length - 1; i++) {
                    typeBuilder.append(" ").append(parts[i]);
                }
                type = typeBuilder.toString();
            }
            
            return new ParameterInfoImpl(name, type);
        }
        
        return null;
    }
    
    /**
     * Parse method parameters into a list of ParameterInfo objects.
     *
     * @param parameterString The parameter string from the method signature
     * @return List of ParameterInfo objects
     */
    private List<ParameterInfo> parseParameters(String parameterString) {
        List<ParameterInfo> parameters = new ArrayList<>();
        
        if (parameterString == null || parameterString.trim().isEmpty()) {
            return parameters;
        }
        
        String[] paramArray = parameterString.split(",");
        for (String param : paramArray) {
            ParameterInfo parameter = parseParameter(param);
            if (parameter != null) {
                parameters.add(parameter);
            }
        }
        
        return parameters;
    }
    
    // Implementation classes
    
    /**
     * Implementation of ClassInfo interface.
     */
    private static class ClassInfoImpl implements ClassInfo {
        private final String name;
        private final String packageName;
        private final String classType;
        private final List<MethodInfo> methods;
        private final JavadocInfo javadoc;
        private final List<String> comments;
        private final Map<String, Object> metadata = new HashMap<>();
        
        public ClassInfoImpl(String name, String packageName, String classType, 
                          List<MethodInfo> methods, JavadocInfo javadoc, List<String> comments) {
            this.name = name;
            this.packageName = packageName;
            this.classType = classType;
            this.methods = methods != null ? methods : List.of();
            this.javadoc = javadoc;
            this.comments = comments != null ? comments : List.of();
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public String getPackageName() {
            return packageName;
        }
        
        @Override
        public String getClassType() {
            return classType;
        }
        
        @Override
        public List<MethodInfo> getMethods() {
            return methods;
        }
        
        @Override
        public Optional<JavadocInfo> getJavadoc() {
            return Optional.ofNullable(javadoc);
        }
        
        @Override
        public List<String> getComments() {
            return comments;
        }
        
        @Override
        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
    
    /**
     * Implementation of MethodInfo interface.
     */
    private static class MethodInfoImpl implements MethodInfo {
        private final String name;
        private final String returnType;
        private final String accessModifier;
        private final boolean isStatic;
        private final List<ParameterInfo> parameters;
        private final String body;
        private final JavadocInfo javadoc;
        private final List<String> comments;
        private final Map<String, Object> metadata = new HashMap<>();
        
        public MethodInfoImpl(String name, String returnType, String accessModifier, 
                           boolean isStatic, List<ParameterInfo> parameters, String body, 
                           JavadocInfo javadoc, List<String> comments) {
            this.name = name;
            this.returnType = returnType;
            this.accessModifier = accessModifier;
            this.isStatic = isStatic;
            this.parameters = parameters != null ? parameters : List.of();
            this.body = body;
            this.javadoc = javadoc;
            this.comments = comments != null ? comments : List.of();
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public String getReturnType() {
            return returnType;
        }
        
        @Override
        public String getAccessModifier() {
            return accessModifier;
        }
        
        @Override
        public boolean isStatic() {
            return isStatic;
        }
        
        @Override
        public List<ParameterInfo> getParameters() {
            return parameters;
        }
        
        @Override
        public Optional<JavadocInfo> getJavadoc() {
            return Optional.ofNullable(javadoc);
        }
        
        @Override
        public List<String> getComments() {
            return comments;
        }
        
        @Override
        public String getBody() {
            return body;
        }
        
        @Override
        public Map<String, Object> getMetadata() {
            return metadata;
        }
    }
    
    /**
     * Implementation of ParameterInfo interface.
     */
    private static class ParameterInfoImpl implements ParameterInfo {
        private final String name;
        private final String type;
        
        public ParameterInfoImpl(String name, String type) {
            this.name = name;
            this.type = type;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public String getType() {
            return type;
        }
        
        @Override
        public boolean hasDefaultValue() {
            return false;
        }
        
        @Override
        public String getDefaultValue() {
            return null;
        }
    }
    
    /**
     * Implementation of JavadocInfo interface.
     */
    private static class JavadocInfoImpl implements JavadocInfo {
        private final String description;
        private final List<JavadocTag> tags;
        
        public JavadocInfoImpl(String description, List<JavadocTag> tags) {
            this.description = description;
            this.tags = tags != null ? tags : List.of();
        }
        
        @Override
        public String getDescription() {
            return description;
        }
        
        @Override
        public List<JavadocTag> getTags() {
            return tags;
        }
    }
    
    /**
     * Implementation of JavadocTag interface.
     */
    private static class JavadocTagImpl implements JavadocTag {
        private final String name;
        private final String value;
        
        public JavadocTagImpl(String name, String value) {
            this.name = name;
            this.value = value;
        }
        
        @Override
        public String getName() {
            return name;
        }
        
        @Override
        public String getValue() {
            return value;
        }
    }
}