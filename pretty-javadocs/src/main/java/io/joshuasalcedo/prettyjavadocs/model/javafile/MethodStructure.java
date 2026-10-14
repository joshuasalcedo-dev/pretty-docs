package io.joshuasalcedo.prettyjavadocs.model.javafile;


import java.util.List;

public class MethodStructure {
    private String accessModifier;
    private boolean isStatic;
    private String returnType;
    private String methodName;
    private List<Parameter> parameters;
    private String body;
    private JavadocStructure javadoc;
    private List<String> comments;

}
