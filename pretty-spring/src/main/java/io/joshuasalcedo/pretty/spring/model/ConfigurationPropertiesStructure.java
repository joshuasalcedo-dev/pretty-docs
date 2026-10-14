package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A {@code @ConfigurationProperties} type and the keys it binds.
 */
@Data
@Builder
public class ConfigurationPropertiesStructure {
    private String prefix;
    private String typeName;
    private boolean registered; // reachable as a bean (component, @EnableConfigurationProperties, scan, or @Bean)
    private List<ConfigurationProperty> properties;
}
