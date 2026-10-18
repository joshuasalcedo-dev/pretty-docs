import { CircleAlertIcon, CircleCheckIcon, SearchIcon } from "lucide-react"
import { useMemo, useState } from "react"

import { TypeNameLink } from "@/components/docs/type-ref"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Empty, EmptyDescription, EmptyHeader, EmptyTitle } from "@/components/ui/empty"
import { InputGroup, InputGroupAddon, InputGroupInput } from "@/components/ui/input-group"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useDocs } from "@/lib/docs/context"
import { formatNumber } from "@/lib/docs/model"
import type { QualityFinding, QualityPriority } from "@/lib/docs/types"

const PRIORITIES: QualityPriority[] = ["HIGH", "MEDIUM", "LOW"]
const PRIORITY_VARIANT: Record<QualityPriority, "destructive" | "secondary" | "outline"> = {
  HIGH: "destructive",
  MEDIUM: "secondary",
  LOW: "outline",
}
const PAGE = 200

export function QualityPage() {
  const { site } = useDocs()
  const quality = site.quality
  const tools = quality?.tools ?? []
  const findings = useMemo(() => quality?.findings ?? [], [quality])
  const [priorities, setPriorities] = useState<string[]>(["HIGH", "MEDIUM"])
  const [shownTools, setShownTools] = useState<string[]>(() => tools.filter((t) => t.ran).map((t) => t.name))
  const [rule, setRule] = useState<string | null>(null)
  const [query, setQuery] = useState("")
  const [limit, setLimit] = useState(PAGE)

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return findings.filter((f) => priorities.includes(f.priority) && shownTools.includes(f.tool) && (!rule || f.rule === rule)
      && (!q || `${f.rule} ${f.category} ${f.message} ${f.file ?? ""} ${f.typeName ?? ""}`.toLowerCase().includes(q)))
  }, [findings, priorities, shownTools, rule, query])

  const topRules = useMemo(() => {
    const counts = new Map<string, { tool: string; count: number }>()
    for (const f of findings.filter((f) => priorities.includes(f.priority) && shownTools.includes(f.tool))) {
      counts.set(f.rule, { tool: f.tool, count: (counts.get(f.rule)?.count ?? 0) + 1 })
    }
    return [...counts.entries()].sort((a, b) => b[1].count - a[1].count).slice(0, 12)
  }, [findings, priorities, shownTools])

  if (!quality) {
    return (
      <Empty>
        <EmptyHeader>
          <EmptyTitle>No quality checks</EmptyTitle>
          <EmptyDescription>Generate with -Dpretty-docs.quality=true to include PMD, SpotBugs and TypeScript findings.</EmptyDescription>
        </EmptyHeader>
      </Empty>
    )
  }

  const count = (p: QualityPriority) => findings.filter((f) => f.priority === p).length

  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <Badge variant="secondary" className="w-fit">Quality</Badge>
        <h1 className="font-heading text-4xl font-bold tracking-tight">Code quality</h1>
        <p className="max-w-3xl font-serif text-lg leading-8">
          Likely bugs and risky code found by PMD (Java sources), SpotBugs (the classes of the last build) and the
          TypeScript compiler. Each finding links to the rule's documentation and to the code it is about.
        </p>
      </header>

      <div className="grid gap-3 sm:grid-cols-3">
        {tools.map((tool) => (
          <Card key={tool.name} size="sm">
            <CardHeader>
              <CardDescription className="flex items-center gap-1.5">
                {tool.ran ? <CircleCheckIcon className="size-3.5" /> : <CircleAlertIcon className="size-3.5" />}
                {tool.name}{tool.version ? ` ${tool.version}` : ""}
              </CardDescription>
              <CardTitle className="text-2xl tabular-nums">{tool.ran ? formatNumber(tool.findings) : "—"}</CardTitle>
              <p className="text-xs text-muted-foreground">{tool.ran ? tool.note : `Skipped: ${tool.note}`}</p>
            </CardHeader>
          </Card>
        ))}
      </div>

      <section className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-3">
          <ToggleGroup multiple value={priorities} onValueChange={(v) => setPriorities(v as string[])} variant="outline" size="sm">
            {PRIORITIES.map((p) => (
              <ToggleGroupItem key={p} value={p}>
                {p.charAt(0) + p.slice(1).toLowerCase()} ({count(p)})
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          <ToggleGroup multiple value={shownTools} onValueChange={(v) => setShownTools(v as string[])} variant="outline" size="sm">
            {tools.filter((t) => t.ran).map((t) => (
              <ToggleGroupItem key={t.name} value={t.name}>{t.name}</ToggleGroupItem>
            ))}
          </ToggleGroup>
          <InputGroup className="max-w-xs">
            <InputGroupInput placeholder="Filter findings…" value={query} onChange={(e) => setQuery(e.target.value)} />
            <InputGroupAddon>
              <SearchIcon />
            </InputGroupAddon>
          </InputGroup>
        </div>

        {topRules.length > 0 && (
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-xs text-muted-foreground">Most frequent:</span>
            {topRules.map(([name, { count }]) => (
              <Badge
                key={name}
                variant={rule === name ? "default" : "outline"}
                render={<button type="button" onClick={() => setRule(rule === name ? null : name)} />}
                className="font-mono"
              >
                {name} · {count}
              </Badge>
            ))}
          </div>
        )}

        {visible.length === 0 ? (
          <p className="text-sm text-muted-foreground">No findings match.</p>
        ) : (
          <FindingsTable findings={visible.slice(0, limit)} />
        )}
        {visible.length > limit && (
          <Button variant="outline" size="sm" className="w-fit" onClick={() => setLimit(limit + PAGE)}>
            Show more ({formatNumber(visible.length - limit)} left)
          </Button>
        )}
      </section>
    </div>
  )
}

/** Findings as a table; also used on type pages (without the location column's type). */
export function FindingsTable({ findings, showType = true }: { findings: QualityFinding[]; showType?: boolean }) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead className="w-24">Priority</TableHead>
          <TableHead>Rule</TableHead>
          <TableHead>Finding</TableHead>
          <TableHead>Where</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {findings.map((f, i) => (
          <TableRow key={`${f.tool}:${f.rule}:${f.file}:${f.line}:${i}`}>
            <TableCell className="align-top">
              <Badge variant={PRIORITY_VARIANT[f.priority]}>{f.priority.toLowerCase()}</Badge>
            </TableCell>
            <TableCell className="w-52 min-w-44 align-top whitespace-normal">
              <div className="font-mono text-xs [overflow-wrap:anywhere]">
                {f.url ? (
                  <a href={f.url} target="_blank" rel="noreferrer" className="text-primary underline-offset-4 hover:underline">{f.rule}</a>
                ) : f.rule}
              </div>
              <div className="text-xs text-muted-foreground">{f.tool} · {f.category}</div>
            </TableCell>
            <TableCell className="max-w-xl align-top text-sm whitespace-normal [overflow-wrap:anywhere]">{f.message}</TableCell>
            <TableCell className="w-56 min-w-36 align-top text-xs whitespace-normal [overflow-wrap:anywhere]">
              {showType && f.typeName && (
                <div>
                  <TypeNameLink name={f.typeName.split(".").pop()!} qualifiedName={f.typeName} />
                  {f.memberName && <span className="font-mono text-muted-foreground">.{f.memberName}</span>}
                </div>
              )}
              {!showType && f.memberName && <div className="font-mono">{f.memberName}</div>}
              <div className="font-mono text-muted-foreground">
                {f.file?.split("/").pop()}{f.line > 0 ? `:${f.line}` : ""}
              </div>
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  )
}

/** The findings about a type (and its nested types), for its page. */
export function useTypeFindings(qualifiedName: string): QualityFinding[] {
  const { site } = useDocs()
  return useMemo(
    () => site.quality?.findings.filter((f) => f.typeName === qualifiedName || f.typeName?.startsWith(`${qualifiedName}.`)) ?? [],
    [site, qualifiedName],
  )
}
