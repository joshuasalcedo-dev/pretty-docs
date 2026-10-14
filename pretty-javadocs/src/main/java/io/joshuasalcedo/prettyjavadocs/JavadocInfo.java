package io.joshuasalcedo.prettyjavadocs;

import java.util.List;

/**
 * Represents Javadoc information extracted from a class or method.
 */
public interface JavadocInfo {
    
    /**
     * Returns the main description from the Javadoc.
     *
     * @return The description
     */
    String getDescription();
    
    /**
     * Returns all tags from the Javadoc.
     *
     * @return List of JavadocTag objects
     */
    List<JavadocTag> getTags();
    
    /**
     * Returns all tags with the specified name.
     *
     * @param name The tag name
     * @return List of JavadocTag objects with the specified name
     */
    default List<JavadocTag> getTagsByName(String name) {
        return getTags().stream()
                .filter(tag -> tag.getName().equals(name))
                .toList();
    }
    
    /**
     * Returns the first tag with the specified name.
     *
     * @param name The tag name
     * @return The first JavadocTag with the specified name, or null if not found
     */
    default JavadocTag getFirstTagByName(String name) {
        return getTags().stream()
                .filter(tag -> tag.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Returns the author tag value.
     *
     * @return The author, or null if not found
     */
    default String getAuthor() {
        JavadocTag tag = getFirstTagByName("author");
        return tag != null ? tag.getValue() : null;
    }
    
    /**
     * Returns the version tag value.
     *
     * @return The version, or null if not found
     */
    default String getVersion() {
        JavadocTag tag = getFirstTagByName("version");
        return tag != null ? tag.getValue() : null;
    }
    
    /**
     * Returns the return tag value.
     *
     * @return The return description, or null if not found
     */
    default String getReturnDescription() {
        JavadocTag tag = getFirstTagByName("return");
        return tag != null ? tag.getValue() : null;
    }
}