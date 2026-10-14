package io.joshuasalcedo.pretty.spring.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * A web request handler method.
 */
@Data
@Builder
public class EndpointStructure {
    private List<String> httpMethods; // empty means any method
    private String path; // class-level and method-level mappings combined
    private String controllerType;
    private String methodName;
    private String methodId; // name(Type,Type), matching the site member anchors
    private String returnType;
    private List<String> produces;
    private List<String> consumes;
    private List<EndpointParameter> parameters;
    private boolean responseBody; // @RestController or @ResponseBody
    private int line;
}
