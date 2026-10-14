package io.joshuasalcedo.pretty.maven.model;

import lombok.Builder;
import lombok.Data;

/**
 * A declared (or managed) dependency.
 */
@Data
@Builder
public class DependencyStructure {
    private String groupId;
    private String artifactId;
    private String version;
    private String scope; // compile when not declared
    private String type;
    private String classifier;
    private boolean optional;
}
