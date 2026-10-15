package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

/**
 * A property a library documents in META-INF/spring-configuration-metadata.json.
 */
@Data
@Builder
public class ConfigurationKeyMetadata {
    private String name;
    private String type;
    private String description;
    private String defaultValue;
    private boolean deprecated;
    private String sourceType;
}
