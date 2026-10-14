package io.joshuasalcedo.pretty.maven.model;

import lombok.Builder;
import lombok.Data;

/**
 * Source control information.
 */
@Data
@Builder
public class ScmStructure {
    private String url;
    private String connection;
    private String developerConnection;
    private String tag;
}
