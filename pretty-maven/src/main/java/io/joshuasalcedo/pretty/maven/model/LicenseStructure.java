package io.joshuasalcedo.pretty.maven.model;

import lombok.Builder;
import lombok.Data;

/**
 * A project license.
 */
@Data
@Builder
public class LicenseStructure {
    private String name;
    private String url;
}
