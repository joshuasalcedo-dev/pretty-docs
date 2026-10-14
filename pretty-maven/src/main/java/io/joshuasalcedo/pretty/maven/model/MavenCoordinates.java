package io.joshuasalcedo.pretty.maven.model;

import lombok.Builder;
import lombok.Data;

/**
 * Maven groupId:artifactId:version.
 */
@Data
@Builder
public class MavenCoordinates {
    private String groupId;
    private String artifactId;
    private String version;
}
