package io.joshuasalcedo.pretty.ts;

import io.joshuasalcedo.pretty.model.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TypeScriptParserTest {

    @TempDir
    Path dir;

    @Test
    void discoversProjectsAndMonorepoPackages() throws IOException {
        write("web/tsconfig.json", "{}");
        write("web/package.json", "{}");
        write("web/src/tsconfig.json", "{}"); // a variant of web's configuration, no package.json
        write("libs/ui/tsconfig.json", "{}");
        write("libs/ui/package.json", "{}");
        write("libs/ui/packages/icons/tsconfig.json", "{}");
        write("libs/ui/packages/icons/package.json", "{}"); // a monorepo package: its own project
        write("web/node_modules/x/tsconfig.json", "{}");
        write("legacy/old/tsconfig.json", "{}");

        List<String> found = TypeScriptParser.discover(dir, List.of("legacy")).stream()
                .map(p -> dir.relativize(p).toString().replace('\\', '/')).sorted().toList();

        assertEquals(List.of("libs/ui", "libs/ui/packages/icons", "web"), found);
    }

    @Test
    void findsWorkspacesAndTheirMembers() throws IOException {
        write("shop/package.json", "{\"name\": \"shop\", \"workspaces\": [\"apps/*\", \"packages/**\", \"!packages/legacy\"]}");
        write("shop/yarn.lock", "");
        write("site/pnpm-workspace.yaml", "# packages\npackages:\n  - 'apps/*'   # the apps\n  - \"libs/*\"\ncatalog:\n  react: ^19\n");
        write("classic/package.json", "{\"workspaces\": {\"packages\": [\"modules/*\"]}}");
        write("plain/package.json", "{\"name\": \"plain\"}");

        List<TypeScriptParser.Workspace> workspaces = TypeScriptParser.workspaces(dir, List.of());
        assertEquals(List.of("classic:npm", "shop:yarn", "site:pnpm"), workspaces.stream()
                .map(w -> dir.relativize(w.dir()) + ":" + w.manager()).sorted().toList());

        TypeScriptParser.Workspace shop = workspaces.stream().filter(w -> w.manager().equals("yarn")).findFirst().orElseThrow();
        assertTrue(shop.contains(dir.resolve("shop/apps/web")));
        assertTrue(shop.contains(dir.resolve("shop/packages/ui/icons")));
        assertFalse(shop.contains(dir.resolve("shop/apps/web/nested")));
        assertFalse(shop.contains(dir.resolve("shop/packages/legacy")));
        assertFalse(shop.contains(dir.resolve("shop")));
        assertFalse(shop.contains(dir.resolve("plain")));

        TypeScriptParser.Workspace site = workspaces.stream().filter(w -> w.manager().equals("pnpm")).findFirst().orElseThrow();
        assertEquals(List.of("apps/*", "libs/*"), site.patterns());
        assertTrue(site.contains(dir.resolve("site/libs/core")));
    }

    @Test
    void readsDeclarationsIntoTheJavaModel() throws IOException {
        Assumptions.assumeTrue(TypeScriptParser.nodeVersion().isPresent(), "node is not on the PATH");
        write("shop/tsconfig.json", """
                { "compilerOptions": { "target": "ES2022", "module": "ESNext", "moduleResolution": "bundler", "strict": true }, "include": ["src"] }
                """);
        write("shop/src/model/user.ts", """
                /** A registered user. */
                export interface User {
                  readonly id: string
                  roles?: Role[]
                }

                export enum Role { Admin = "admin" }

                /** Loads users. */
                export abstract class Repository<T extends User> {
                  constructor(private readonly endpoint: string) {}
                  /**
                   * Loads one user.
                   * @param id the user's id
                   * @returns the user, if any
                   */
                  abstract find(id: string): Promise<T | undefined>
                }

                /** Default page size. */
                export const PAGE_SIZE = 20

                export const UserBadge = ({ user }: { user: User }) => user.id

                function helper(): number { return PAGE_SIZE }
                """);
        write("shop/src/model/user.test.ts", "export const x = 1\n");
        write("shop/src/broken.ts", "export const count: number = \"three\"\n");

        TypeScriptParser.Result result = TypeScriptParser.parse(
                        List.of(new TypeScriptParser.Project("shop", dir.resolve("shop"), "@demo/shop")), false, false, true,
                        Files.createDirectories(dir.resolve("work")), message -> fail(message))
                .orElseThrow();

        TypeScriptParser.ProjectFiles shop = result.projects().get("shop");
        assertTrue(shop.warnings().isEmpty(), shop.warnings().toString());
        assertEquals(2, shop.files().size(), "test files are left out");
        JavaFileStructure file = shop.files().stream().filter(f -> f.getFileName().equals("user.ts")).findFirst().orElseThrow();

        assertEquals(1, shop.diagnostics().size(), shop.diagnostics().toString());
        TypeScriptParser.Diagnostic error = shop.diagnostics().getFirst();
        assertEquals(2322, error.code()); // Type 'string' is not assignable to type 'number'
        assertEquals("error", error.category());
        assertTrue(error.file().endsWith("broken.ts"));
        assertEquals(1, error.line());
        assertEquals("typescript", file.getLanguage());
        assertEquals("src/model", file.getPackageName());

        TypeStructure user = type(file, "User");
        assertEquals(TypeKind.INTERFACE, user.getKind());
        assertEquals("@demo/shop/src/model/user.User", user.getQualifiedName());
        assertEquals("A registered user.", user.getJavadoc().getDescription());
        assertEquals("@demo/shop/src/model/user.Role", user.getFields().get(1).getType().getQualifiedName());
        assertTrue(user.getUsedBy().contains("@demo/shop/src/model/user.UserBadge"), user.getUsedBy().toString());

        TypeStructure repository = type(file, "Repository");
        assertEquals("export abstract class Repository<T extends User>", repository.getSignature());
        MethodStructure find = repository.getMethods().getFirst();
        assertEquals("abstract find(id: string): Promise<T | undefined>", find.getSignature());
        assertEquals("@param", find.getJavadoc().getTags().getFirst().getName());
        assertEquals("@return", find.getJavadoc().getTags().get(1).getName());
        assertEquals(ModifierKind.PRIVATE, repository.getFields().getFirst().getModifiers().getFirst().getKind(), "parameter property");

        assertEquals(TypeKind.VARIABLE, type(file, "PAGE_SIZE").getKind());
        assertEquals(List.of("@demo/shop/src/model/user.helper"), type(file, "PAGE_SIZE").getUsedBy(), "value usages count too");
        assertEquals(TypeKind.FUNCTION, type(file, "UserBadge").getKind());
        assertEquals(ModifierKind.PACKAGE_PRIVATE, type(file, "helper").getModifiers().getFirst().getKind(), "not exported");
        assertEquals(TypeKind.ENUM, type(file, "Role").getKind());
        assertTrue(file.getCodeLines() > 10 && file.getCommentLines() >= 7, file.getCodeLines() + "/" + file.getCommentLines());
    }

    private static TypeStructure type(JavaFileStructure file, String name) {
        return file.getTypes().stream().filter(t -> t.getName().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no " + name));
    }

    private void write(String path, String content) throws IOException {
        Path file = dir.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
