import { useEffect, useState } from "react"
import { createHighlighterCore, type HighlighterCore } from "shiki/core"
import { createJavaScriptRegexEngine } from "shiki/engine/javascript"

// Only the grammars a Java project's docs need, to keep the single-file bundle small.
const LANGUAGES: Record<string, string> = {
  java: "java",
  kotlin: "kotlin",
  kt: "kotlin",
  groovy: "groovy",
  xml: "xml",
  html: "xml",
  pom: "xml",
  json: "json",
  yaml: "yaml",
  yml: "yaml",
  properties: "properties",
  sql: "sql",
  bash: "shellscript",
  sh: "shellscript",
  shell: "shellscript",
  shellscript: "shellscript",
  console: "shellscript",
  diff: "diff",
}

let instance: HighlighterCore | null = null
const ready = createHighlighterCore({
  themes: [import("@shikijs/themes/github-light"), import("@shikijs/themes/github-dark")],
  langs: [
    import("@shikijs/langs/java"),
    import("@shikijs/langs/kotlin"),
    import("@shikijs/langs/groovy"),
    import("@shikijs/langs/xml"),
    import("@shikijs/langs/json"),
    import("@shikijs/langs/yaml"),
    import("@shikijs/langs/properties"),
    import("@shikijs/langs/sql"),
    import("@shikijs/langs/shellscript"),
    import("@shikijs/langs/diff"),
  ],
  engine: createJavaScriptRegexEngine(),
}).then((highlighter) => (instance = highlighter))

/** The shared highlighter once its grammars are loaded, null before that (render plain text meanwhile). */
export function useHighlighter(): HighlighterCore | null {
  const [highlighter, setHighlighter] = useState(instance)
  useEffect(() => {
    if (!highlighter) ready.then(setHighlighter)
  }, [highlighter])
  return highlighter
}

/** HTML for a code block, with both themes as CSS variables (see .shiki rules in index.css). */
export function highlight(highlighter: HighlighterCore, code: string, language = "java"): string {
  const lang = LANGUAGES[language.toLowerCase()]
  return highlighter.codeToHtml(code, {
    lang: lang ?? "text",
    themes: { light: "github-light", dark: "github-dark" },
    defaultColor: false,
  })
}

/**
 * Highlights <pre><code> blocks inside rendered HTML (README, javadoc). The language comes from a
 * `language-xxx` class (Markdown fences); javadoc <pre> blocks have none and are taken as Java.
 */
export function highlightBlocks(root: HTMLElement, highlighter: HighlighterCore, defaultLanguage?: string) {
  for (const pre of root.querySelectorAll("pre")) {
    if (pre.classList.contains("shiki")) continue
    const code = pre.querySelector("code") ?? pre
    const language = [...code.classList].find((c) => c.startsWith("language-"))?.slice("language-".length) ?? defaultLanguage
    if (!language || !LANGUAGES[language.toLowerCase()]) continue
    const wrapper = document.createElement("div")
    wrapper.innerHTML = highlight(highlighter, code.textContent ?? "", language)
    const highlighted = wrapper.firstElementChild
    if (highlighted) pre.replaceWith(highlighted)
  }
}
