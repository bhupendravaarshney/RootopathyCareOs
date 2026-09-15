import { readdir, readFile } from 'node:fs/promises';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import ts from 'typescript';

const sourceDirectory = fileURLToPath(new URL('../src/', import.meta.url));
const governedAreas = new Set(['api', 'components', 'data', 'features', 'pages']);
const allowedTargets = {
  api: new Set(['api']),
  components: new Set(['api', 'components', 'data']),
  data: new Set(['api', 'data']),
  features: new Set(['api', 'components', 'data', 'features']),
  pages: new Set(['api', 'components', 'data', 'features', 'pages']),
};

async function sourceFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const path = resolve(directory, entry.name);
    if (entry.isDirectory()) {
      files.push(...(await sourceFiles(path)));
    } else if (entry.isFile() && /\.(?:ts|tsx)$/.test(entry.name)) {
      files.push(path);
    }
  }
  return files.sort();
}

function normalizedRelative(root, path) {
  return relative(root, path).split(sep).join('/');
}

function areaFor(root, path) {
  const relativePath = normalizedRelative(root, path);
  const [area = 'root', feature] = relativePath.split('/');
  return {
    area: governedAreas.has(area) ? area : 'root',
    feature: area === 'features' ? feature : undefined,
    relativePath,
  };
}

function importedModules(contents, file) {
  const kind = file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const source = ts.createSourceFile(file, contents, ts.ScriptTarget.Latest, true, kind);
  const modules = [];

  function visit(node) {
    if (
      (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) &&
      node.moduleSpecifier &&
      ts.isStringLiteral(node.moduleSpecifier)
    ) {
      modules.push(node.moduleSpecifier.text);
    } else if (
      ts.isCallExpression(node) &&
      node.expression.kind === ts.SyntaxKind.ImportKeyword &&
      node.arguments.length === 1 &&
      ts.isStringLiteral(node.arguments[0])
    ) {
      modules.push(node.arguments[0].text);
    }
    ts.forEachChild(node, visit);
  }

  visit(source);
  return modules;
}

function boundaryViolation(root, sourceFile, specifier) {
  if (!specifier.startsWith('.') && !isAbsolute(specifier)) {
    return undefined;
  }

  const source = areaFor(root, sourceFile);
  const targetPath = resolve(dirname(sourceFile), specifier);
  const targetRelative = normalizedRelative(root, targetPath);
  if (targetRelative === '..' || targetRelative.startsWith('../')) {
    return `${source.relativePath} imports ${specifier}, which escapes the frontend source boundary`;
  }

  if (source.area === 'root') {
    return undefined;
  }
  const target = areaFor(root, targetPath);
  if (!allowedTargets[source.area].has(target.area)) {
    return `${source.relativePath} (${source.area}) imports ${specifier} (${target.area}), which reverses the frontend dependency direction`;
  }
  if (
    source.area === 'features' &&
    target.area === 'features' &&
    source.feature !== target.feature
  ) {
    return `${source.relativePath} imports another feature (${target.feature}) directly; compose features at a page or root boundary`;
  }
  return undefined;
}

export async function verifyFrontendBoundaries(root = sourceDirectory) {
  const absoluteRoot = resolve(root);
  const files = await sourceFiles(absoluteRoot);
  const violations = [];
  let relativeImports = 0;

  for (const file of files) {
    const contents = await readFile(file, 'utf8');
    for (const specifier of importedModules(contents, file)) {
      if (!specifier.startsWith('.') && !isAbsolute(specifier)) {
        continue;
      }
      relativeImports += 1;
      const violation = boundaryViolation(absoluteRoot, file, specifier);
      if (violation) {
        violations.push(violation);
      }
    }
  }

  if (violations.length > 0) {
    throw new Error(`Frontend boundary verification failed:\n- ${violations.join('\n- ')}`);
  }

  return {
    files: files.length,
    features: [
      ...new Set(
        files
          .map((file) => areaFor(absoluteRoot, file))
          .filter((location) => location.area === 'features')
          .map((location) => location.feature),
      ),
    ].sort(),
    relativeImports,
    status: 'PASS',
  };
}

const invokedPath = process.argv[1] ? resolve(process.argv[1]) : undefined;
if (invokedPath === fileURLToPath(import.meta.url)) {
  console.log(JSON.stringify(await verifyFrontendBoundaries(), null, 2));
}
