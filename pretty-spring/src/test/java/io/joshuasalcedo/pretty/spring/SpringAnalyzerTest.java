package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.JavaParser;
import io.joshuasalcedo.pretty.model.JavaFileStructure;
import io.joshuasalcedo.pretty.spring.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SpringAnalyzerTest {

    private static final List<String> SOURCES = List.of(
            """
            package com.shop;
            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.boot.context.properties.EnableConfigurationProperties;
            @SpringBootApplication
            @EnableConfigurationProperties(MailProperties.class)
            public class ShopApplication {
                public static void main(String[] args) { SpringApplication.run(ShopApplication.class, args); }
            }
            """,
            """
            package com.shop;
            import org.springframework.boot.context.properties.ConfigurationProperties;
            import java.time.Duration;
            @ConfigurationProperties(prefix = "shop.mail")
            public class MailProperties {
                /** SMTP host to connect to. */
                private String host = "localhost";
                private int retryCount = 3;
                private Duration timeout;
                private Sender sender = new Sender();
                public static class Sender { private String fromAddress; }
            }
            """,
            """
            package com.shop.orders;
            import lombok.RequiredArgsConstructor;
            import org.springframework.stereotype.Service;
            import org.springframework.beans.factory.annotation.Value;
            import org.springframework.jdbc.core.JdbcTemplate;
            import com.shop.payments.PaymentGateway;
            import java.util.List;
            @Service
            @RequiredArgsConstructor
            public class OrderService {
                private final OrderRepository orders;
                private final PaymentGateway gateway;          // two implementations: ambiguous
                private final List<PaymentGateway> allGateways; // collections are fine
                private final JdbcTemplate jdbc;               // auto-configured, not in the project
                private final InvoiceRenderer renderer;        // project type with no bean
                @Value("${shop.orders.page-size:20}")
                private int pageSize;
            }
            """,
            """
            package com.shop.orders;
            import org.springframework.data.jpa.repository.JpaRepository;
            public interface OrderRepository extends JpaRepository<Order, Long> { }
            """,
            """
            package com.shop.orders;
            public interface InvoiceRenderer { String render(Order order); }
            """,
            """
            package com.shop.orders;
            import jakarta.persistence.*;
            @Entity
            @Table(name = "orders")
            public class Order { @Id private Long id; private String customer; }
            """,
            """
            package com.shop.payments;
            public interface PaymentGateway { void charge(long cents); }
            """,
            """
            package com.shop.payments;
            import org.springframework.stereotype.Component;
            @Component
            public class StripeGateway implements PaymentGateway { public void charge(long cents) { } }
            """,
            """
            package com.shop.payments;
            import org.springframework.stereotype.Component;
            import org.springframework.context.annotation.Profile;
            @Component("paypal")
            @Profile("eu")
            public class PaypalGateway implements PaymentGateway { public void charge(long cents) { } }
            """,
            """
            package com.shop.web;
            import org.springframework.web.bind.annotation.*;
            import com.shop.orders.OrderService;
            import com.shop.orders.Order;
            import java.util.List;
            @RestController
            @RequestMapping("/api/orders")
            public class OrderController {
                private final OrderService service;
                public OrderController(OrderService service) { this.service = service; }
                @GetMapping
                public List<Order> list(@RequestParam(defaultValue = "0") int page) { return List.of(); }
                @GetMapping("/{id}")
                public Order get(@PathVariable long id) { return null; }
                @PostMapping(consumes = "application/json")
                public Order create(@RequestBody Order order) { return order; }
                @RequestMapping(value = "/{id}", method = {RequestMethod.PUT, RequestMethod.PATCH})
                public Order update(@PathVariable("id") long orderId, @RequestBody Order order) { return order; }
            }
            """,
            """
            package com.shop.cycle;
            import org.springframework.stereotype.Service;
            @Service
            public class Alpha { public Alpha(Beta beta) { } }
            """,
            """
            package com.shop.cycle;
            import org.springframework.stereotype.Service;
            import org.springframework.beans.factory.annotation.Autowired;
            @Service
            public class Beta { @Autowired private Alpha alpha; }
            """,
            """
            package com.shop.config;
            import org.springframework.context.annotation.*;
            import java.time.Clock;
            @Configuration
            public class AppConfig {
                @Bean @Primary public Clock clock() { return Clock.systemUTC(); }
                @Bean({"stripeGateway", "legacy"}) public Object duplicate() { return new Object(); }
            }
            """,
            """
            package org.other;
            import org.springframework.stereotype.Component;
            @Component
            public class Stray { }
            """,
            """
            package com.shop.util;
            public class NotSpring { private final String x = ""; }
            """);

    private static SpringStructure analyze() {
        List<JavaFileStructure> files = SOURCES.stream().map(s -> JavaParser.parse(s, "X.java")).toList();
        return SpringAnalyzer.analyze(files).orElseThrow();
    }

    @Test
    void ignoresNonSpringCode() {
        assertTrue(SpringAnalyzer.analyze(List.of(JavaParser.parse("package a; public class B { }", "B.java"))).isEmpty());
    }

    @Test
    void findsApplicationAndBeans() {
        SpringStructure spring = analyze();

        SpringApplicationStructure app = spring.getApplications().getFirst();
        assertEquals("com.shop.ShopApplication", app.getTypeName());
        assertTrue(app.isHasMainMethod());
        assertEquals(List.of("com.shop"), spring.getScanPackages());

        assertEquals(BeanKind.SERVICE, bean(spring, "orderService").getKind());
        assertEquals(BeanKind.DATA_REPOSITORY, bean(spring, "orderRepository").getKind());
        assertEquals(BeanKind.REST_CONTROLLER, bean(spring, "orderController").getKind());
        assertEquals(BeanKind.CONFIGURATION, bean(spring, "shopApplication").getKind());
        assertEquals(List.of("eu"), bean(spring, "paypal").getProfiles(), "explicit bean name from @Component(\"paypal\")");
        BeanDefinition clock = bean(spring, "clock");
        assertEquals(BeanKind.BEAN_METHOD, clock.getKind());
        assertTrue(clock.isPrimary());
        assertEquals("com.shop.config.AppConfig", clock.getDeclaringType());
        assertEquals(BeanKind.CONFIGURATION_PROPERTIES, bean(spring, "shop.mail-com.shop.MailProperties").getKind());
        assertTrue(spring.getBeans().stream().noneMatch(b -> b.getTypeName().endsWith("NotSpring")));
    }

    @Test
    void resolvesInjectionIncludingLombokConstructors() {
        BeanDefinition service = bean(analyze(), "orderService");

        assertEquals(List.of("orders", "gateway", "allGateways", "jdbc", "renderer"),
                service.getDependencies().stream().map(InjectionPoint::getName).toList(), "@RequiredArgsConstructor final fields, not @Value");
        InjectionPoint orders = dependency(service, "orders");
        assertEquals(InjectionPoint.Kind.CONSTRUCTOR, orders.getKind());
        assertEquals("orderRepository", orders.getResolvedTo());
        InjectionPoint all = dependency(service, "allGateways");
        assertTrue(all.isCollection());
        assertEquals("com.shop.payments.PaymentGateway", all.getTypeName());
        assertEquals(2, all.getCandidates().size());
        assertTrue(bean(analyze(), "stripeGateway").getInjectedInto().contains("orderService"));
    }

    @Test
    void reportsWiringProblems() {
        SpringStructure spring = analyze();

        BeanIssue ambiguous = issue(spring, BeanIssue.Kind.AMBIGUOUS_DEPENDENCY, "orderService");
        assertEquals(BeanIssue.Severity.WARNING, ambiguous.getSeverity(), "paypal is profile-specific, so it may be fine");
        assertTrue(ambiguous.getMessage().contains("gateway"));

        BeanIssue missing = issue(spring, BeanIssue.Kind.UNSATISFIED_DEPENDENCY, "orderService");
        assertEquals(BeanIssue.Severity.ERROR, missing.getSeverity());
        assertTrue(missing.getMessage().contains("InvoiceRenderer"));

        BeanIssue external = spring.getIssues().stream().filter(i -> i.getKind() == BeanIssue.Kind.EXTERNAL_DEPENDENCY).findFirst().orElseThrow();
        assertEquals(BeanIssue.Severity.INFO, external.getSeverity());
        assertTrue(external.getMessage().startsWith("JdbcTemplate"));

        BeanIssue cycle = issue(spring, BeanIssue.Kind.CIRCULAR_DEPENDENCY, "alpha");
        assertTrue(cycle.getMessage().contains("alpha -> beta -> alpha"), cycle.getMessage());
        assertTrue(cycle.getMessage().contains("allow-circular-references"), "field injection in the cycle");

        assertEquals(BeanIssue.Severity.ERROR, issue(spring, BeanIssue.Kind.DUPLICATE_BEAN_NAME, "stripeGateway").getSeverity());
        assertTrue(issue(spring, BeanIssue.Kind.OUTSIDE_COMPONENT_SCAN, "stray").getMessage().contains("org.other"));
        assertFalse(bean(spring, "stray").isInComponentScan());
        assertTrue(issue(spring, BeanIssue.Kind.FIELD_INJECTION, "beta").getMessage().contains("alpha"));
        assertEquals(BeanIssue.Severity.ERROR, spring.getIssues().getFirst().getSeverity(), "errors sort first");
    }

    @Test
    void findsEndpoints() {
        List<EndpointStructure> endpoints = analyze().getEndpoints();

        assertEquals(List.of("GET /api/orders", "POST /api/orders", "GET /api/orders/{id}", "PUT,PATCH /api/orders/{id}"),
                endpoints.stream().map(e -> String.join(",", e.getHttpMethods()) + " " + e.getPath()).toList());
        EndpointStructure list = endpoints.getFirst();
        assertTrue(list.isResponseBody());
        assertEquals("list(int)", list.getMethodId());
        assertEquals("List<Order>", list.getReturnType());
        EndpointParameter page = list.getParameters().getFirst();
        assertEquals("query", page.getSource());
        assertFalse(page.isRequired(), "a defaultValue makes it optional");
        assertEquals("0", page.getDefaultValue());
        EndpointStructure update = endpoints.get(3);
        assertEquals("id", update.getParameters().getFirst().getName(), "@PathVariable(\"id\") renames orderId");
        assertEquals("body", update.getParameters().get(1).getSource());
        assertEquals(List.of("application/json"), endpoints.get(1).getConsumes());
    }

    @Test
    void findsConfigurationPropertiesValuesAndEntities() {
        SpringStructure spring = analyze();

        ConfigurationPropertiesStructure mail = spring.getConfigurationProperties().getFirst();
        assertEquals("shop.mail", mail.getPrefix());
        assertTrue(mail.isRegistered(), "@EnableConfigurationProperties(MailProperties.class)");
        assertEquals(List.of("shop.mail.host", "shop.mail.retry-count", "shop.mail.timeout", "shop.mail.sender.from-address"),
                mail.getProperties().stream().map(ConfigurationProperty::getKey).toList());
        ConfigurationProperty host = mail.getProperties().getFirst();
        assertEquals("\"localhost\"", host.getDefaultValue());
        assertEquals("SMTP host to connect to.", host.getDescription());

        PropertyUsage pageSize = spring.getPropertyUsages().getFirst();
        assertEquals("shop.orders.page-size", pageSize.getKey());
        assertEquals("20", pageSize.getDefaultValue());

        EntityStructure order = spring.getEntities().getFirst();
        assertEquals("orders", order.getTableName());
        assertEquals("id", order.getIdField());
    }

    @Test
    void namingHelpers() {
        assertEquals("orderService", SpringAnalyzer.decapitalize("OrderService"));
        assertEquals("URLParser", SpringAnalyzer.decapitalize("URLParser"));
        assertEquals("retry-count", SpringAnalyzer.kebab("retryCount"));
        assertTrue(SpringAnalyzer.sameType("com.a.Foo", "Foo"));
        assertFalse(SpringAnalyzer.sameType("com.a.Foo", "com.b.Foo"));
        assertTrue(SpringAnalyzer.inPackage("com.shop.orders", "com.shop"));
        assertFalse(SpringAnalyzer.inPackage("com.shopping", "com.shop"));
    }

    private static BeanDefinition bean(SpringStructure spring, String name) {
        return spring.getBeans().stream().filter(b -> b.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("no bean " + name + " in " + spring.getBeans().stream().map(BeanDefinition::getName).toList()));
    }

    private static InjectionPoint dependency(BeanDefinition bean, String name) {
        return bean.getDependencies().stream().filter(d -> d.getName().equals(name)).findFirst().orElseThrow();
    }

    private static BeanIssue issue(SpringStructure spring, BeanIssue.Kind kind, String bean) {
        Optional<BeanIssue> found = spring.getIssues().stream().filter(i -> i.getKind() == kind && bean.equals(i.getBeanName())).findFirst();
        return found.orElseThrow(() -> new AssertionError("no " + kind + " for " + bean + " in " + spring.getIssues()));
    }
}
