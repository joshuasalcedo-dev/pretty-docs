package io.joshuasalcedo.pretty.spring.model;

/**
 * How a bean is declared.
 */
public enum BeanKind {
    COMPONENT, SERVICE, REPOSITORY, CONTROLLER, REST_CONTROLLER, CONTROLLER_ADVICE, CONFIGURATION,
    BEAN_METHOD, // @Bean method in a configuration
    DATA_REPOSITORY, // Spring Data repository interface
    CONFIGURATION_PROPERTIES // registered @ConfigurationProperties class
}
