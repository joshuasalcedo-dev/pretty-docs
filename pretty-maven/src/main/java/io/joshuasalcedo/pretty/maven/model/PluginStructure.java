package io.joshuasalcedo.pretty.maven.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A build plugin with the goals its executions bind.
 */
@Data
@Builder
public class PluginStructure {
    private String groupId;
    private String artifactId;
    private String version;
    private List<String> goals; // goals bound by its executions, de-duplicated
}
