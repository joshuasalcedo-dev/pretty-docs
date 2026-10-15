package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

/**
 * A {@code @Value("${key:default}")} placeholder.
 */
@Data
@Builder
public class PropertyUsage {
    private String key;
    private String defaultValue; // null when there is none
    private String expression; // the annotation value as written
    private String typeName; // where it is used
    private String memberName;
    private String source; // @Value, @ConditionalOnProperty or Environment
    private boolean required; // startup fails when it is not set (@Value without default, getRequiredProperty)
}
