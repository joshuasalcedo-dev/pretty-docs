import { ExternalLinkIcon } from "lucide-react"
import { useState } from "react"

import { DiagramView, diagramsFor } from "@/components/docs/diagram-view"
import { ReadmeView } from "@/components/docs/javadoc-text"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useDocs } from "@/lib/docs/context"
import { coverage, formatNumber, moduleName, routes } from "@/lib/docs/model"
import type { Dependency, ModuleDocumentation } from "@/lib/docs/types"
import { AssetGrid, BackdropSwitch } from "@/pages/assets-page"

export function ModulePage({ module, home }: { module: ModuleDocumentation; home?: boolean }) {
  const index = useDocs()
  const project = module.project
  const packages = index.packages.get(module.id) ?? []
  const typescript = module.language === "typescript"
  const assets = module.assets ?? []
  const [backdrop, setBackdrop] = useState<"checker" | "light" | "dark">("checker")
  // the home page lists every module, Maven modules before TypeScript packages
  const subModules = home
    ? index.site.modules
        .filter((m) => m.id !== module.id)
        .sort((a, b) => Number(a.language === "typescript") - Number(b.language === "typescript"))
    : (index.children.get(module.id) ?? [])
  const stats = home ? totals(index.site.modules) : module.statistics
  const diagrams = [
    ...(home ? diagramsFor(index.site, "MODULE_DEPENDENCIES") : []),
    ...diagramsFor(index.site, "PACKAGE_CLASSES", module.id),
  ]

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="secondary">{project.packaging}</Badge>
          {project.version && <Badge variant="outline">v{project.version}</Badge>}
          <span className="font-mono text-xs text-muted-foreground">{module.id}</span>
        </div>
        <h1 className="font-heading text-4xl font-bold tracking-tight">
          {home ? index.site.title || moduleName(module) : moduleName(module)}
        </h1>
        {project.description && <p className="max-w-3xl font-serif text-lg leading-8">{project.description}</p>}
        <ProjectLinks module={module} />
      </header>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {home && index.site.modules.length > 1 && <Stat label="Modules" value={index.site.modules.length} />}
        <Stat label="Packages" value={stats.packages} />
        <Stat label="Types" value={stats.types} />
        <Stat label="Methods" value={stats.methods} />
        <Stat label="Documented" value={coverage(stats.documented, stats.documentable)} hint={`${formatNumber(stats.documented)} of ${formatNumber(stats.documentable)} elements`} />
      </div>

      <Tabs key={module.id} defaultValue={module.readmeHtml ? "readme" : subModules.length > 0 && packages.length === 0 ? "modules" : "packages"}>
        <TabsList variant="pill">
          {module.readmeHtml && <TabsTrigger value="readme">README</TabsTrigger>}
          <TabsTrigger value="packages">{typescript ? "Folders" : "Packages"}</TabsTrigger>
          {subModules.length > 0 && <TabsTrigger value="modules">{typescript ? "Packages" : "Modules"}</TabsTrigger>}
          {assets.length > 0 && <TabsTrigger value="assets">Assets</TabsTrigger>}
          {diagrams.length > 0 && <TabsTrigger value="diagrams">Diagrams</TabsTrigger>}
          <TabsTrigger value="dependencies">Dependencies</TabsTrigger>
          <TabsTrigger value="build">Build</TabsTrigger>
          {home && <TabsTrigger value="about">About</TabsTrigger>}
        </TabsList>

        {module.readmeHtml && (
          <TabsContent value="readme" className="pt-4">
            <ReadmeView html={module.readmeHtml} />
          </TabsContent>
        )}

        <TabsContent value="packages" className="pt-4">
          {packages.length === 0 ? (
            <Empty>
              <EmptyHeader>
                <EmptyTitle>{typescript ? "No TypeScript sources" : "No Java sources"}</EmptyTitle>
                <EmptyDescription>
                  {subModules.length > 0 ? (typescript ? "This workspace holds other packages." : "This module aggregates other modules.") : "Nothing documented at this visibility."}
                </EmptyDescription>
              </EmptyHeader>
            </Empty>
          ) : (
            <div className="grid gap-3 md:grid-cols-2">
              {packages.map((pkg) => (
                <a key={pkg.name} href={routes.pkg(module.id, pkg.name)} className="group">
                  <Card size="sm" className="h-full transition-colors group-hover:bg-muted/50">
                    <CardHeader>
                      <CardTitle className="font-mono text-sm">{pkg.name || "(default package)"}</CardTitle>
                      <CardDescription>
                        {pkg.types.length} {pkg.types.length === 1 ? "type" : "types"}
                      </CardDescription>
                    </CardHeader>
                  </Card>
                </a>
              ))}
            </div>
          )}
        </TabsContent>

        {subModules.length > 0 && (
          <TabsContent value="modules" className="pt-4">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Module</TableHead>
                  <TableHead>Path</TableHead>
                  <TableHead>Packaging</TableHead>
                  <TableHead className="text-right">Types</TableHead>
                  <TableHead className="text-right">Documented</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {subModules.map((m) => (
                  <TableRow key={m.id}>
                    <TableCell>
                      <a href={routes.module(m.id)} className="font-medium text-primary underline-offset-4 hover:underline">
                        {moduleName(m)}
                      </a>
                      <div className="font-mono text-xs text-muted-foreground">{m.project.artifactId}</div>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{m.relativePath || "."}</TableCell>
                    <TableCell>{m.project.packaging}</TableCell>
                    <TableCell className="text-right tabular-nums">{formatNumber(m.statistics.types)}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {coverage(m.statistics.documented, m.statistics.documentable)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TabsContent>
        )}

        {assets.length > 0 && (
          <TabsContent value="assets" className="flex flex-col gap-4 pt-4">
            <BackdropSwitch value={backdrop} onChange={setBackdrop} />
            <AssetGrid assets={assets} backdrop={backdrop} />
          </TabsContent>
        )}

        {diagrams.length > 0 && (
          <TabsContent value="diagrams" className="flex flex-col gap-8 pt-4">
            {diagrams.map((d) => (
              <section key={d.id} className="flex flex-col gap-2">
                <h2 className="font-mono text-sm font-medium">{d.title}</h2>
                <DiagramView diagram={d} />
              </section>
            ))}
          </TabsContent>
        )}

        <TabsContent value="dependencies" className="flex flex-col gap-6 pt-4">
          <DependencyTable title="Dependencies" dependencies={project.dependencies} />
          {project.managedDependencies.length > 0 && (
            <DependencyTable title="Managed dependencies" dependencies={project.managedDependencies} />
          )}
        </TabsContent>

        <TabsContent value="build" className="pt-4">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Plugin</TableHead>
                <TableHead>Version</TableHead>
                <TableHead>Goals</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {project.plugins.map((p) => (
                <TableRow key={`${p.groupId}:${p.artifactId}`}>
                  <TableCell className="font-mono text-xs">{p.groupId ? `${p.groupId}:` : ""}{p.artifactId}</TableCell>
                  <TableCell className="font-mono text-xs">{p.version ?? "—"}</TableCell>
                  <TableCell className="flex flex-wrap gap-1">
                    {p.goals.map((g) => (
                      <Badge key={g} variant="outline">{g}</Badge>
                    ))}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TabsContent>

        {home && (
          <TabsContent value="about" className="grid gap-4 pt-4 md:grid-cols-2">
            <About module={module} />
          </TabsContent>
        )}
      </Tabs>
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: number | string; hint?: string }) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums">{typeof value === "number" ? formatNumber(value) : value}</CardTitle>
        {hint && <CardDescription className="text-xs">{hint}</CardDescription>}
      </CardHeader>
    </Card>
  )
}

function totals(modules: ModuleDocumentation[]) {
  return modules.reduce(
    (sum, m) => ({
      packages: sum.packages + m.statistics.packages,
      types: sum.types + m.statistics.types,
      methods: sum.methods + m.statistics.methods,
      documented: sum.documented + m.statistics.documented,
      documentable: sum.documentable + m.statistics.documentable,
    }),
    { packages: 0, types: 0, methods: 0, documented: 0, documentable: 0 }
  )
}

function ProjectLinks({ module }: { module: ModuleDocumentation }) {
  const p = module.project
  const links = [
    { label: "Website", url: p.url },
    { label: "Source", url: p.scm?.url },
    { label: "Issues", url: p.issueManagementUrl },
    { label: "CI", url: p.ciManagementUrl },
  ].filter((l): l is { label: string; url: string } => !!l.url && /^https?:/.test(l.url))
  if (links.length === 0) return null
  return (
    <div className="flex flex-wrap gap-4 text-sm">
      {links.map((l) => (
        <a key={l.label} href={l.url} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary underline-offset-4 hover:underline">
          {l.label}
          <ExternalLinkIcon className="size-3.5" />
        </a>
      ))}
    </div>
  )
}

function DependencyTable({ title, dependencies }: { title: string; dependencies: Dependency[] }) {
  return (
    <div className="flex flex-col gap-2">
      <h2 className="text-sm font-medium">{title}</h2>
      {dependencies.length === 0 ? (
        <p className="text-sm text-muted-foreground">None declared.</p>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Artifact</TableHead>
              <TableHead>Version</TableHead>
              <TableHead>Scope</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {dependencies.map((d, i) => (
              <TableRow key={`${d.groupId}:${d.artifactId}:${d.classifier ?? ""}:${i}`}>
                <TableCell className="font-mono text-xs">
                  {d.type !== "npm" && `${d.groupId}:`}<span className="font-medium text-foreground">{d.artifactId}</span>
                  {d.optional && <Badge variant="outline" className="ml-2">optional</Badge>}
                </TableCell>
                <TableCell className="font-mono text-xs">{d.version ?? (d.type === "npm" ? "—" : "managed")}</TableCell>
                <TableCell>
                  <Badge variant={d.scope === "compile" ? "secondary" : "outline"}>{d.scope}</Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </div>
  )
}

function About({ module }: { module: ModuleDocumentation }) {
  const p = module.project
  return (
    <>
      <Card size="sm">
        <CardHeader>
          <CardTitle>Project</CardTitle>
        </CardHeader>
        <CardContent className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
          <span className="text-muted-foreground">Coordinates</span>
          <span className="font-mono text-xs">{module.language === "typescript" ? `${p.artifactId}@${p.version || "—"}` : `${p.groupId}:${p.artifactId}:${p.version}`}</span>
          {p.inceptionYear && (
            <>
              <span className="text-muted-foreground">Since</span>
              <span>{p.inceptionYear}</span>
            </>
          )}
          {p.organizationName && (
            <>
              <span className="text-muted-foreground">Organization</span>
              <span>{p.organizationName}</span>
            </>
          )}
          {p.licenses.map((l) => (
            <span key={l.name} className="contents">
              <span className="text-muted-foreground">License</span>
              {l.url ? (
                <a href={l.url} target="_blank" rel="noreferrer" className="text-primary underline-offset-4 hover:underline">{l.name}</a>
              ) : (
                <span>{l.name}</span>
              )}
            </span>
          ))}
        </CardContent>
      </Card>
      {p.developers.length > 0 && (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Developers</CardTitle>
          </CardHeader>
          <CardContent className="flex flex-col gap-3 text-sm">
            {p.developers.map((d) => (
              <div key={d.id ?? d.name} className="flex flex-col">
                <span className="font-medium">{d.name ?? d.id}</span>
                <span className="text-xs text-muted-foreground">
                  {[d.roles.join(", "), d.organization].filter(Boolean).join(" · ")}
                </span>
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </>
  )
}
