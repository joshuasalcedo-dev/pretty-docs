package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A key the project's configuration files set, with what reads it.
 */
@Data
@Builder
public class ConfigurationKey {
    public enum Status {
        PROJECT, // bound by the project's @ConfigurationProperties or read by @Value
        LIBRARY, // documented by a library's spring-configuration-metadata.json
        UNKNOWN, // nothing the documentation knows of reads it (likely a typo or a leftover)
        UNCHECKED // not the project's, and no library metadata was available to check it against
    }

    private String key; // as first written
    private Status status;
    private String description; // the comment above it, else the metadata or javadoc description
    private String typeName;
    private String defaultValue;
    private boolean deprecated;
    private List<String> boundTo; // @ConfigurationProperties types and @Value members (Type#member) reading it
    private List<ConfigurationValue> values; // where it is set
    private String suggestion; // a known key it is close to, for UNKNOWN keys
}
