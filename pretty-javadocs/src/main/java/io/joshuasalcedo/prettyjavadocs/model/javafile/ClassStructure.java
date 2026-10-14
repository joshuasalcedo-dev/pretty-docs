package io.joshuasalcedo.prettyjavadocs.model.javafile;

import java.util.List;

public class ClassStructure {

        private String fileName;
        private String packageName;
        private String className;
        private String classType; // class, interface, or enum
        private List<MethodStructure> methods;
        private JavadocStructure javadoc;
        private List<String> comments;

        // Private constructor to be used by the Builder
        private ClassStructure(Builder builder) {
                this.fileName = builder.fileName;
                this.packageName = builder.packageName;
                this.className = builder.className;
                this.classType = builder.classType;
                this.methods = builder.methods;
                this.javadoc = builder.javadoc;
                this.comments = builder.comments;
        }

        // Static method to create a new Builder instance
        public static Builder builder() {
                return new Builder();
        }

        public String getFileName() {
                return fileName;
        }

        public void setFileName(String fileName) {
                this.fileName = fileName;
        }

        public String getPackageName() {
                return packageName;
        }

        public void setPackageName(String packageName) {
                this.packageName = packageName;
        }

        public String getClassName() {
                return className;
        }

        public void setClassName(String className) {
                this.className = className;
        }

        public String getClassType() {
                return classType;
        }

        public void setClassType(String classType) {
                this.classType = classType;
        }

        public List<MethodStructure> getMethods() {
                return methods;
        }

        public void setMethods(List<MethodStructure> methods) {
                this.methods = methods;
        }

        public JavadocStructure getJavadoc() {
                return javadoc;
        }

        public void setJavadoc(JavadocStructure javadoc) {
                this.javadoc = javadoc;
        }

        public List<String> getComments() {
                return comments;
        }

        public void setComments(List<String> comments) {
                this.comments = comments;
        }

        // Builder class
        public static class Builder {
                private String fileName;
                private String packageName;
                private String className;
                private String classType; // class, interface, or enum
                private List<MethodStructure> methods;
                private JavadocStructure javadoc;
                private List<String> comments;

                // Private constructor
                private Builder() {
                }

                // Builder methods for each property
                public Builder fileName(String fileName) {
                        this.fileName = fileName;
                        return this;
                }

                public Builder packageName(String packageName) {
                        this.packageName = packageName;
                        return this;
                }

                public Builder className(String className) {
                        this.className = className;
                        return this;
                }

                public Builder classType(String classType) {
                        this.classType = classType;
                        return this;
                }

                public Builder methods(List<MethodStructure> methods) {
                        this.methods = methods;
                        return this;
                }

                public Builder javadoc(JavadocStructure javadoc) {
                        this.javadoc = javadoc;
                        return this;
                }

                public Builder comments(List<String> comments) {
                        this.comments = comments;
                        return this;
                }

                // Build method to create the ClassStructure instance
                public ClassStructure build() {
                        return new ClassStructure(this);
                }
        }
}