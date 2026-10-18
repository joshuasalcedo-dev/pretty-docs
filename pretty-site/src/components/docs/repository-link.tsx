import { GitBranchIcon } from "lucide-react"

import { Button } from "@/components/ui/button"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

/** The GitHub mark (lucide no longer ships brand icons). */
function GitHubMark() {
  return (
    <svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true">
      <path d="M12 .3a12 12 0 0 0-3.8 23.4c.6.1.8-.3.8-.6v-2c-3.3.7-4-1.6-4-1.6-.6-1.4-1.4-1.8-1.4-1.8-1-.7.1-.7.1-.7 1.2 0 1.9 1.2 1.9 1.2 1 1.8 2.8 1.3 3.5 1 0-.8.4-1.3.7-1.6-2.7-.3-5.5-1.3-5.5-6 0-1.2.5-2.3 1.3-3.1-.2-.4-.6-1.6 0-3.2 0 0 1-.3 3.4 1.2a11.5 11.5 0 0 1 6 0c2.3-1.5 3.3-1.2 3.3-1.2.6 1.6.2 2.8.1 3.2.8.8 1.3 1.9 1.3 3.2 0 4.6-2.8 5.6-5.5 5.9.5.4.9 1 .9 2.2v3.3c0 .3.1.7.8.6A12 12 0 0 0 12 .3" />
    </svg>
  )
}

export function repositoryLabel(url: string) {
  try {
    const { hostname, pathname } = new URL(url)
    const host = hostname.replace(/^www\./, "")
    const name = host === "github.com" ? "GitHub" : host === "gitlab.com" ? "GitLab" : host === "bitbucket.org" ? "Bitbucket" : host
    return { name, path: pathname.replace(/^\/|\/$/g, "") }
  } catch {
    return { name: "Repository", path: url }
  }
}

/** An icon button to the source repository. */
export function RepositoryLink({ url }: { url: string }) {
  const { name, path } = repositoryLabel(url)
  return (
    <Tooltip>
      <TooltipTrigger
        render={<Button variant="ghost" size="icon-sm" render={<a href={url} target="_blank" rel="noreferrer" aria-label={`${name}: ${path}`} />} />}
      >
        {name === "GitHub" ? <GitHubMark /> : <GitBranchIcon />}
      </TooltipTrigger>
      <TooltipContent>{name} · {path}</TooltipContent>
    </Tooltip>
  )
}
