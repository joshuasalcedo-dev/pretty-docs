import { CheckIcon, CopyIcon } from "lucide-react"
import { useEffect, useMemo, useState } from "react"

import { Button } from "@/components/ui/button"
import { highlight, useHighlighter } from "@/lib/highlight"
import { cn } from "@/lib/utils"

/** Removes the common leading indentation so method bodies start at column 0. */
function dedent(code: string) {
  const lines = code.replace(/\t/g, "    ").split(/\r?\n/)
  const indents = lines.slice(1).filter((l) => l.trim()).map((l) => l.match(/^ */)![0].length)
  const strip = indents.length ? Math.min(...indents) : 0
  return [lines[0], ...lines.slice(1).map((l) => l.slice(Math.min(strip, l.match(/^ */)![0].length)))].join("\n")
}

/** Copies text; the icon turns into a check for a moment. */
export function CopyButton({ text, className }: { text: string; className?: string }) {
  const [copied, setCopied] = useState(false)
  useEffect(() => {
    if (!copied) return
    const timer = setTimeout(() => setCopied(false), 1500)
    return () => clearTimeout(timer)
  }, [copied])
  return (
    <Button
      variant="ghost"
      size="icon-xs"
      className={className}
      aria-label={copied ? "Copied" : "Copy code"}
      onClick={() => navigator.clipboard?.writeText(text).then(() => setCopied(true))}
    >
      {copied ? <CheckIcon /> : <CopyIcon />}
    </Button>
  )
}

/** Syntax-highlighted code (Shiki, VS Code grammars) with a copy button; plain text until the grammars load. */
export function JavaCode({ code, className, language = "java" }: { code: string; className?: string; language?: string }) {
  const highlighter = useHighlighter()
  const source = useMemo(() => dedent(code), [code])
  const html = useMemo(() => (highlighter ? highlight(highlighter, source, language) : null), [highlighter, source, language])

  return (
    <div className={cn("group/code relative", className)}>
      {html ? (
        <div dangerouslySetInnerHTML={{ __html: html }} />
      ) : (
        <pre className="overflow-x-auto rounded-md bg-muted px-4 py-3 font-mono text-[13px] leading-6">
          <code>{source}</code>
        </pre>
      )}
      <CopyButton text={source} className="absolute top-2 right-2 opacity-60 group-hover/code:opacity-100" />
    </div>
  )
}
