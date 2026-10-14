package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A {@code @SpringBootApplication} class.
 */
@Data
@Builder
public class SpringApplicationStructure {
    private String typeName; // qualified name
    private boolean hasMainMethod;
    private List<String> scanPackages; // scanBasePackages, or the package of the class
    private List<String> excludedAutoConfigurations;
}
