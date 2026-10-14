import { externalTypeUrl, routes, type DocsIndex } from "./model"

const JAVADOC_TAGS = new Set([
  "p", "br", "code", "pre", "em", "strong", "b", "i", "u", "ul", "ol", "li", "a",
  "table", "thead", "tbody", "tr", "th", "td", "blockquote", "dl", "dt", "dd", "sup", "sub", "span",
])
const README_TAGS = new Set([...JAVADOC_TAGS, "h1", "h2", "h3", "h4", "h5", "h6", "img", "hr", "del", "s", "input", "details", "summary"])

/**
 * Turns javadoc text (HTML plus inline tags like {@code x} and {@link Foo#bar}) into safe HTML.
 * Unknown tags are unwrapped, attributes dropped (except safe hrefs), and links point to this site
 * or, for types outside the project, to their external javadoc.
 */
export function javadocToHtml(text: string, index: DocsIndex, context?: { packageName: string }): string {
  const withInlineTags = text
    .replace(/\{@(?:code|literal)\s+((?:[^{}]|\{[^{}]*})*)}/g, (_, code: string) => `<code>${escapeHtml(code.trim())}</code>`)
    .replace(/\{@(?:link|linkplain)\s+([^}\s]+)(?:\s+([^}]*))?}/g, (_, ref: string, label?: string) =>
      link(ref, label, index, context)
    )
    .replace(/\{@(?:value|inheritDoc|docRoot)\s*([^}]*)}/g, (_, rest: string) => escapeHtml(rest))
    // Blank lines separate paragraphs in javadoc written without <p>.
    .replace(/\n\s*\n/g, "<p>")
  return sanitize(withInlineTags, JAVADOC_TAGS)
}

/** Sanitizes README HTML (rendered from Markdown by the plugin); keeps headings, images and code languages. */
export function readmeToHtml(html: string): string {
  return sanitize(html, README_TAGS)
}

function link(ref: string, label: string | undefined, index: DocsIndex, context?: { packageName: string }) {
  const [typePart, member] = ref.split("#")
  const text = escapeHtml(label?.trim() || (typePart ? ref.replace("#", ".") : member ?? ref))
  const qualified = resolveType(typePart, index, context)
  if (qualified) return `<a href="${routes.type(qualified, member?.replace(/\s/g, ""))}"><code>${text}</code></a>`
  const external = typePart ? externalTypeUrl(index, typePart) : undefined
  if (external) return `<a href="${escapeHtml(external.url)}"><code>${text}</code></a>`
  return `<code>${text}</code>`
}

/** Finds a documented type by qualified name, same-package simple name, or unique simple name. */
export function resolveType(name: string | undefined, index: DocsIndex, context?: { packageName: string }) {
  if (!name) return undefined
  if (index.types.has(name)) return name
  if (context && index.types.has(`${context.packageName}.${name}`)) return `${context.packageName}.${name}`
  if (name.includes(".") && /^[a-z]/.test(name)) return undefined // fully qualified but not ours
  let found: string | undefined
  for (const [qualified, indexed] of index.types) {
    if (indexed.type.name === name || qualified.endsWith(`.${name}`)) {
      if (found) return undefined // ambiguous
      found = qualified
    }
  }
  return found
}

function sanitize(html: string, allowed: Set<string>): string {
  const doc = new DOMParser().parseFromString(`<div>${html}</div>`, "text/html")
  const root = doc.body.firstElementChild!
  clean(root, allowed)
  return root.innerHTML
}

const SAFE_URL = /^(https?:|#|mailto:)/i

function clean(element: Element, allowed: Set<string>) {
  for (const child of [...element.children]) {
    clean(child, allowed)
    const tag = child.tagName.toLowerCase()
    if (/^h[1-6]$/.test(tag) && !allowed.has(tag)) {
      const strong = child.ownerDocument.createElement("strong")
      strong.append(...child.childNodes)
      child.replaceWith(strong)
      continue
    }
    if (!allowed.has(tag)) {
      child.replaceWith(...child.childNodes)
      continue
    }
    for (const attribute of [...child.attributes]) {
      const name = attribute.name
      const keep =
        (tag === "a" && name === "href" && SAFE_URL.test(attribute.value)) ||
        // readme-assets/ holds README images the plugin copied next to the site
        (tag === "img" && (name === "alt" || (name === "src" && /^(https?:|data:image\/|readme-assets\/)/i.test(attribute.value)))) ||
        (tag === "code" && name === "class" && /^language-[\w+-]+$/.test(attribute.value)) ||
        (/^h[1-6]$/.test(tag) && name === "id") ||
        (tag === "input" && (name === "type" || name === "checked" || name === "disabled"))
      if (!keep) child.removeAttribute(name)
    }
    if (tag === "input" && child.getAttribute("type") !== "checkbox") child.remove()
    if (tag === "img" && !child.hasAttribute("src")) child.remove() // images the plugin could not copy
    if (tag === "a" && /^https?:|^mailto:/i.test(child.getAttribute("href") ?? "")) {
      child.setAttribute("target", "_blank")
      child.setAttribute("rel", "noreferrer")
    }
  }
}

export function escapeHtml(text: string) {
  return text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;")
}
