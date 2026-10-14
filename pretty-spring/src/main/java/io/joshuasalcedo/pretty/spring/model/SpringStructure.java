package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * The Spring wiring found in a set of sources: what Spring would register and how it fits together.
 */
@Data
@Builder
public class SpringStructure {
    private List<SpringApplicationStructure> applications; // @SpringBootApplication classes
    private List<String> scanPackages; // component-scan base packages (from applications and @ComponentScan)
    private List<BeanDefinition> beans;
    private List<EndpointStructure> endpoints;
    private List<ConfigurationPropertiesStructure> configurationProperties;
    private List<PropertyUsage> propertyUsages; // @Value placeholders
    private List<EntityStructure> entities; // JPA entities
    private List<BeanIssue> issues;
}
