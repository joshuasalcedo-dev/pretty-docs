import { useEffect, useMemo, useRef, type MouseEvent } from "react"

import { useDocs } from "@/lib/docs/context"
import { javadocToHtml, readmeToHtml } from "@/lib/docs/javadoc"
import { scrollToElement } from "@/lib/scroll"
import { highlightBlocks, useHighlighter } from "@/lib/highlight"
import { cn } from "@/lib/utils"

const PROSE =
  "font-serif text-base leading-7 text-foreground [&_a]:text-primary [&_a]:underline-offset-4 hover:[&_a]:underline [&_:not(pre)>code]:rounded [&_:not(pre)>code]:bg-muted [&_:not(pre)>code]:px-1 [&_:not(pre)>code]:py-0.5 [&_:not(pre)>code]:font-mono [&_:not(pre)>code]:text-[0.85em] [&_li]:ml-5 [&_ol]:list-decimal [&_p]:mt-3 [&_pre]:mt-3 [&_pre]:overflow-x-auto [&_pre]:rounded-lg [&_pre]:bg-muted [&_pre]:p-3 [&_ul]:list-disc"

/** Sanitized HTML with highlighted code blocks; plain "#anchor" links scroll instead of hitting the router. */
function RichHtml({ html, className, defaultLanguage }: { html: string; className?: string; defaultLanguage?: string }) {
  const ref = useRef<HTMLDivElement>(null)
  const highlighter = useHighlighter()

  useEffect(() => {
    if (ref.current && highlighter) highlightBlocks(ref.current, highlighter, defaultLanguage)
  }, [html, highlighter, defaultLanguage])

  const onClick = (event: MouseEvent<HTMLDivElement>) => {
    const anchor = (event.target as HTMLElement).closest("a")
    const href = anchor?.getAttribute("href")
    if (href && href.startsWith("#") && !href.startsWith("#/")) {
      event.preventDefault()
      scrollToElement(document.getElementById(decodeURIComponent(href.slice(1))))
    }
  }

  return <div ref={ref} className={className} onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />
}

/** Renders javadoc HTML (sanitized) with documentation-site links; <pre> blocks are highlighted as Java. */
export function JavadocText({ text, packageName, className }: { text: string; packageName?: string; className?: string }) {
  const index = useDocs()
  const html = useMemo(
    () => javadocToHtml(text, index, packageName !== undefined ? { packageName } : undefined),
    [text, index, packageName]
  )
  return <RichHtml html={html} className={cn(PROSE, className)} defaultLanguage="java" />
}

/** A module README rendered by the plugin; fenced code blocks are highlighted by their language. */
export function ReadmeView({ html }: { html: string }) {
  const clean = useMemo(() => readmeToHtml(html), [html])
  return (
    <RichHtml
      html={clean}
      className={cn(
        PROSE,
        "max-w-3xl [&_h1]:mt-8 [&_h1]:font-heading [&_h1]:text-2xl [&_h1]:font-semibold [&_h1:first-child]:mt-0 [&_h2]:mt-8 [&_h2]:scroll-mt-20 [&_h2]:border-b [&_h2]:pb-1 [&_h2]:font-heading [&_h2]:text-xl [&_h2]:font-semibold [&_h3]:mt-6 [&_h3]:font-semibold [&_hr]:my-6 [&_img]:inline [&_img]:max-w-full [&_table]:mt-3 [&_table]:w-full [&_table]:text-left [&_td]:border-b [&_td]:py-1.5 [&_td]:pr-4 [&_th]:border-b [&_th]:py-1.5 [&_th]:pr-4 [&_th]:font-medium [&_.shiki]:mt-3"
      )}
    />
  )
}
