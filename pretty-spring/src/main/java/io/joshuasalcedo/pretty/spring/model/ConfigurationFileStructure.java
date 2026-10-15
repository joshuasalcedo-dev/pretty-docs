package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A Spring Boot configuration file (application*.yml/.yaml/.properties, bootstrap*), flattened to keys.
 */
@Data
@Builder
public class ConfigurationFileStructure {
    private String moduleId; // the module whose resources hold it
    private String path; // relative to the documented project root
    private String fileName;
    private String format; // yaml or properties
    private String profile; // from the file name (application-prod.yml -> prod); null for the default file
    private String content; // the file as written, for display
    private List<ConfigurationEntry> entries;
}
