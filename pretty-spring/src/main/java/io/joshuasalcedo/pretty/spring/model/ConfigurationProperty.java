package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

/**
 * One bindable key, e.g. {@code app.mail.retry-count}.
 */
@Data
@Builder
public class ConfigurationProperty {
    private String key; // full key in kebab case
    private String typeName;
    private String defaultValue; // field initializer as written
    private String description; // field javadoc
}
