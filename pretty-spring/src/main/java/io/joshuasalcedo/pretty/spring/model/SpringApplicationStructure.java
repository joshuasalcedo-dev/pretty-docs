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
    private String moduleId; // module holding the class, when the build layout is known
    private List<String> modules; // that module and every build module it depends on: what is on its classpath
    private List<String> activeProfiles; // spring.profiles.active from its configuration files
    private List<String> autoConfigurations; // project auto-configurations its context imports
    private int beanCount; // beans in its context (0 when not worked out)
}
