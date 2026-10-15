package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A wiring problem Spring would report at startup (ERROR), a likely mistake (WARNING), or advice (INFO).
 */
@Data
@Builder
public class BeanIssue {
    public enum Severity { ERROR, WARNING, INFO }

    public enum Kind {
        UNSATISFIED_DEPENDENCY, AMBIGUOUS_DEPENDENCY, CIRCULAR_DEPENDENCY, DUPLICATE_BEAN_NAME,
        OUTSIDE_COMPONENT_SCAN, NO_USABLE_CONSTRUCTOR, FIELD_INJECTION, BEAN_METHOD_LITE_MODE,
        EXTERNAL_DEPENDENCY, UNREGISTERED_CONFIGURATION_PROPERTIES, UNKNOWN_PROPERTY, MISSING_PROPERTY
    }

    private Severity severity;
    private Kind kind;
    private String message;
    private String beanName; // the bean concerned, when there is one
    private String typeName; // the type to look at
    private List<String> applications; // the application contexts it occurs in; empty when it does not depend on one
    private String file; // configuration file, for property issues
    private Integer line; // line in that file
}
