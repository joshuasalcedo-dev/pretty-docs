import { AlertTriangleIcon, CircleAlertIcon, InfoIcon, SearchIcon } from "lucide-react"
import { useMemo, useState } from "react"

import { DiagramView, diagramsFor } from "@/components/docs/diagram-view"
import { TypeNameLink } from "@/components/docs/type-ref"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useDocs } from "@/lib/docs/context"
import { routes } from "@/lib/docs/model"
import { scrollToElement } from "@/lib/scroll"
import type { BeanDefinition, BeanIssue, BeanKind, SpringStructure } from "@/lib/docs/types"

const KIND_LABEL: Record<BeanKind, string> = {
  COMPONENT: "Component",
  SERVICE: "Service",
  REPOSITORY: "Repository",
  CONTROLLER: "Controller",
  REST_CONTROLLER: "REST controller",
  CONTROLLER_ADVICE: "Controller advice",
  CONFIGURATION: "Configuration",
  BEAN_METHOD: "@Bean",
  DATA_REPOSITORY: "Spring Data",
  CONFIGURATION_PROPERTIES: "Properties",
}

const TABS = ["issues", "beans", "graph", "endpoints", "configuration"] as const

export function SpringPage({ tab }: { tab?: string }) {
  const { site } = useDocs()
  const spring = site.spring
  if (!spring) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>No Spring usage found</EmptyTitle>
          <EmptyDescription>This project does not declare Spring beans.</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }
  const graph = diagramsFor(site, "SPRING_BEANS")[0]
  const errors = spring.issues.filter((i) => i.severity === "ERROR").length
  const warnings = spring.issues.filter((i) => i.severity === "WARNING").length
  const active = TABS.includes(tab as (typeof TABS)[number]) ? tab! : errors + warnings > 0 ? "issues" : "beans"

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <Badge variant="secondary" className="w-fit">Spring</Badge>
        <h1 className="font-heading text-4xl font-bold tracking-tight">Application wiring</h1>
        <p className="max-w-3xl font-serif text-lg leading-8">
          Beans, dependencies and endpoints found by static analysis of the sources. Auto-configuration, conditions and
          profiles are not evaluated, so dependencies on library types are listed as information.
        </p>
        {spring.applications.map((app) => (
          <p key={app.typeName} className="text-sm">
            <span className="text-muted-foreground">Application </span>
            <TypeNameLink name={app.typeName.split(".").pop()!} qualifiedName={app.typeName} />
            <span className="text-muted-foreground"> scans </span>
            <code className="font-mono text-xs">{app.scanPackages.join(", ")}</code>
          </p>
        ))}
      </header>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Beans" value={spring.beans.length} />
        <Stat label="Endpoints" value={spring.endpoints.length} />
        <Stat label="Errors" value={errors} />
        <Stat label="Warnings" value={warnings} />
      </div>

      <Tabs value={active} onValueChange={(value) => (window.location.hash = routes.spring(String(value)))}>
        <TabsList variant="pill">
          <TabsTrigger value="issues">Issues ({spring.issues.length})</TabsTrigger>
          <TabsTrigger value="beans">Beans</TabsTrigger>
          {graph && <TabsTrigger value="graph">Graph</TabsTrigger>}
          <TabsTrigger value="endpoints">Endpoints</TabsTrigger>
          <TabsTrigger value="configuration">Configuration</TabsTrigger>
        </TabsList>
        <TabsContent value="issues" className="pt-4">
          <Issues issues={spring.issues} />
        </TabsContent>
        <TabsContent value="beans" className="pt-4">
          <Beans spring={spring} />
        </TabsContent>
        {graph && (
          <TabsContent value="graph" className="pt-4">
            <DiagramView diagram={graph} />
          </TabsContent>
        )}
        <TabsContent value="endpoints" className="pt-4">
          <Endpoints spring={spring} />
        </TabsContent>
        <TabsContent value="configuration" className="pt-4">
          <Configuration spring={spring} />
        </TabsContent>
      </Tabs>
    </div>
  )
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums">{value}</CardTitle>
      </CardHeader>
    </Card>
  )
}

const SEVERITY = {
  ERROR: { icon: CircleAlertIcon, title: "Error", variant: "destructive" as const },
  WARNING: { icon: AlertTriangleIcon, title: "Warning", variant: "default" as const },
  INFO: { icon: InfoIcon, title: "Note", variant: "default" as const },
}

function Issues({ issues }: { issues: BeanIssue[] }) {
  const [shown, setShown] = useState<string[]>(["ERROR", "WARNING"])
  if (issues.length === 0) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>No wiring problems found</EmptyTitle>
          <EmptyDescription>Every injection point resolves to exactly one bean.</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }
  const visible = issues.filter((i) => shown.includes(i.severity))
  return (
    <div className="flex flex-col gap-3">
      <ToggleGroup multiple value={shown} onValueChange={(value) => setShown(value as string[])} variant="outline" size="sm">
        {(["ERROR", "WARNING", "INFO"] as const).map((s) => (
          <ToggleGroupItem key={s} value={s}>
            {SEVERITY[s].title}s ({issues.filter((i) => i.severity === s).length})
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
      {visible.map((issue, i) => {
        const severity = SEVERITY[issue.severity]
        return (
          <Alert key={i} variant={severity.variant}>
            <severity.icon />
            <AlertTitle className="flex flex-wrap items-center gap-2">
              {severity.title}
              <Badge variant="outline" className="font-mono">{issue.kind.toLowerCase().replaceAll("_", " ")}</Badge>
            </AlertTitle>
            <AlertDescription>
              <p>{issue.message}</p>
              {issue.typeName && (
                <p className="text-xs">
                  See <TypeNameLink name={issue.typeName.split(".").pop()!} qualifiedName={issue.typeName} />
                </p>
              )}
            </AlertDescription>
          </Alert>
        )
      })}
    </div>
  )
}

function Beans({ spring }: { spring: SpringStructure }) {
  const [query, setQuery] = useState("")
  const beans = useMemo(() => {
    const q = query.trim().toLowerCase()
    return q ? spring.beans.filter((b) => `${b.name} ${b.typeName} ${b.kind}`.toLowerCase().includes(q)) : spring.beans
  }, [spring, query])

  return (
    <div className="flex flex-col gap-4">
      <InputGroup className="max-w-sm">
        <InputGroupInput placeholder="Filter beans…" value={query} onChange={(e) => setQuery(e.target.value)} />
        <InputGroupAddon>
          <SearchIcon />
        </InputGroupAddon>
      </InputGroup>
      <div className="grid gap-3 lg:grid-cols-2">
        {beans.map((bean) => <BeanCard key={bean.name + bean.declaringType} bean={bean} />)}
      </div>
    </div>
  )
}

/** One bean: what it is, what it needs and who needs it. Also used on type pages. */
export function BeanCard({ bean }: { bean: BeanDefinition }) {
  const index = useDocs()
  const simple = (name: string) => name.split(".").pop()!
  return (
    <div id={`bean-${bean.name}`} className="flex flex-col gap-3 rounded-xl border p-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono font-semibold">{bean.name}</span>
        <Badge variant="secondary">{KIND_LABEL[bean.kind]}</Badge>
        {bean.scope !== "singleton" && <Badge variant="outline">{bean.scope}</Badge>}
        {bean.primary && <Badge variant="outline">primary</Badge>}
        {bean.lazy && <Badge variant="outline">lazy</Badge>}
        {bean.profiles.map((p) => <Badge key={p} variant="outline">profile: {p}</Badge>)}
        {!bean.inComponentScan && <Badge variant="destructive">not scanned</Badge>}
      </div>
      <p className="text-muted-foreground">
        <TypeNameLink name={simple(bean.typeName)} qualifiedName={bean.typeName} />
        {bean.declaringMember && (
          <>
            {" from "}
            <TypeNameLink name={simple(bean.declaringType)} qualifiedName={bean.declaringType} />
            <code className="font-mono">.{bean.declaringMember}()</code>
          </>
        )}
      </p>
      {bean.conditions.length > 0 && (
        <p className="font-mono text-xs text-muted-foreground">{bean.conditions.join(" ")}</p>
      )}
      {bean.dependencies.length > 0 && (
        <div className="flex flex-col gap-1">
          <p className="text-xs font-medium text-muted-foreground">Injects</p>
          {bean.dependencies.map((d) => (
            <p key={d.kind + d.name} className="flex flex-wrap items-baseline gap-x-2 font-mono text-xs">
              <span>{d.name}</span>
              <span className="text-muted-foreground">
                : {d.collection ? "many " : ""}
                <TypeNameLink name={simple(d.typeName)} qualifiedName={d.typeName} />
              </span>
              <span className="text-muted-foreground">→</span>
              {d.resolvedTo ? (
                <a href={`${routes.spring("beans")}`} onClick={() => setTimeout(() => scrollToElement(document.getElementById(`bean-${d.resolvedTo}`)))} className="text-primary underline-offset-4 hover:underline">
                  {d.resolvedTo}
                </a>
              ) : d.collection ? (
                <span>{d.candidates.length} bean(s)</span>
              ) : d.candidates.length > 1 ? (
                <Badge variant="destructive">ambiguous ({d.candidates.length})</Badge>
              ) : (
                !d.required ? (
                <span className="text-muted-foreground italic">optional</span>
              ) : index.types.has(d.typeName) ? (
                <Badge variant="destructive">missing bean</Badge>
              ) : (
                <span className="text-muted-foreground italic">from a library / auto-configuration</span>
              )
              )}
              <span className="text-[10px] text-muted-foreground uppercase">{d.kind.toLowerCase().replaceAll("_", " ")}</span>
            </p>
          ))}
        </div>
      )}
      {bean.injectedInto.length > 0 && (
        <p className="text-xs text-muted-foreground">Injected into {bean.injectedInto.join(", ")}</p>
      )}
    </div>
  )
}

const METHOD_VARIANT: Record<string, "default" | "secondary" | "outline" | "destructive"> = {
  GET: "secondary",
  POST: "default",
  PUT: "outline",
  PATCH: "outline",
  DELETE: "destructive",
}

function Endpoints({ spring }: { spring: SpringStructure }) {
  if (spring.endpoints.length === 0) return <p className="text-sm text-muted-foreground">No request mappings found.</p>
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-28">Method</TableHead>
          <TableHead>Path</TableHead>
          <TableHead>Handler</TableHead>
          <TableHead>Parameters</TableHead>
          <TableHead>Returns</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {spring.endpoints.map((e, i) => (
          <TableRow key={i}>
            <TableCell className="flex flex-wrap gap-1">
              {(e.httpMethods.length ? e.httpMethods : ["ANY"]).map((m) => (
                <Badge key={m} variant={METHOD_VARIANT[m] ?? "outline"} className="font-mono">{m}</Badge>
              ))}
            </TableCell>
            <TableCell className="font-mono text-sm">{e.path}</TableCell>
            <TableCell className="font-mono text-xs">
              <a href={routes.type(e.controllerType, e.methodId)} className="text-primary underline-offset-4 hover:underline">
                {e.controllerType.split(".").pop()}.{e.methodName}
              </a>
            </TableCell>
            <TableCell className="font-mono text-xs whitespace-normal">
              {e.parameters.map((p) => (
                <div key={p.source + p.name}>
                  <span className="text-muted-foreground">{p.source} </span>
                  {p.name}
                  {!p.required && "?"}
                  <span className="text-muted-foreground">: {p.typeName}</span>
                  {p.defaultValue !== undefined && <span className="text-muted-foreground"> = {p.defaultValue}</span>}
                </div>
              ))}
              {e.consumes.length > 0 && <div className="text-muted-foreground">consumes {e.consumes.join(", ")}</div>}
            </TableCell>
            <TableCell className="font-mono text-xs">{e.returnType}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

function Configuration({ spring }: { spring: SpringStructure }) {
  return (
    <div className="flex flex-col gap-6">
      {spring.configurationProperties.map((group) => (
        <section key={group.typeName + group.prefix} className="flex flex-col gap-2">
          <h2 className="flex flex-wrap items-center gap-2 text-sm font-medium">
            <code className="font-mono">{group.prefix}</code>
            <span className="text-muted-foreground">bound to</span>
            <TypeNameLink name={group.typeName.split(".").pop()!} qualifiedName={group.typeName} />
            {!group.registered && <Badge variant="destructive">not registered</Badge>}
          </h2>
          <PropertyTable rows={group.properties.map((p) => ({ key: p.key, type: p.typeName, value: p.defaultValue, note: p.description }))} />
        </section>
      ))}
      {spring.propertyUsages.length > 0 && (
        <section className="flex flex-col gap-2">
          <h2 className="text-sm font-medium">@Value placeholders</h2>
          <PropertyTable
            rows={spring.propertyUsages.map((u) => ({
              key: u.key,
              type: `${u.typeName.split(".").pop()}${u.memberName ? `.${u.memberName}` : ""}`,
              value: u.defaultValue,
            }))}
            typeLabel="Used in"
          />
        </section>
      )}
      {spring.entities.length > 0 && (
        <section className="flex flex-col gap-2">
          <h2 className="text-sm font-medium">JPA entities</h2>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Entity</TableHead>
                <TableHead>Table</TableHead>
                <TableHead>Id</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {spring.entities.map((e) => (
                <TableRow key={e.typeName}>
                  <TableCell><TypeNameLink name={e.entityName} qualifiedName={e.typeName} /></TableCell>
                  <TableCell className="font-mono text-xs">{e.tableName ?? <span className="text-muted-foreground">default</span>}</TableCell>
                  <TableCell className="font-mono text-xs">{e.idField ?? "—"}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </section>
      )}
      {spring.configurationProperties.length === 0 && spring.propertyUsages.length === 0 && spring.entities.length === 0 && (
        <p className="text-sm text-muted-foreground">No configuration properties, @Value placeholders or entities found.</p>
      )}
    </div>
  )
}

function PropertyTable({ rows, typeLabel = "Type" }: { rows: { key: string; type: string; value?: string; note?: string }[]; typeLabel?: string }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Key</TableHead>
          <TableHead>{typeLabel}</TableHead>
          <TableHead>Default</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((r) => (
          <TableRow key={r.key + r.type}>
            <TableCell className="align-top font-mono text-xs">
              {r.key}
              {r.note && <div className="mt-1 font-sans whitespace-normal text-muted-foreground">{r.note}</div>}
            </TableCell>
            <TableCell className="align-top font-mono text-xs">{r.type}</TableCell>
            <TableCell className="align-top font-mono text-xs">{r.value ?? <span className="text-muted-foreground">—</span>}</TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}
