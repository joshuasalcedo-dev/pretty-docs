import { Badge } from "@/components/ui/badge"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { DiagramView, diagramsFor } from "@/components/docs/diagram-view"
import { JavadocText } from "@/components/docs/javadoc-text"
import { TypeIcon } from "@/components/docs/type-icon"
import { useDocs } from "@/lib/docs/context"
import { KIND_LABELS, KIND_ORDER, isDeprecated, kindLabel, routes, summary, type PackageInfo } from "@/lib/docs/model"

export function PackagePage({ pkg }: { pkg: PackageInfo }) {
  const { site } = useDocs()
  const diagram = diagramsFor(site, "PACKAGE_CLASSES", pkg.module.id, pkg.name)[0]
  return (
    <div className="flex flex-col gap-8">
      <header className="flex flex-col gap-3">
        <Badge variant="secondary" className="w-fit">Package</Badge>
        <h1 className="font-heading font-mono text-3xl font-bold tracking-tight break-all">
          {pkg.name || "(default package)"}
        </h1>
        {pkg.description && <JavadocText text={pkg.description} packageName={pkg.name} className="max-w-3xl" />}
      </header>

      {KIND_ORDER.map((kind) => {
        const types = pkg.types.filter((t) => t.type.kind === kind)
        if (types.length === 0) return null
        return (
          <section key={kind} className="flex flex-col gap-2">
            <h2 className="font-heading text-lg font-semibold">{KIND_LABELS[kind].plural}</h2>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-1/3">Name</TableHead>
                  <TableHead>Description</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {types.map(({ type }) => (
                  <TableRow key={type.qualifiedName}>
                    <TableCell className="align-top">
                      <a href={routes.type(type.qualifiedName)} className="inline-flex items-center gap-2 font-mono text-sm font-medium text-primary underline-offset-4 hover:underline">
                        <TypeIcon type={type} />
                        {type.name}
                      </a>
                      <div className="mt-1 flex flex-wrap gap-1">
                        {kindLabel(type) !== KIND_LABELS[kind].singular && <Badge variant="outline">{kindLabel(type)}</Badge>}
                        {isDeprecated(type) && <Badge variant="destructive">Deprecated</Badge>}
                      </div>
                    </TableCell>
                    <TableCell className="text-sm whitespace-normal text-muted-foreground">
                      {summary(type.javadoc) || <span className="italic">No description.</span>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </section>
        )
      })}

      {diagram && (
        <section className="flex flex-col gap-2">
          <h2 className="font-heading text-lg font-semibold">Class diagram</h2>
          <DiagramView diagram={diagram} caption={false} />
        </section>
      )}
    </div>
  )
}
