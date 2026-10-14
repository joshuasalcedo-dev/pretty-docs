package io.joshuasalcedo.prettyjavadocs;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Interface representing the result of documentation generation.
 * This provides access to the structured documentation data.
 */
public interface DocumentationResult {
    
    /**
     * Returns information about all classes found in the source.
     *
     * @return List of ClassInfo objects
     */
    List<ClassInfo> getClasses();
    
    /**
     * Returns the raw data of the documentation in a structured format.
     * This can be used for custom processing or serialization.
     *
     * @return Map containing the raw documentation data
     */
    Map<String, Object> getRawData();
    
    /**
     * Gets information about a specific class by its fully qualified name.
     *
     * @param fullyQualifiedName The fully qualified name of the class
     * @return Optional containing the ClassInfo if found
     */
    default Optional<ClassInfo> getClassInfo(String fullyQualifiedName) {
        return getClasses().stream()
                .filter(classInfo -> classInfo.getFullyQualifiedName().equals(fullyQualifiedName))
                .findFirst();
    }
    
    /**
     * Gets a list of classes in a specific package.
     *
     * @param packageName The package name
     * @return List of ClassInfo objects in the specified package
     */
    default List<ClassInfo> getClassesByPackage(String packageName) {
        return getClasses().stream()
                .filter(classInfo -> classInfo.getPackageName().equals(packageName))
                .toList();
    }
}