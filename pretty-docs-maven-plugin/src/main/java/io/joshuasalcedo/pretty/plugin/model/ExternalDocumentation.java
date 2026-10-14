package io.joshuasalcedo.pretty.plugin.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * An external javadoc site (the JDK, a dependency on javadoc.io, or a configured link) and the
 * packages it documents that this site refers to. A type's page is
 * {@code baseUrl + [module + "/"] + package/path/ + Outer.Inner.html}.
 */
@Data
@Builder
public class ExternalDocumentation {
    private String label; // e.g. "Java SE 21" or "gson 2.13.1"
    private String baseUrl; // ends with "/"
    private Map<String, String> packages; // package -> module name, "" for non-modular sites
}
