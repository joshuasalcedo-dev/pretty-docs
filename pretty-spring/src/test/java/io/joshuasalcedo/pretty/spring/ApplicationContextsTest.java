package io.joshuasalcedo.pretty.spring;

import io.joshuasalcedo.pretty.JavaParser;
import io.joshuasalcedo.pretty.model.JavaFileStructure;
import io.joshuasalcedo.pretty.spring.model.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Per-application contexts: module dependencies, auto-configurations and conditions. */
class ApplicationContextsTest {

    private static JavaFileStructure file(String module, String path, String source) {
        JavaFileStructure parsed = JavaParser.parse(source, path.substring(path.lastIndexOf('/') + 1));
        parsed.setFilePath("/repo/" + module + "/src/main/java/" + path);
        return parsed;
    }

    private static final List<JavaFileStructure> FILES = List.of(
            file("lib", "com/lib/FeatureService.java", "package com.lib;\npublic class FeatureService { }\n"),
            file("lib", "com/lib/LibAutoConfiguration.java", """
                    package com.lib;
                    import java.time.Clock;
                    import org.springframework.boot.autoconfigure.AutoConfiguration;
                    import org.springframework.boot.autoconfigure.condition.*;
                    import org.springframework.context.annotation.Bean;
                    @AutoConfiguration
                    public class LibAutoConfiguration {
                        @Bean @ConditionalOnMissingBean
                        public Clock clock() { return Clock.systemUTC(); }
                        @Bean @ConditionalOnProperty(prefix = "lib.feature", name = "enabled")
                        public FeatureService featureService() { return new FeatureService(); }
                    }
                    """),
            file("lib2", "com/lib2/OtherAutoConfiguration.java", """
                    package com.lib2;
                    import java.time.Clock;
                    import org.springframework.boot.autoconfigure.AutoConfiguration;
                    import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
                    import org.springframework.context.annotation.Bean;
                    @AutoConfiguration
                    public class OtherAutoConfiguration {
                        @Bean @ConditionalOnMissingBean
                        public Clock otherClock() { return Clock.systemUTC(); }
                    }
                    """),
            file("app", "com/app/App.java", """
                    package com.app;
                    import org.springframework.boot.autoconfigure.SpringBootApplication;
                    @SpringBootApplication
                    public class App { public static void main(String[] args) { } }
                    """),
            file("app", "com/app/Consumer.java", """
                    package com.app;
                    import java.time.Clock;
                    import com.lib.FeatureService;
                    import org.springframework.stereotype.Service;
                    @Service
                    public class Consumer { public Consumer(Clock clock, FeatureService features) { } }
                    """),
            file("app2", "com/app2/App2.java", """
                    package com.app2;
                    import org.springframework.boot.autoconfigure.SpringBootApplication;
                    @SpringBootApplication
                    public class App2 { public static void main(String[] args) { } }
                    """),
            file("app2", "com/app2/Consumer2.java", """
                    package com.app2;
                    import com.lib.FeatureService;
                    import org.springframework.stereotype.Service;
                    @Service
                    public class Consumer2 { public Consumer2(FeatureService features) { } }
                    """),
            file("app2", "com/app2/DevOnly.java", """
                    package com.app2;
                    import org.springframework.context.annotation.Profile;
                    import org.springframework.stereotype.Component;
                    @Component @Profile("dev")
                    public class DevOnly { }
                    """),
            file("other", "com/other/Orphan.java", """
                    package com.other;
                    import org.springframework.stereotype.Service;
                    @Service
                    public class Orphan { public Orphan(Missing missing) { } }
                    """),
            file("other", "com/other/Missing.java", "package com.other;\npublic interface Missing { }\n"));

    private static SpringStructure analyze(Map<String, List<String>> libraryKeys) {
        List<ConfigurationFileStructure> files = new ArrayList<>();
        ConfigurationFileStructure app2 = ConfigurationFiles.parse("application.yml", """
                spring:
                  profiles:
                    active: prod
                lib:
                  feature:
                    # Turns the feature service on.
                    enabled: true
                  featur:
                    timeout: 5s
                """);
        app2.setModuleId("app2");
        app2.setPath("app2/src/main/resources/application.yml");
        files.add(app2);
        Map<String, ConfigurationKeyMetadata> metadata = new java.util.HashMap<>();
        libraryKeys.forEach((key, type) -> metadata.put(key, ConfigurationKeyMetadata.builder().name(key).type(type.getFirst()).build()));
        ProjectLayout layout = new ProjectLayout(
                Map.of("/repo/lib/src/main/java", "lib", "/repo/lib2/src/main/java", "lib2", "/repo/app/src/main/java", "app",
                        "/repo/app2/src/main/java", "app2", "/repo/other/src/main/java", "other"),
                Map.of("app", List.of("lib"), "app2", List.of("lib"), "lib", List.of("lib2")),
                Map.of("lib", List.of("com.lib.LibAutoConfiguration"), "lib2", List.of("com.lib2.OtherAutoConfiguration")),
                files,
                metadata);
        return SpringAnalyzer.analyze(FILES, layout).orElseThrow();
    }

    private static SpringStructure analyze() {
        return analyze(Map.of("spring.profiles.active", List.of("java.util.List<java.lang.String>")));
    }

    @Test
    void contextsFollowModuleDependencies() {
        SpringStructure spring = analyze();
        SpringApplicationStructure app = spring.getApplications().stream().filter(a -> a.getTypeName().equals("com.app.App")).findFirst().orElseThrow();
        assertEquals("app", app.getModuleId());
        assertEquals(List.of("app", "lib", "lib2"), app.getModules());
        assertEquals(List.of("com.lib.LibAutoConfiguration", "com.lib2.OtherAutoConfiguration"), app.getAutoConfigurations());

        assertTrue(bean(spring, "orphan").getApplications().isEmpty(), "no application depends on module other");
        assertTrue(spring.getIssues().stream().noneMatch(i -> "orphan".equals(i.getBeanName())), "problems outside every context are not reported");
        assertTrue(bean(spring, "clock").isAutoConfigured());
        assertEquals(List.of("com.app.App"), bean(spring, "consumer").getApplications());
        assertEquals(List.of("com.app.App", "com.app2.App2"), bean(spring, "clock").getApplications());
    }

    @Test
    void conditionalOnMissingBeanBacksOff() {
        SpringStructure spring = analyze();
        assertTrue(spring.getIssues().stream().noneMatch(i -> i.getKind() == BeanIssue.Kind.AMBIGUOUS_DEPENDENCY), spring.getIssues().toString());
        assertEquals("clock", dependency(bean(spring, "consumer"), "clock").getResolvedTo());
        assertTrue(bean(spring, "otherClock").getNotes().stream().anyMatch(n -> n.startsWith("App: backs off") && n.contains("LibAutoConfiguration.clock()")),
                bean(spring, "otherClock").getNotes().toString());
    }

    @Test
    void conditionalOnPropertyAndProfilesUseTheApplicationsConfiguration() {
        SpringStructure spring = analyze();
        BeanIssue unsatisfied = spring.getIssues().stream()
                .filter(i -> i.getKind() == BeanIssue.Kind.UNSATISFIED_DEPENDENCY && "consumer".equals(i.getBeanName())).findFirst().orElseThrow();
        assertEquals(BeanIssue.Severity.WARNING, unsatisfied.getSeverity());
        assertEquals(List.of("com.app.App"), unsatisfied.getApplications());
        assertTrue(unsatisfied.getMessage().contains("@ConditionalOnProperty(lib.feature.enabled)"), unsatisfied.getMessage());

        assertTrue(spring.getIssues().stream().noneMatch(i -> "consumer2".equals(i.getBeanName())), "App2 sets lib.feature.enabled");
        assertEquals(List.of("com.app2.App2"), bean(spring, "featureService").getApplications());

        SpringApplicationStructure app2 = spring.getApplications().stream().filter(a -> a.getTypeName().equals("com.app2.App2")).findFirst().orElseThrow();
        assertEquals(List.of("prod"), app2.getActiveProfiles());
        assertTrue(bean(spring, "devOnly").getApplications().isEmpty());
        assertTrue(bean(spring, "devOnly").getNotes().getFirst().contains("@Profile(dev) is not active"));
    }

    @Test
    void configurationKeysAreCrossReferenced() {
        SpringStructure spring = analyze();
        ConfigurationKey enabled = spring.getConfigurationKeys().stream().filter(k -> k.getKey().equals("lib.feature.enabled")).findFirst().orElseThrow();
        assertEquals("Turns the feature service on.", enabled.getDescription());
        assertEquals(ConfigurationKey.Status.PROJECT, enabled.getStatus(), "read by @ConditionalOnProperty");
        assertEquals(List.of("com.lib.LibAutoConfiguration"), enabled.getBoundTo().stream().map(b -> b.replaceAll("#.*", "")).distinct().toList());
        assertEquals("app2/src/main/resources/application.yml", enabled.getValues().getFirst().getPath());
        assertEquals(7, enabled.getValues().getFirst().getLine());

        // nothing reads lib.featur.timeout, and library metadata is loaded: it is unknown
        ConfigurationKey typo = spring.getConfigurationKeys().stream().filter(k -> k.getKey().equals("lib.featur.timeout")).findFirst().orElseThrow();
        assertEquals(ConfigurationKey.Status.UNKNOWN, typo.getStatus());
        assertEquals(ConfigurationKey.Status.LIBRARY,
                spring.getConfigurationKeys().stream().filter(k -> k.getKey().equals("spring.profiles.active")).findFirst().orElseThrow().getStatus());
        assertTrue(spring.getIssues().stream().anyMatch(i -> i.getKind() == BeanIssue.Kind.UNKNOWN_PROPERTY && i.getLine() == 9));

        // without library metadata, keys the project does not read cannot be judged
        SpringStructure unchecked = analyze(Map.of());
        assertTrue(unchecked.getConfigurationKeys().stream()
                .allMatch(k -> k.getStatus() == (k.getKey().equals("lib.feature.enabled") ? ConfigurationKey.Status.PROJECT : ConfigurationKey.Status.UNCHECKED)));
        assertTrue(unchecked.getIssues().stream().noneMatch(i -> i.getKind() == BeanIssue.Kind.UNKNOWN_PROPERTY));
    }

    @Test
    void siblingApplicationsDoNotSeeEachOthersBeans() {
        // Two applications, each with its own SpringTransactionPort for an interface from a shared module:
        // one context each, so neither is ambiguous (checked as one context, both would be).
        String port = """
                package %s;
                import com.shared.TransactionPort;
                import org.springframework.stereotype.Component;
                @Component
                class SpringTransactionPort implements TransactionPort { }
                """;
        String app = """
                package %s;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class %s { public static void main(String[] args) { } }
                """;
        String service = """
                package %s;
                import com.shared.TransactionPort;
                import org.springframework.stereotype.Service;
                @Service
                public class %s { public %s(TransactionPort transactions) { } }
                """;
        List<JavaFileStructure> files = List.of(
                file("shared", "com/shared/TransactionPort.java", "package com.shared;\npublic interface TransactionPort { }\n"),
                file("chat", "com/chat/ChatApp.java", app.formatted("com.chat", "ChatApp")),
                file("chat", "com/chat/SpringTransactionPort.java", port.formatted("com.chat")),
                file("chat", "com/chat/PayService.java", service.formatted("com.chat", "PayService", "PayService")),
                file("auth", "com/auth/AuthApp.java", app.formatted("com.auth", "AuthApp")),
                file("auth", "com/auth/SpringTransactionPort.java", port.formatted("com.auth")),
                file("auth", "com/auth/UserService.java", service.formatted("com.auth", "UserService", "UserService")));
        ProjectLayout layout = new ProjectLayout(
                Map.of("/repo/shared/src/main/java", "shared", "/repo/chat/src/main/java", "chat", "/repo/auth/src/main/java", "auth"),
                Map.of("chat", List.of("shared"), "auth", List.of("shared")),
                Map.of(), List.of(), Map.of());

        SpringStructure spring = SpringAnalyzer.analyze(files, layout).orElseThrow();

        assertTrue(spring.getIssues().stream().noneMatch(i -> i.getSeverity() != BeanIssue.Severity.INFO), spring.getIssues().toString());
        List<BeanDefinition> ports = spring.getBeans().stream().filter(b -> b.getName().equals("springTransactionPort")).toList();
        assertEquals(2, ports.size());
        ports.forEach(p -> assertEquals(1, p.getApplications().size(), p.getTypeName() + " " + p.getApplications()));
        assertEquals(List.of("com.chat.ChatApp"), bean(spring, "payService").getApplications());
        assertEquals("springTransactionPort", dependency(bean(spring, "payService"), "transactions").getResolvedTo());
        assertEquals(List.of("com.auth.AuthApp"), bean(spring, "userService").getApplications());
        assertEquals(List.of("springTransactionPort"), dependency(bean(spring, "userService"), "transactions").getCandidates());
    }

    @Test
    void withoutLayoutEverythingIsOneContext() {
        SpringStructure spring = SpringAnalyzer.analyze(FILES).orElseThrow();
        assertTrue(spring.getIssues().stream().anyMatch(i -> i.getKind() == BeanIssue.Kind.UNSATISFIED_DEPENDENCY && "orphan".equals(i.getBeanName())));
        assertEquals(List.of("clock", "otherClock"), dependency(bean(spring, "consumer"), "clock").getCandidates());
        assertTrue(bean(spring, "consumer").getApplications().isEmpty());
    }

    private static BeanDefinition bean(SpringStructure spring, String name) {
        return spring.getBeans().stream().filter(b -> b.getName().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no bean " + name));
    }

    private static InjectionPoint dependency(BeanDefinition bean, String name) {
        return bean.getDependencies().stream().filter(p -> p.getName().equals(name)).findFirst().orElseThrow();
    }
}
