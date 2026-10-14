package io.joshuasalcedo.pretty.maven.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A developer or contributor listed in the POM.
 */
@Data
@Builder
public class DeveloperStructure {
    private String id;
    private String name;
    private String email;
    private String url;
    private String organization;
    private List<String> roles;
}
