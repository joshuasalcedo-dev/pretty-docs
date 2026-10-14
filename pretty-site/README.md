# Pretty Site

The site template the Pretty Docs Maven plugin fills with your project's documentation. It is a React app
(Vite, TypeScript, Tailwind CSS v4, shadcn/ui on Base UI) built into **one self-contained `index.html`**,
which is committed into the plugin at `pretty-docs-maven-plugin/src/main/resources/pretty-site/`. This folder
is not a Maven module: Maven builds never run Node.js.

At generation time the plugin copies that `index.html` next to the data it writes (`docs-data.js`,
`docs.json`, `diagrams/`), so the result works from any static host and straight from `file://`.

## How it reads the data

`index.html` loads `docs-data.js` with a classic `<script>` tag; the data arrives as
`window.__PRETTY_DOCS__`. Navigation uses the URL hash (`#/t/<type>`, `#/m/<module>/p/<package>`,
`#/spring`, `#/stats`). Both choices exist because browsers block module scripts and `fetch()` on
`file://` pages; keep them when changing the app.

The data shape is defined by the plugin's `DocumentationSite` model and mirrored in
[`src/lib/docs/types.ts`](src/lib/docs/types.ts).

## Working on it

```shell
# 1. Generate data for any project
mvn io.joshuasalcedo.pretty:pretty-docs-maven-plugin:1.0.1:generate
# 2. Use it in the dev server
cp target/pretty-docs/docs-data.js pretty-site/public/
cd pretty-site && npm run dev
```

`npm run build` type-checks and writes the single-file build into the plugin's resources. Commit that
`index.html` together with your source change; CI rebuilds it and fails if the two differ.

| Path | What is there |
|---|---|
| `src/pages/` | Module, package, type, Spring and statistics pages |
| `src/components/docs/` | Sidebar, search, type links and icons, javadoc rendering, code, diagrams |
| `src/components/ui/` | shadcn/ui components (add more with `npx shadcn@latest add <name>`) |
| `src/lib/docs/` | Data types, the search/link index, javadoc sanitizing |
| `src/lib/highlight.ts` | Shiki syntax highlighting |
| `src/index.css` | Theme tokens (light and dark), chart and type-kind colors |
