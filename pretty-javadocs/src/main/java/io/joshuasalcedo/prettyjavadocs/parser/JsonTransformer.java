package io.joshuasalcedo.prettyjavadocs.parser;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.joshuasalcedo.prettyjavadocs.*;

import java.util.*;

/**
 * Transformer that converts documentation elements to JSON.
 */
public class JsonTransformer {
    
    private final Gson gson;
    
    /**
     * Creates a new JSON transformer with default settings.
     */
    public JsonTransformer() {
        this(false);
    }
    
    /**
     * Creates a new JSON transformer with the specified pretty-printing setting.
     *
     * @param prettyPrint true to enable pretty-printing, false to disable
     */
    public JsonTransformer(boolean prettyPrint) {
        GsonBuilder builder = new GsonBuilder();
        if (prettyPrint) {
            builder.setPrettyPrinting();
        }
        this.gson = builder.create();
    }
    
    /**
     * Transforms a documentation result to a JSON string.
     *
     * @param result The documentation result to transform
     * @return The JSON string representation
     */
    public String transform(DocumentationResult result) {
        JsonObject rootObject = new JsonObject();
        
        // Add classes
        JsonObject classesObject = new JsonObject();
        for (ClassInfo classInfo : result.getClasses()) {
            classesObject.add(classInfo.getFullyQualifiedName(), transformClass(classInfo));
        }
        rootObject.add("classes", classesObject);
        
        // Add metadata
        JsonObject metadataObject = new JsonObject();
        metadataObject.addProperty("generatedAt", new Date().toString());
        metadataObject.addProperty("classCount", result.getClasses().size());
        rootObject.add("metadata", metadataObject);
        
        return gson.toJson(rootObject);
    }
    
    /**
     * Transforms a class info to a JSON element.
     *
     * @param classInfo The class info to transform
     * @return The JSON element representation
     */
    private JsonElement transformClass(ClassInfo classInfo) {
        Map<String, Object> classMap = new HashMap<>();
        
        // Basic class info
        classMap.put("name", classInfo.getName());
        classMap.put("packageName", classInfo.getPackageName());
        classMap.put("fullyQualifiedName", classInfo.getFullyQualifiedName());
        classMap.put("type", classInfo.getClassType());
        
        // Javadoc
        classInfo.getJavadoc().ifPresent(javadoc -> {
            Map<String, Object> javadocMap = transformJavadoc(javadoc);
            classMap.put("javadoc", javadocMap);
        });
        
        // Comments
        if (!classInfo.getComments().isEmpty()) {
            classMap.put("comments", classInfo.getComments());
        }
        
        // Methods
        if (!classInfo.getMethods().isEmpty()) {
            List<Map<String, Object>> methodsList = new ArrayList<>();
            for (MethodInfo methodInfo : classInfo.getMethods()) {
                methodsList.add(transformMethod(methodInfo));
            }
            classMap.put("methods", methodsList);
        }
        
        // Metadata
        if (!classInfo.getMetadata().isEmpty()) {
            classMap.put("metadata", classInfo.getMetadata());
        }
        
        return gson.toJsonTree(classMap);
    }
    
    /**
     * Transforms a method info to a map.
     *
     * @param methodInfo The method info to transform
     * @return The map representation
     */
    private Map<String, Object> transformMethod(MethodInfo methodInfo) {
        Map<String, Object> methodMap = new HashMap<>();
        
        // Basic method info
        methodMap.put("name", methodInfo.getName());
        methodMap.put("returnType", methodInfo.getReturnType());
        methodMap.put("accessModifier", methodInfo.getAccessModifier());
        methodMap.put("isStatic", methodInfo.isStatic());
        
        // Parameters
        if (!methodInfo.getParameters().isEmpty()) {
            List<Map<String, Object>> paramsList = new ArrayList<>();
            for (ParameterInfo paramInfo : methodInfo.getParameters()) {
                paramsList.add(transformParameter(paramInfo));
            }
            methodMap.put("parameters", paramsList);
        }
        
        // Javadoc
        methodInfo.getJavadoc().ifPresent(javadoc -> {
            Map<String, Object> javadocMap = transformJavadoc(javadoc);
            methodMap.put("javadoc", javadocMap);
        });
        
        // Comments
        if (!methodInfo.getComments().isEmpty()) {
            methodMap.put("comments", methodInfo.getComments());
        }
        
        // Body (optionally exclude for brevity)
        if (methodInfo.getBody() != null && !methodInfo.getBody().isEmpty()) {
            methodMap.put("body", methodInfo.getBody());
        }
        
        // Metadata
        if (!methodInfo.getMetadata().isEmpty()) {
            methodMap.put("metadata", methodInfo.getMetadata());
        }
        
        return methodMap;
    }
    
    /**
     * Transforms a parameter info to a map.
     *
     * @param paramInfo The parameter info to transform
     * @return The map representation
     */
    private Map<String, Object> transformParameter(ParameterInfo paramInfo) {
        Map<String, Object> paramMap = new HashMap<>();
        
        paramMap.put("name", paramInfo.getName());
        paramMap.put("type", paramInfo.getType());
        
        if (paramInfo.hasDefaultValue()) {
            paramMap.put("defaultValue", paramInfo.getDefaultValue());
        }
        
        return paramMap;
    }
    
    /**
     * Transforms a javadoc info to a map.
     *
     * @param javadocInfo The javadoc info to transform
     * @return The map representation
     */
    private Map<String, Object> transformJavadoc(JavadocInfo javadocInfo) {
        Map<String, Object> javadocMap = new HashMap<>();
        
        javadocMap.put("description", javadocInfo.getDescription());
        
        if (!javadocInfo.getTags().isEmpty()) {
            List<Map<String, String>> tagsList = new ArrayList<>();
            for (JavadocTag tag : javadocInfo.getTags()) {
                Map<String, String> tagMap = new HashMap<>();
                tagMap.put("name", tag.getName());
                tagMap.put("value", tag.getValue());
                tagsList.add(tagMap);
            }
            javadocMap.put("tags", tagsList);
        }
        
        return javadocMap;
    }
}