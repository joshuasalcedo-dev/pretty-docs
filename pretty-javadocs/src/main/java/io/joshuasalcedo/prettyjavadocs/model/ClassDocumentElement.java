package io.joshuasalcedo.prettyjavadocs.model;

import io.joshuasalcedo.prettyjavadocs.ClassInfo;
import io.joshuasalcedo.prettyjavadocs.JavadocInfo;
import io.joshuasalcedo.prettyjavadocs.MethodInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Implementation of ClassInfo that also implements DocumentElement.
 * This provides a bridge between our specific interfaces and the general model.
 */
public class ClassDocumentElement implements ClassInfo, DocumentElement {
    
    private final String name;
    private final String packageName;
    private final String classType;
    private final List<MethodInfo> methods;
    private final JavadocInfo javadoc;
    private final List<String> comments;
    private final Map<String, Object> metadata = new HashMap<>();
    private DocumentElement parent;
    
    public ClassDocumentElement(String name, String packageName, String classType, 
                               List<MethodInfo> methods, JavadocInfo javadoc, List<String> comments) {
        this.name = name;
        this.packageName = packageName;
        this.classType = classType;
        this.methods = methods != null ? methods : List.of();
        this.javadoc = javadoc;
        this.comments = comments != null ? comments : List.of();
    }
    
    // ClassInfo implementation
    
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
    
    // DocumentElement implementation
    
    @Override
    public String getId() {
        return getFullyQualifiedName();
    }
    
    @Override
    public ElementType getType() {
        switch (classType.toLowerCase()) {
            case "interface":
                return ElementType.INTERFACE;
            case "enum":
                return ElementType.ENUM;
            case "annotation":
            case "@interface":
                return ElementType.ANNOTATION;
            case "class":
            default:
                return ElementType.CLASS;
        }
    }
    
    @Override
    public Optional<DocumentElement> getParent() {
        return Optional.ofNullable(parent);
    }
    
    @Override
    public void setParent(DocumentElement parent) {
        this.parent = parent;
    }
    
    @Override
    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }
    
    @Override
    public Optional<Object> getMetadata(String key) {
        return Optional.ofNullable(metadata.get(key));
    }
}