package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

/**
 * One key set in a configuration file, e.g. {@code server.port: 9000} or {@code accounts[0].email: ...}.
 */
@Data
@Builder
public class ConfigurationEntry {
    private String key; // flattened, as written (lists as [index])
    private String value; // as written, placeholders included
    private int line; // 1-based
    private String profile; // the file's profile, or the YAML document's spring.config.activate.on-profile
    private String comment; // the comment written right above the key, if any
    private String environmentVariable; // VAR in ${VAR:default}, when the value is a single placeholder
    private String placeholderDefault; // default in ${VAR:default}
}
