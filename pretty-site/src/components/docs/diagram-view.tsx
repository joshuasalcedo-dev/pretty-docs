import { DownloadIcon, ExternalLinkIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import type { DiagramStructure, DocumentationSite } from "@/lib/docs/types"

/** Diagrams of a kind, optionally narrowed to a module or a package. */
export function diagramsFor(site: DocumentationSite, kind: DiagramStructure["kind"], moduleId?: string, packageName?: string) {
  return (site.diagrams ?? []).filter(
    (d) => d.kind === kind && (moduleId === undefined || d.moduleId === moduleId) && (packageName === undefined || d.packageName === packageName)
  )
}

/**
 * A rendered PlantUML diagram (SVG next to the site, so it also works from file://) with links to
 * the SVG and the .puml source. Diagrams are drawn for a light background, so they sit on white.
 */
export function DiagramView({ diagram, caption = true }: { diagram: DiagramStructure; caption?: boolean }) {
  return (
    <figure className="flex flex-col gap-2">
      {diagram.svgPath ? (
        <div className="max-h-[70vh] overflow-auto rounded-md border bg-white p-4">
          <img src={diagram.svgPath} alt={`${diagram.title} diagram`} loading="lazy" className="max-w-none" />
        </div>
      ) : (
        <p className="rounded-md border border-dashed p-4 text-sm text-muted-foreground">
          This diagram was not rendered. Its PlantUML source is available below.
        </p>
      )}
      <figcaption className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        {caption && (
          <span>
            {diagram.title} · {diagram.elementCount} {diagram.kind === "PACKAGE_CLASSES" ? "types" : diagram.kind === "SPRING_BEANS" ? "beans" : "modules"}
          </span>
        )}
        <span className="ml-auto flex gap-1">
          {diagram.svgPath && (
            <Button variant="ghost" size="xs" render={<a href={diagram.svgPath} target="_blank" rel="noreferrer" />}>
              <ExternalLinkIcon data-icon="inline-start" />
              Open SVG
            </Button>
          )}
          <Button variant="ghost" size="xs" render={<a href={diagram.pumlPath} download />}>
            <DownloadIcon data-icon="inline-start" />
            PlantUML
          </Button>
        </span>
      </figcaption>
    </figure>
  )
}
