/** Scrolls the window so `element` sits just below the sticky header (scrollIntoView can scroll the wrong container). */
export function scrollToElement(element: Element | null) {
  if (!element) return false
  const header = document.querySelector("header")
  const offset = (header?.getBoundingClientRect().height ?? 0) + 16
  window.scrollTo({ top: element.getBoundingClientRect().top + window.scrollY - offset })
  return true
}
