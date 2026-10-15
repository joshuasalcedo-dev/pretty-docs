package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

/**
 * One place a configuration key is set.
 */
@Data
@Builder
public class ConfigurationValue {
    private String moduleId;
    private String path; // the file, relative to the project root
    private String profile; // null for the default profile
    private int line;
    private String value; // as written
}
