package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

/**
 * A handler parameter bound from the request.
 */
@Data
@Builder
public class EndpointParameter {
    private String source; // path, query, body, header, cookie, form
    private String name; // as bound in the request
    private String typeName;
    private boolean required;
    private String defaultValue;
}
