// pretty-ts analyzer. Reads TypeScript projects with the TypeScript compiler API and writes the
// same JSON model pretty-java produces for Java sources (JavaFileStructure), so the documentation
// site renders both alike. Run by io.joshuasalcedo.pretty.ts.TypeScriptParser with the user's node:
//
//   node analyzer.cjs request.json response.json
//
// request:  { typescript: "<path to typescript.js>", includeBodies, includeTests, diagnostics,
//             projects: [{ id, dir, name }] }   (deeper projects first; a file belongs to the first that has it)
// response: { typescriptVersion, projects: [{ id, files, testFiles, diagnostics, warnings }] }
// Plain CommonJS, no dependencies besides the compiler: it ships prebuilt, nothing is compiled.
"use strict"

const fs = require("fs")
const path = require("path")

const [requestPath, responsePath] = process.argv.slice(2)
const request = JSON.parse(fs.readFileSync(requestPath, "utf8"))
const ts = require(request.typescript)

const SOURCE = /\.(ts|tsx|mts|cts)$/
const TEST = /(\.(test|spec)\.[cm]?tsx?$)|([\\/]__tests__[\\/])/
const SKIPPED_DIRS = /[\\/](node_modules|dist|build|target|out|coverage|\.[^\\/]+)[\\/]/

const claimed = new Set() // files documented by a deeper project already

function main() {
  const response = { typescriptVersion: ts.version, projects: [] }
  for (const project of request.projects) {
    const warnings = []
    try {
      response.projects.push({ id: project.id, ...analyzeProject(project, warnings), warnings })
    } catch (error) {
      warnings.push(`${project.dir}: ${error && error.stack ? error.stack : error}`)
      response.projects.push({ id: project.id, files: [], testFiles: [], diagnostics: [], warnings })
    }
  }
  fs.writeFileSync(responsePath, JSON.stringify(response))
}

// ------------------------------------------------------------------ projects

function analyzeProject(project, warnings) {
  const dir = path.resolve(project.dir)
  const roots = new Set()
  let options = { allowJs: false, jsx: ts.JsxEmit.Preserve }
  const missingConfigs = [] // "extends" targets that are not installed: the settings the code is written for are unknown
  const visit = (configPath, seen) => {
    if (seen.has(configPath) || !fs.existsSync(configPath)) return
    seen.add(configPath)
    const parsed = ts.getParsedCommandLineOfConfigFile(configPath, {}, {
      ...ts.sys,
      onUnRecoverableConfigFileDiagnostic: (d) => warnings.push(message(d)),
    })
    if (!parsed) return
    for (const e of parsed.errors || []) if (e.code === 6053) missingConfigs.push(message(e))
    if (parsed.fileNames.length > 0 && roots.size === 0) options = parsed.options
    parsed.fileNames.forEach((f) => roots.add(path.resolve(f)))
    for (const reference of parsed.projectReferences || []) {
      const target = fs.existsSync(reference.path) && fs.statSync(reference.path).isDirectory()
        ? path.join(reference.path, "tsconfig.json") : reference.path
      visit(path.resolve(target), seen)
    }
  }
  visit(path.join(dir, "tsconfig.json"), new Set())

  const documented = [...roots].filter((f) => SOURCE.test(f) && !f.endsWith(".d.ts") && f.startsWith(dir + path.sep)
    && !SKIPPED_DIRS.test(f.slice(dir.length)) && !claimed.has(f))
  documented.forEach((f) => claimed.add(f))
  if (documented.length === 0) return { files: [], testFiles: [], diagnostics: [] }

  // Project references are compiled from source (no build output needed); emit settings do not matter.
  const program = ts.createProgram({
    rootNames: [...roots],
    options: { ...options, noEmit: true, composite: false, incremental: false, declaration: false, skipLibCheck: true },
  })
  const context = new Context(program, dir, project.name || path.basename(dir))
  const sourceFiles = documented.map((f) => program.getSourceFile(f)).filter(Boolean)
  sourceFiles.forEach((sf) => context.declare(sf)) // names first, so references across files resolve
  const files = []
  const testFiles = []
  for (const sf of sourceFiles) {
    const test = TEST.test(sf.fileName)
    if (test && !request.includeTests) continue
    ;(test ? testFiles : files).push(context.file(sf))
  }
  sourceFiles.forEach((sf) => context.valueUsages(sf))
  context.linkUsedBy()
  if (request.diagnostics && missingConfigs.length > 0) {
    // without its base configuration (jsx, lib, strictness) the compiler would report errors the code does not have
    warnings.push(`tsconfig extends a configuration that is not installed (${missingConfigs[0]}); run npm install in ${dir} to type-check it`)
    return { files, testFiles, diagnostics: [] }
  }
  return { files, testFiles, diagnostics: request.diagnostics ? diagnosticsOf(program, sourceFiles, dir, warnings) : [] }
}

// Missing modules or their type declarations: one note per project ("run npm install"), not an error per import.
const UNRESOLVED = new Set([2307, 2792, 7016, 2875, 2882])
// What missing packages cause downstream (no JSX types for React, ...): dropped when imports are unresolved.
// 7006: callbacks into an uninstalled library get untyped parameters; 2580/2591/2582: no @types/node or test globals
const CASCADE = new Set([7026, 2503, 2786, 2604, 7031, 7006, 2580, 2591, 2582])
const MAX_DIAGNOSTICS = 1000

/** The compiler's errors and warnings for the documented files (what `tsc --noEmit` would print). */
function diagnosticsOf(program, sourceFiles, dir, warnings) {
  const result = []
  const all = []
  let unresolved = 0
  for (const sf of sourceFiles) {
    if (TEST.test(sf.fileName) && !request.includeTests) continue
    for (const d of [...program.getSyntacticDiagnostics(sf), ...program.getSemanticDiagnostics(sf)]) {
      if (UNRESOLVED.has(d.code)) unresolved++
      else all.push([sf, d])
    }
  }
  for (const [sf, d] of all) {
    if (unresolved > 0 && CASCADE.has(d.code)) continue
    if (result.length >= MAX_DIAGNOSTICS) break
    const start = d.start !== undefined ? sf.getLineAndCharacterOfPosition(d.start).line + 1 : 0
    const end = d.start !== undefined && d.length !== undefined ? sf.getLineAndCharacterOfPosition(d.start + d.length).line + 1 : start
    result.push({
      file: sf.fileName,
      line: start,
      endLine: end,
      code: d.code,
      category: d.category === ts.DiagnosticCategory.Error ? "error" : d.category === ts.DiagnosticCategory.Warning ? "warning" : "suggestion",
      message: message(d),
    })
  }
  if (unresolved > 0) {
    warnings.push(`${unresolved} import(s) could not be resolved; run npm install in ${dir} for a complete type check`)
  }
  return result
}

function message(diagnostic) {
  return ts.flattenDiagnosticMessageText(diagnostic.messageText, "\n")
}

// ------------------------------------------------------------------ model

class Context {
  constructor(program, dir, projectName) {
    this.program = program
    this.checker = program.getTypeChecker()
    this.dir = dir
    this.projectName = projectName
    this.qualified = new Map() // declaration node -> qualified name
    this.types = new Map() // qualified name -> type structure
    this.usedBy = new Map() // qualified name -> Set of qualified names using it
  }

  relative(sf) {
    return path.relative(this.dir, sf.fileName).split(path.sep).join("/")
  }

  packageOf(sf) {
    const rel = path.posix.dirname(this.relative(sf))
    return rel === "." ? "" : rel
  }

  prefix(sf) {
    return `${this.projectName}/${this.relative(sf).replace(/\.[cm]?tsx?$/, "")}`
  }

  /** Pass 1: the qualified name of every declaration the file documents. */
  declare(sf) {
    const exported = this.exportedSymbols(sf)
    const walk = (statements, prefix) => {
      for (const node of statements) {
        for (const declaration of declarationsOf(node)) {
          const name = declarationName(declaration)
          this.qualified.set(declaration, `${prefix}.${name}`)
          if (ts.isModuleDeclaration(declaration) && declaration.body && ts.isModuleBlock(declaration.body)) {
            walk(declaration.body.statements, `${prefix}.${name}`)
          }
        }
      }
    }
    walk(sf.statements, this.prefix(sf))
    this.exported = this.exported || new Map()
    this.exported.set(sf, exported)
  }

  exportedSymbols(sf) {
    const result = new Set()
    const moduleSymbol = this.checker.getSymbolAtLocation(sf)
    if (!moduleSymbol) return result
    for (const symbol of this.checker.getExportsOfModule(moduleSymbol)) {
      const target = symbol.flags & ts.SymbolFlags.Alias ? this.checker.getAliasedSymbol(symbol) : symbol
      ;(target.declarations || []).forEach((d) => result.add(d))
      ;(symbol.declarations || []).forEach((d) => result.add(d))
    }
    return result
  }

  /** Pass 2: the file's structure. */
  file(sf) {
    const exported = this.exported.get(sf)
    const types = []
    const byName = new Map()
    const collect = (statements, into, intoByName, inNamespace, namespaceExported) => {
      for (const node of statements) {
        for (const declaration of declarationsOf(node)) {
          const isExported = inNamespace ? namespaceExported && hasModifier(statementOf(declaration), ts.SyntaxKind.ExportKeyword)
            : exported.has(declaration) || hasModifier(statementOf(declaration), ts.SyntaxKind.ExportKeyword)
          const qualifiedName = this.qualified.get(declaration)
          const existing = intoByName.get(qualifiedName)
          const type = this.type(declaration, sf, qualifiedName, isExported, existing)
          if (!type) continue
          if (!existing) {
            into.push(type)
            intoByName.set(qualifiedName, type)
            this.types.set(qualifiedName, type)
          }
          if (ts.isModuleDeclaration(declaration) && declaration.body && ts.isModuleBlock(declaration.body)) {
            collect(declaration.body.statements, type.nestedTypes, new Map(), true, isExported)
          }
        }
      }
    }
    collect(sf.statements, types, byName, false, false)
    return {
      fileName: path.basename(sf.fileName),
      filePath: sf.fileName,
      packageName: this.packageOf(sf),
      language: "typescript",
      imports: [],
      types,
      fileComments: [],
      ...lineCounts(sf),
    }
  }

  /** A declaration as a type; merges overloads and interface declaration merging into the first. */
  type(node, sf, qualifiedName, isExported, existing) {
    const kind = kindOf(node)
    if (!kind) return null
    const lines = this.lines(node, sf)
    if (existing) {
      if (kind === "FUNCTION") existing.methods.push(...this.functionMethods(node, sf, qualifiedName))
      if (kind === "INTERFACE") this.members(node, sf, existing, qualifiedName)
      existing.endLine = Math.max(existing.endLine, lines.endLine)
      return existing
    }
    const type = {
      name: declarationName(node),
      qualifiedName,
      kind,
      modifiers: modifiers(node, isExported ? "PUBLIC" : "PACKAGE_PRIVATE"),
      annotations: decorators(node),
      typeParameters: this.typeParameters(node),
      interfaces: [],
      fields: [],
      methods: [],
      constructors: [],
      nestedTypes: [],
      enumConstants: [],
      usedBy: [],
      recordComponents: [],
      signature: signatureOf(node, sf),
      javadoc: this.javadoc(node, sf),
      ...lines,
    }
    if (ts.isClassDeclaration(node) || ts.isInterfaceDeclaration(node)) {
      for (const clause of node.heritageClauses || []) {
        for (const expression of clause.types) {
          const reference = { name: expression.expression.getText(sf), qualifiedName: this.resolve(expression.expression, qualifiedName) }
          if (clause.token === ts.SyntaxKind.ExtendsKeyword && ts.isClassDeclaration(node)) type.superclass = reference
          else type.interfaces.push(reference)
        }
      }
      this.members(node, sf, type, qualifiedName)
    } else if (ts.isEnumDeclaration(node)) {
      node.members.forEach((member, ordinal) => type.enumConstants.push({
        name: member.name.getText(sf),
        arguments: member.initializer ? [member.initializer.getText(sf)] : [],
        javadoc: this.javadoc(member, sf),
        ordinal,
      }))
    } else if (kind === "FUNCTION") {
      type.methods.push(...this.functionMethods(node, sf, qualifiedName))
    } else if (kind === "VARIABLE") {
      type.fields.push(this.field(node, sf, qualifiedName, "PUBLIC"))
    } else if (kind === "TYPE_ALIAS") {
      this.references(node.type, qualifiedName)
      if (ts.isTypeLiteralNode(node.type)) this.members(node.type, sf, type, qualifiedName)
    }
    return type
  }

  members(node, sf, type, owner) {
    const names = new Map()
    for (const member of node.members) {
      if (ts.isConstructorDeclaration(member)) {
        if (!member.body && node.members.some((m) => m !== member && ts.isConstructorDeclaration(m) && !m.body)) continue
        type.constructors.push(this.constructor_(member, sf, owner))
      } else if (ts.isMethodDeclaration(member) || ts.isMethodSignature(member)) {
        const name = memberName(member, sf)
        const overloads = node.members.filter((m) => (ts.isMethodDeclaration(m) || ts.isMethodSignature(m)) && memberName(m, sf) === name)
        if (member.body && overloads.some((m) => !m.body)) continue // overloads document it; the implementation signature is hidden
        type.methods.push(this.method(member, sf, owner, name))
      } else if (ts.isPropertyDeclaration(member) || ts.isPropertySignature(member) || ts.isGetAccessorDeclaration(member)) {
        const name = memberName(member, sf)
        if (names.has(name)) continue
        names.set(name, true)
        type.fields.push(this.field(member, sf, owner, memberVisibility(member)))
      } else if (ts.isSetAccessorDeclaration(member)) {
        const name = memberName(member, sf)
        if (!names.has(name) && !node.members.some((m) => ts.isGetAccessorDeclaration(m) && memberName(m, sf) === name)) {
          names.set(name, true)
          type.fields.push(this.field(member, sf, owner, memberVisibility(member)))
        }
      }
    }
    // constructor parameter properties (constructor(private readonly repo: Repo)) are fields too
    for (const ctor of node.members.filter(ts.isConstructorDeclaration)) {
      for (const parameter of ctor.parameters) {
        if (ts.getModifiers && (ts.getModifiers(parameter) || []).length > 0 && ts.isIdentifier(parameter.name)) {
          type.fields.push(this.field(parameter, sf, owner, memberVisibility(parameter)))
        }
      }
    }
  }

  functionMethods(node, sf, owner) {
    const fn = functionOf(node)
    if (!fn) return []
    if (ts.isFunctionDeclaration(node) && node.body) {
      const siblings = (node.parent.statements || []).filter((s) => ts.isFunctionDeclaration(s) && s.name && node.name && s.name.text === node.name.text)
      if (siblings.some((s) => !s.body)) return [] // overload signatures document it
    }
    return [this.method(fn, sf, owner, declarationName(node), node)]
  }

  method(node, sf, owner, name, outer) {
    const signature = this.checker.getSignatureFromDeclaration(node)
    const returnType = node.type ? this.typeRef(node.type, owner)
      : signature ? this.inferred(this.checker.getReturnTypeOfSignature(signature)) : undefined
    return {
      name,
      returnType,
      modifiers: modifiers(node, outer ? "PUBLIC" : memberVisibility(node)),
      annotations: decorators(node),
      parameters: this.parameters(node, owner),
      thrownExceptions: [],
      typeParameters: this.typeParameters(node),
      body: request.includeBodies && node.body ? node.body.getText(sf) : undefined,
      javadoc: this.javadoc(outer || node, sf),
      calledBy: [],
      calls: [],
      cyclomaticComplexity: complexity(node.body),
      lineCount: this.lines(node, sf).endLine - this.lines(node, sf).startLine + 1,
      signature: signatureOf(outer && !ts.isFunctionDeclaration(outer) ? outer : node, sf),
      ...this.lines(outer || node, sf),
    }
  }

  constructor_(node, sf, owner) {
    return {
      name: "constructor",
      modifiers: modifiers(node, memberVisibility(node)),
      annotations: decorators(node),
      parameters: this.parameters(node, owner),
      thrownExceptions: [],
      typeParameters: [],
      body: request.includeBodies && node.body ? node.body.getText(sf) : undefined,
      javadoc: this.javadoc(node, sf),
      calledBy: [],
      signature: signatureOf(node, sf),
      ...this.lines(node, sf),
    }
  }

  field(node, sf, owner, visibility) {
    const declaration = ts.isVariableStatement(node) ? node.declarationList.declarations[0] : node
    const typeNode = ts.isGetAccessorDeclaration(declaration) ? declaration.type
      : ts.isSetAccessorDeclaration(declaration) ? declaration.parameters[0] && declaration.parameters[0].type : declaration.type
    const type = typeNode ? this.typeRef(typeNode, owner) : this.inferred(this.checker.getTypeAtLocation(declaration))
    const initializer = declaration.initializer ? declaration.initializer.getText(sf) : undefined
    return {
      name: memberName(declaration, sf),
      type,
      modifiers: modifiers(declaration, visibility),
      annotations: decorators(declaration),
      initializer: initializer && initializer.length <= 200 ? initializer : undefined,
      javadoc: this.javadoc(node, sf),
      readBy: [],
      writtenBy: [],
      signature: ts.isVariableStatement(node) ? variableSignature(node, declaration, sf, type) : signatureOf(declaration, sf),
      ...this.lines(node, sf),
    }
  }

  parameters(node, owner) {
    return (node.parameters || []).filter((p) => !(ts.isIdentifier(p.name) && p.name.text === "this")).map((p) => ({
      name: ts.isIdentifier(p.name) ? p.name.text : p.name.getText(),
      type: p.type ? this.typeRef(p.type, owner) : this.inferred(this.checker.getTypeAtLocation(p)),
      annotations: decorators(p),
      isVarArgs: !!p.dotDotDotToken,
      isFinal: false,
      optional: !!p.questionToken || !!p.initializer,
      defaultValue: p.initializer ? p.initializer.getText() : undefined,
    }))
  }

  typeParameters(node) {
    return (node.typeParameters || []).map((p) => ({
      name: p.name.text,
      bounds: p.constraint ? [this.typeRef(p.constraint, null)] : [],
    }))
  }

  /** A written type as a TypeReference: the base name, type arguments, arrays; project types resolve to their declaration. */
  typeRef(node, owner) {
    if (!node) return undefined
    if (ts.isArrayTypeNode(node)) {
      const element = this.typeRef(node.elementType, owner)
      return { ...element, isArray: true, arrayDimensions: (element.arrayDimensions || 0) + 1 }
    }
    if (ts.isParenthesizedTypeNode(node)) return this.typeRef(node.type, owner)
    if (ts.isTypeReferenceNode(node)) {
      return {
        name: node.typeName.getText(),
        qualifiedName: this.resolve(node.typeName, owner),
        typeArguments: node.typeArguments ? node.typeArguments.map((a) => this.typeRef(a, owner)) : undefined,
        isArray: false, arrayDimensions: 0, isPrimitive: false, isWildcard: false,
      }
    }
    this.references(node, owner)
    return {
      name: node.getText().replace(/\s+/g, " "),
      isArray: false, arrayDimensions: 0,
      isPrimitive: node.kind >= ts.SyntaxKind.FirstKeyword && node.kind <= ts.SyntaxKind.LastKeyword,
      isWildcard: false,
    }
  }

  inferred(type) {
    const text = type ? this.checker.typeToString(type, undefined, ts.TypeFormatFlags.NoTruncation | ts.TypeFormatFlags.UseFullyQualifiedType) : "any"
    return { name: text.length > 300 ? `${text.slice(0, 297)}...` : text.replace(/import\("[^"]*"\)\./g, ""), isArray: false, arrayDimensions: 0, isPrimitive: false, isWildcard: false }
  }

  /** Records project types referenced anywhere inside a type (unions, object types, functions), for usedBy. */
  references(node, owner) {
    if (!node || !owner) return
    const walk = (n) => {
      if (ts.isTypeReferenceNode(n) || ts.isExpressionWithTypeArguments(n)) this.resolve(n.typeName || n.expression, owner)
      ts.forEachChild(n, walk)
    }
    walk(node)
  }

  /** The qualified name a reference points to, when it is declared in this project; records the usage. */
  resolve(name, owner) {
    let symbol = this.checker.getSymbolAtLocation(name)
    if (!symbol) return undefined
    if (symbol.flags & ts.SymbolFlags.Alias) {
      try {
        symbol = this.checker.getAliasedSymbol(symbol)
      } catch {
        return undefined
      }
    }
    for (const declaration of symbol.declarations || []) {
      const target = this.qualified.get(declaration)
      if (target) {
        if (owner && owner !== target && !owner.startsWith(`${target}.`)) {
          if (!this.usedBy.has(target)) this.usedBy.set(target, new Set())
          this.usedBy.get(target).add(owner.replace(/\.[^.]*\(.*$/, ""))
        }
        return target
      }
    }
    return undefined
  }

  /**
   * Value usages: calls, JSX elements, reads of constants, `new`, `extends` expressions. Each
   * identifier is resolved by the checker and credited to the declaration it appears in; code
   * outside every declaration (top-level statements) credits nothing.
   */
  valueUsages(sf) {
    const walk = (node, owner) => {
      const own = this.qualified.get(node)
      if (own) owner = own
      if (ts.isIdentifier(node) && owner && !(node.parent && node.parent.name === node && this.qualified.has(node.parent))) {
        this.resolve(node, owner)
      }
      ts.forEachChild(node, (child) => walk(child, owner))
    }
    walk(sf, undefined)
  }

  linkUsedBy() {
    for (const [target, users] of this.usedBy) {
      const type = this.types.get(target)
      if (type) type.usedBy = [...users].filter((u) => this.types.has(u)).sort()
    }
  }

  javadoc(node, sf) {
    const host = ts.isVariableDeclaration(node) ? node.parent.parent : node
    const docs = (host.jsDoc || []).filter((d) => d.kind === ts.SyntaxKind.JSDoc)
    const doc = docs[docs.length - 1]
    if (!doc) {
      // overload signatures without docs share the implementation's
      const implementation = (ts.isFunctionDeclaration(node) || ts.isMethodDeclaration(node)) && !node.body && node.name
        ? (node.parent.statements || node.parent.members || []).find((s) => s !== node && s.kind === node.kind && s.body
          && s.name && s.name.getText(sf) === node.name.getText(sf))
        : undefined
      return implementation ? this.javadoc(implementation, sf) : undefined
    }
    const tags = (doc.tags || []).map((tag, position) => {
      const raw = tag.tagName.text
      const name = raw === "returns" ? "@return" : raw === "throws" ? "@throws" : `@${raw}`
      return {
        name,
        text: (ts.getTextOfJSDocComment(tag.comment) || "").trim(),
        paramName: ts.isJSDocParameterTag(tag) ? tag.name.getText(sf) : undefined,
        position,
      }
    })
    const lines = this.lines(doc, sf)
    return { description: (ts.getTextOfJSDocComment(doc.comment) || "").trim(), tags, ...lines }
  }

  lines(node, sf) {
    return {
      startLine: sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1,
      endLine: sf.getLineAndCharacterOfPosition(node.getEnd()).line + 1,
    }
  }
}

// ------------------------------------------------------------------ helpers

/** The declarations a statement contributes: one per variable for `const a = 1, b = 2`. */
function declarationsOf(node) {
  if (ts.isClassDeclaration(node) || ts.isInterfaceDeclaration(node) || ts.isEnumDeclaration(node)
    || ts.isTypeAliasDeclaration(node) || ts.isFunctionDeclaration(node)) {
    return node.name || hasModifier(node, ts.SyntaxKind.DefaultKeyword) ? [node] : []
  }
  if (ts.isModuleDeclaration(node) && ts.isIdentifier(node.name)) return [node]
  if (ts.isVariableStatement(node)) {
    return node.declarationList.declarations.filter((d) => ts.isIdentifier(d.name))
  }
  return []
}

function statementOf(declaration) {
  return ts.isVariableDeclaration(declaration) ? declaration.parent.parent : declaration
}

function declarationName(node) {
  if (node.name && ts.isIdentifier(node.name)) return node.name.text
  if (node.name) return node.name.getText()
  return "default"
}

function memberName(node, sf) {
  if (!node.name) return "constructor"
  if (ts.isPrivateIdentifier(node.name)) return node.name.text
  return node.name.getText(sf)
}

function kindOf(node) {
  if (ts.isClassDeclaration(node)) return "CLASS"
  if (ts.isInterfaceDeclaration(node)) return "INTERFACE"
  if (ts.isEnumDeclaration(node)) return "ENUM"
  if (ts.isTypeAliasDeclaration(node)) return "TYPE_ALIAS"
  if (ts.isFunctionDeclaration(node)) return "FUNCTION"
  if (ts.isModuleDeclaration(node)) return "NAMESPACE"
  if (ts.isVariableDeclaration(node)) return functionOf(node) ? "FUNCTION" : "VARIABLE"
  return null
}

/** The function behind a declaration: itself, or an arrow function / function expression a const holds. */
function functionOf(node) {
  if (ts.isFunctionDeclaration(node)) return node
  if (ts.isVariableDeclaration(node) && node.initializer) {
    let init = node.initializer
    while (ts.isParenthesizedExpression(init) || ts.isAsExpression(init) || ts.isSatisfiesExpression?.(init)) init = init.expression
    if (ts.isArrowFunction(init) || ts.isFunctionExpression(init)) return init
    // forwardRef((props, ref) => ...), memo(() => ...): document the wrapped function
    if (ts.isCallExpression(init) && init.arguments.length > 0
      && (ts.isArrowFunction(init.arguments[0]) || ts.isFunctionExpression(init.arguments[0]))) return init.arguments[0]
  }
  return null
}

function hasModifier(node, kind) {
  const list = (ts.canHaveModifiers && ts.canHaveModifiers(node) ? ts.getModifiers(node) : node.modifiers) || []
  return list.some((m) => m.kind === kind)
}

function memberVisibility(node) {
  if (node.name && ts.isPrivateIdentifier(node.name)) return "PRIVATE"
  if (hasModifier(node, ts.SyntaxKind.PrivateKeyword)) return "PRIVATE"
  if (hasModifier(node, ts.SyntaxKind.ProtectedKeyword)) return "PROTECTED"
  return "PUBLIC"
}

/** Java-style modifiers for filtering and badges; the TypeScript wording is in the signature. */
function modifiers(node, visibility) {
  const result = [{ kind: visibility, position: 0 }]
  if (hasModifier(node, ts.SyntaxKind.StaticKeyword)) result.push({ kind: "STATIC", position: result.length })
  if (hasModifier(node, ts.SyntaxKind.AbstractKeyword)) result.push({ kind: "ABSTRACT", position: result.length })
  if (hasModifier(node, ts.SyntaxKind.ReadonlyKeyword)) result.push({ kind: "FINAL", position: result.length })
  return result
}

function decorators(node) {
  const list = ts.canHaveDecorators && ts.canHaveDecorators(node) ? ts.getDecorators(node) || [] : []
  return list.map((d) => {
    const expression = d.expression
    const callee = ts.isCallExpression(expression) ? expression.expression : expression
    const args = ts.isCallExpression(expression) ? expression.arguments.map((a) => a.getText()).join(", ") : ""
    return { name: callee.getText(), values: args ? { value: args } : {} }
  })
}

/** The declaration as written, without its body or doc comment, on one line. */
function signatureOf(node, sf) {
  const text = sf.text
  const start = node.getStart(sf)
  let end = node.getEnd()
  if (node.members && (ts.isClassDeclaration(node) || ts.isInterfaceDeclaration(node) || ts.isEnumDeclaration(node))) {
    end = text.lastIndexOf("{", node.members.pos)
  } else if (node.body && (ts.isFunctionLike(node))) {
    end = node.body.getStart(sf)
  } else if (ts.isModuleDeclaration(node) && node.body) {
    end = node.body.getStart(sf)
  } else if (ts.isVariableDeclaration(node)) {
    const fn = functionOf(node)
    const exported = hasModifier(node.parent.parent, ts.SyntaxKind.ExportKeyword) ? "export " : ""
    const keyword = node.parent.flags & ts.NodeFlags.Const ? "const" : node.parent.flags & ts.NodeFlags.Let ? "let" : "var"
    const head = text.slice(node.getStart(sf), fn && fn.body ? fn.body.getStart(sf) : node.getEnd())
    return clean(`${exported}${keyword} ${head}`).replace(/\s*=>\s*$/, "")
  }
  return clean(text.slice(start, Math.max(start, end)))
}

function variableSignature(statement, declaration, sf, type) {
  const keyword = statement.declarationList.flags & ts.NodeFlags.Const ? "const" : statement.declarationList.flags & ts.NodeFlags.Let ? "let" : "var"
  const exported = hasModifier(statement, ts.SyntaxKind.ExportKeyword) ? "export " : ""
  const declared = hasModifier(statement, ts.SyntaxKind.DeclareKeyword) ? "declare " : ""
  const init = declaration.initializer ? declaration.initializer.getText(sf) : ""
  const value = init && init.length <= 80 && !init.includes("\n") ? ` = ${init}` : ""
  return clean(`${exported}${declared}${keyword} ${declaration.name.getText(sf)}: ${type.name}${value}`)
}

function clean(text) {
  return text.replace(/\/\*[\s\S]*?\*\//g, "").replace(/\s+/g, " ").replace(/\s*\{\s*$/, "").replace(/;\s*$/, "").trim()
}

/** Cyclomatic complexity: 1 plus each branch (if, loops, case, catch, ?:, &&, ||, ??). */
function complexity(body) {
  if (!body) return 1
  let count = 1
  const walk = (node) => {
    switch (node.kind) {
      case ts.SyntaxKind.IfStatement:
      case ts.SyntaxKind.ConditionalExpression:
      case ts.SyntaxKind.ForStatement:
      case ts.SyntaxKind.ForInStatement:
      case ts.SyntaxKind.ForOfStatement:
      case ts.SyntaxKind.WhileStatement:
      case ts.SyntaxKind.DoStatement:
      case ts.SyntaxKind.CaseClause:
      case ts.SyntaxKind.CatchClause:
        count++
        break
      case ts.SyntaxKind.BinaryExpression: {
        const op = node.operatorToken.kind
        if (op === ts.SyntaxKind.AmpersandAmpersandToken || op === ts.SyntaxKind.BarBarToken || op === ts.SyntaxKind.QuestionQuestionToken) count++
        break
      }
    }
    // nested functions have their own complexity
    if (!ts.isFunctionLike(node) || node === body) ts.forEachChild(node, walk)
  }
  walk(body)
  return count
}

/** cloc-style line counts: a line with code and a comment counts as code. */
function lineCounts(sf) {
  const text = sf.text
  const inComment = new Uint8Array(text.length)
  const seen = new Set()
  const mark = (ranges) => {
    for (const r of ranges || []) {
      if (seen.has(r.pos)) continue
      seen.add(r.pos)
      inComment.fill(1, r.pos, r.end)
    }
  }
  const walk = (node) => {
    mark(ts.getLeadingCommentRanges(text, node.pos))
    mark(ts.getTrailingCommentRanges(text, node.end))
    ts.forEachChild(node, walk)
  }
  walk(sf)
  mark(ts.getLeadingCommentRanges(text, sf.endOfFileToken.pos))
  let code = 0
  let comment = 0
  let blank = 0
  let start = 0
  const lines = text.split(/\r?\n/)
  for (const line of lines) {
    let hasCode = false
    let hasComment = false
    for (let i = 0; i < line.length; i++) {
      if (/\s/.test(line[i])) continue
      if (inComment[start + i]) hasComment = true
      else hasCode = true
    }
    if (hasCode) code++
    else if (hasComment) comment++
    else blank++
    start += line.length + (text[start + line.length] === "\r" ? 2 : 1)
  }
  return { totalLines: lines.length, codeLines: code, commentLines: comment, blankLines: blank }
}

main()
