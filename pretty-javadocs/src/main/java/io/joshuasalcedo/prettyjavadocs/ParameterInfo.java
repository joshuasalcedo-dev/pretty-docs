package io.joshuasalcedo.prettyjavadocs;

/**
 * Represents information about a method parameter.
 */
public interface ParameterInfo {
    
    /**
     * Returns the name of the parameter.
     *
     * @return The parameter name
     */
    String getName();
    
    /**
     * Returns the type of the parameter.
     *
     * @return The parameter type
     */
    String getType();
    
    /**
     * Returns whether the parameter has a default value.
     *
     * @return true if the parameter has a default value, false otherwise
     */
    boolean hasDefaultValue();
    
    /**
     * Returns the default value of the parameter if present.
     *
     * @return The default value, or null if not present
     */
    String getDefaultValue();
}