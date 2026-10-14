import { useMemo, useState } from "react"

import { KindIcon, TypeIcon, iconKind } from "@/components/docs/type-icon"
import { Badge } from "@/components/ui/badge"
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { useDocs } from "@/lib/docs/context"
import { KIND_LABELS, KIND_ORDER, coverage, displayName, formatNumber, memberId, moduleName, routes } from "@/lib/docs/model"
import type { ModuleDocumentation, TypeKind } from "@/lib/docs/types"

interface Lines {
  total: number
  code: number
  comment: number
  blank: number
}

function linesOf(module: ModuleDocumentation): Lines {
  const s = module.statistics
  return { total: s.totalLines ?? 0, code: s.codeLines ?? s.linesOfCode, comment: s.commentLines ?? 0, blank: s.blankLines ?? 0 }
}

export function StatsPage() {
  const index = useDocs()
  const modules = index.site.modules
  const withCode = modules.filter((m) => linesOf(m).total > 0)

  const totals = useMemo(() => {
    const sum = { files: 0, types: 0, methods: 0, documented: 0, documentable: 0, total: 0, code: 0, comment: 0, blank: 0 }
    for (const m of modules) {
      const l = linesOf(m)
      sum.files += m.statistics.files
      sum.types += m.statistics.types
      sum.methods += m.statistics.methods
      sum.documented += m.statistics.documented
      sum.documentable += m.statistics.documentable
      sum.total += l.total
      sum.code += l.code
      sum.comment += l.comment
      sum.blank += l.blank
    }
    return sum
  }, [modules])

  const methods = useMemo(() => {
    const all = [...index.types.values()].flatMap((t) => t.type.methods.map((m) => ({ type: t, method: m })))
    return all
  }, [index])
  const complexities = methods.map((m) => m.method.cyclomaticComplexity)
  const average = complexities.length ? complexities.reduce((a, b) => a + b, 0) / complexities.length : 0

  const kinds = useMemo(() => {
    const counts = new Map<TypeKind, number>()
    for (const t of index.types.values()) counts.set(t.type.kind, (counts.get(t.type.kind) ?? 0) + 1)
    return KIND_ORDER.map((kind) => ({ kind, count: counts.get(kind) ?? 0 })).filter((k) => k.count > 0)
  }, [index])

  const commentRatio = totals.code + totals.comment === 0 ? 0 : Math.round((totals.comment / (totals.code + totals.comment)) * 100)

  return (
    <div className="flex flex-col gap-10">
      <header className="flex flex-col gap-3">
        <Badge variant="secondary" className="w-fit">Statistics</Badge>
        <h1 className="font-heading text-4xl font-bold tracking-tight">Codebase at a glance</h1>
        <p className="max-w-3xl font-serif text-lg leading-8">
          Line counts cover every main source file. Type, member and documentation figures cover what this site documents
          ({index.site.visibility} and wider).
        </p>
      </header>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Lines of code" value={formatNumber(totals.code)} hint={`${formatNumber(totals.total)} lines in all`} />
        <Stat label="Comment lines" value={formatNumber(totals.comment)} hint={`${commentRatio}% of non-blank lines`} />
        <Stat label="Files" value={formatNumber(totals.files)} />
        <Stat label="Types" value={formatNumber(totals.types)} hint={`${formatNumber(totals.methods)} methods`} />
        <Stat label="Avg. complexity" value={average.toFixed(1)} hint={`max ${Math.max(0, ...complexities)}`} />
        <Stat label="Documented" value={coverage(totals.documented, totals.documentable)} hint={`${formatNumber(totals.documented)} of ${formatNumber(totals.documentable)}`} />
      </div>

      {withCode.length > 0 && <LinesByModule modules={withCode} />}

      <section className="flex flex-col gap-4">
        <SectionTitle title="Types by kind" />
        <div className="flex max-w-2xl flex-col gap-2">
          {kinds.map(({ kind, count }) => {
            const max = Math.max(...kinds.map((k) => k.count))
            return (
              <Tooltip key={kind}>
                <TooltipTrigger render={<div className="grid grid-cols-[9rem_1fr_3rem] items-center gap-3 text-sm" />}>
                  <span className="flex items-center gap-2">
                    <KindIcon kind={iconKind(kind)} />
                    {KIND_LABELS[kind].plural}
                  </span>
                  <span className="h-3 rounded-r-[4px] bg-viz-code" style={{ width: `${(count / max) * 100}%` }} />
                  <span className="text-right tabular-nums text-muted-foreground">{formatNumber(count)}</span>
                </TooltipTrigger>
                <TooltipContent>
                  {formatNumber(count)} {count === 1 ? KIND_LABELS[kind].singular.toLowerCase() : KIND_LABELS[kind].plural.toLowerCase()}
                </TooltipContent>
              </Tooltip>
            )
          })}
        </div>
      </section>

      <section className="flex flex-col gap-4">
        <SectionTitle title="Most complex methods" note="Cyclomatic complexity: 1 + decision points. Above 10 is worth a look." />
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Method</TableHead>
              <TableHead>Type</TableHead>
              <TableHead className="text-right">Complexity</TableHead>
              <TableHead className="text-right">Lines</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {[...methods].sort((a, b) => b.method.cyclomaticComplexity - a.method.cyclomaticComplexity).slice(0, 10).map(({ type, method }) => (
              <TableRow key={type.type.qualifiedName + memberId(method)}>
                <TableCell className="font-mono text-xs">
                  <a href={routes.type(type.type.qualifiedName, memberId(method))} className="text-primary underline-offset-4 hover:underline">
                    {method.name}()
                  </a>
                </TableCell>
                <TableCell>
                  <span className="flex items-center gap-2 text-sm">
                    <TypeIcon type={type.type} />
                    {displayName(type)}
                  </span>
                </TableCell>
                <TableCell className="text-right tabular-nums">
                  {method.cyclomaticComplexity > 10 ? <Badge variant="destructive">{method.cyclomaticComplexity}</Badge> : method.cyclomaticComplexity}
                </TableCell>
                <TableCell className="text-right tabular-nums">{method.lineCount}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </section>

      <section className="flex flex-col gap-4">
        <SectionTitle title="Largest types" note="By source lines of the declaration, nested types included." />
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Type</TableHead>
              <TableHead>Module</TableHead>
              <TableHead className="text-right">Lines</TableHead>
              <TableHead className="text-right">Methods</TableHead>
              <TableHead className="text-right">Fields</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {[...index.types.values()].filter((t) => !t.outer)
              .sort((a, b) => b.type.endLine - b.type.startLine - (a.type.endLine - a.type.startLine)).slice(0, 10)
              .map((t) => (
                <TableRow key={t.type.qualifiedName}>
                  <TableCell>
                    <a href={routes.type(t.type.qualifiedName)} className="flex items-center gap-2 text-sm text-primary underline-offset-4 hover:underline">
                      <TypeIcon type={t.type} />
                      {t.type.name}
                    </a>
                  </TableCell>
                  <TableCell className="text-sm text-muted-foreground">{moduleName(t.module)}</TableCell>
                  <TableCell className="text-right tabular-nums">{formatNumber(t.type.endLine - t.type.startLine + 1)}</TableCell>
                  <TableCell className="text-right tabular-nums">{t.type.methods.length}</TableCell>
                  <TableCell className="text-right tabular-nums">{t.type.fields.length}</TableCell>
                </TableRow>
              ))}
          </TableBody>
        </Table>
      </section>
    </div>
  )
}

function SectionTitle({ title, note }: { title: string; note?: string }) {
  return (
    <div className="flex flex-col gap-1">
      <h2 className="font-heading text-2xl font-bold tracking-tight">{title}</h2>
      {note && <p className="text-sm text-muted-foreground">{note}</p>}
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <Card size="sm">
      <CardHeader>
        <CardDescription>{label}</CardDescription>
        <CardTitle className="text-2xl tabular-nums">{value}</CardTitle>
        {hint && <CardDescription className="text-xs">{hint}</CardDescription>}
      </CardHeader>
    </Card>
  )
}

/**
 * Code and comment lines per module as one stacked bar each, sharing one scale (blank lines are
 * the unfilled remainder). Legend above, value direct-labeled at the bar end, table view on demand.
 */
function LinesByModule({ modules }: { modules: ModuleDocumentation[] }) {
  const [mode, setMode] = useState<"chart" | "table">("chart")
  const rows = modules.map((m) => ({ module: m, lines: linesOf(m) })).sort((a, b) => b.lines.code - a.lines.code)
  const max = Math.max(...rows.map((r) => r.lines.code + r.lines.comment))

  return (
    <section className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <SectionTitle title="Lines by module" />
        <ToggleGroup value={[mode]} onValueChange={(v) => v[0] && setMode(v[0] as "chart" | "table")} variant="outline" size="sm">
          <ToggleGroupItem value="chart">Chart</ToggleGroupItem>
          <ToggleGroupItem value="table">Table</ToggleGroupItem>
        </ToggleGroup>
      </div>

      {mode === "chart" ? (
        <>
          <div className="flex gap-4 text-xs text-muted-foreground" aria-hidden>
            <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-[2px] bg-viz-code" />Code</span>
            <span className="flex items-center gap-1.5"><span className="size-2.5 rounded-[2px] bg-viz-comment" />Comments</span>
          </div>
          <div className="flex flex-col gap-2" role="list" aria-label="Lines by module">
            {rows.map(({ module, lines }) => (
              <Tooltip key={module.id}>
                <TooltipTrigger
                  render={<a href={routes.module(module.id)} role="listitem" className="grid grid-cols-[minmax(8rem,14rem)_1fr_4.5rem] items-center gap-3 rounded-sm py-1 text-sm hover:bg-muted/60" />}
                >
                  <span className="truncate">{moduleName(module)}</span>
                  <span className="flex h-3.5 gap-[2px]">
                    <span className="rounded-l-[2px] bg-viz-code" style={{ width: `${(lines.code / max) * 100}%` }} />
                    {lines.comment > 0 && (
                      <span className="rounded-r-[4px] bg-viz-comment" style={{ width: `${(lines.comment / max) * 100}%` }} />
                    )}
                  </span>
                  <span className="text-right tabular-nums text-muted-foreground">{formatNumber(lines.code)}</span>
                </TooltipTrigger>
                <TooltipContent>
                  <div className="flex flex-col gap-0.5">
                    <span className="font-medium">{moduleName(module)}</span>
                    <span>{formatNumber(lines.code)} code · {formatNumber(lines.comment)} comment · {formatNumber(lines.blank)} blank</span>
                  </div>
                </TooltipContent>
              </Tooltip>
            ))}
          </div>
        </>
      ) : (
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Module</TableHead>
              <TableHead className="text-right">Code</TableHead>
              <TableHead className="text-right">Comments</TableHead>
              <TableHead className="text-right">Blank</TableHead>
              <TableHead className="text-right">Total</TableHead>
              <TableHead className="text-right">Avg. complexity</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map(({ module, lines }) => (
              <TableRow key={module.id}>
                <TableCell>{moduleName(module)}</TableCell>
                <TableCell className="text-right tabular-nums">{formatNumber(lines.code)}</TableCell>
                <TableCell className="text-right tabular-nums">{formatNumber(lines.comment)}</TableCell>
                <TableCell className="text-right tabular-nums">{formatNumber(lines.blank)}</TableCell>
                <TableCell className="text-right tabular-nums">{formatNumber(lines.total)}</TableCell>
                <TableCell className="text-right tabular-nums">{module.statistics.averageComplexity?.toFixed(1) ?? "—"}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </section>
  )
}
