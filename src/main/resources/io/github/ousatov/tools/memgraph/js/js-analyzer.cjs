#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const ts = require('typescript');

const args = process.argv.slice(2);
const file = argValue('--file');
const root = argValue('--root') || process.cwd();

if (!file) {
  console.error('Missing --file');
  process.exit(1);
}

const text = fs.readFileSync(file, 'utf8');
const sourceFile = ts.createSourceFile(
  file,
  text,
  ts.ScriptTarget.Latest,
  true,
  scriptKind(file)
);

const modulePath = path.relative(root, file).replace(/\\/g, '/');
const moduleDir = path.dirname(modulePath);
const dirParts = moduleDir === '.'
  ? []
  : moduleDir.split('/').map(pathIdentityPart).filter(Boolean);
const moduleBaseName = path.basename(modulePath).replace(/\.[^.]+$/, '');
const moduleName = sanitizePart(moduleBaseName);
const moduleFqnName = pathIdentityPart(path.basename(modulePath));
const packageName = ['js', ...dirParts].join('.');
const moduleFqn = `${packageName}.${moduleFqnName}`;
const moduleSignature = `${moduleFqn}.<init>()`;
const ANGULAR_DECORATORS = new Set(['Component', 'Directive', 'Injectable', 'NgModule', 'Pipe']);
const SOURCE_EXTENSIONS = ['.js', '.jsx', '.ts', '.tsx', '.mts', '.cts', '.mjs', '.cjs'];
const DECLARATION_EXTENSIONS = ['.d.ts', '.d.mts', '.d.cts'];
const declarationsByName = new Map();
const declarationsByOwnerAndName = new Map();
const staticDeclarationsByOwnerAndName = new Map();
const classesByName = new Map();
const callables = [];
const imports = collectImports(sourceFile);
const importedNames = collectImportedNames(sourceFile);
const importedNamespaces = collectImportedNamespaces(sourceFile);

write({
  record: 'module',
  moduleFqn,
  moduleName,
  packageName,
  modulePath,
  startLine: 1,
  endLine: sourceFile.getLineAndCharacterOfPosition(sourceFile.end).line + 1
});

sourceFile.statements.forEach(collectDeclaration);
sourceFile.statements.forEach(statement => {
  if (!isDeclarationWithOwnCallableScope(statement)) {
    collectCalls(statement, moduleSignature, moduleFqn, false);
  }
});
for (const item of callables) {
  for (const parameter of item.parameters || []) {
    if (parameter.initializer) {
      collectCalls(parameter.initializer, item.signature, item.ownerFqn, false);
    }
  }
  if (item.body) {
    collectCalls(item.body, item.signature, item.ownerFqn, true);
  }
}

function collectDeclaration(node) {
  if (ts.isClassDeclaration(node)) {
    const name = declarationName(node);
    if (name) {
      collectClass(node, name, declarationAliases(node, name));
    }
  } else if (ts.isInterfaceDeclaration(node)) {
    collectInterface(node, 'interface');
  } else if (ts.isTypeAliasDeclaration(node)) {
    collectInterface(node, 'type');
  } else if (ts.isEnumDeclaration(node)) {
    collectEnum(node);
  } else if (ts.isFunctionDeclaration(node)) {
    const name = declarationName(node);
    if (name && node.body) {
      collectFunction(moduleFqn, name, node, 'function', {
        aliases: declarationAliases(node, name)
      });
    }
  } else if (ts.isVariableStatement(node)) {
    collectVariables(node, moduleFqn);
  } else if (ts.isExportAssignment(node)) {
    collectExportAssignment(node);
  }
}

function collectClass(node, name, aliases = []) {
  const fqn = `${moduleFqn}.${name}`;
  classesByName.set(name, fqn);
  for (const alias of aliases) {
    classesByName.set(alias, fqn);
  }
  const framework = frameworkFor(node);
  const range = lineRange(node);
  const fieldInitializers = [];
  write({
    record: 'type',
    kind: 'class',
    fqn,
    name,
    framework,
    hasConstructor: node.members.some(member => ts.isConstructorDeclaration(member)),
    startLine: range.start,
    endLine: range.end
  });
  writeDecorators(node, 'fqn', fqn);
  for (const member of node.members) {
    if (ts.isConstructorDeclaration(member)) {
      collectFunction(fqn, '<init>', member, 'constructor');
    } else if (
      ts.isMethodDeclaration(member) ||
      ts.isGetAccessor(member) ||
      ts.isSetAccessor(member)
    ) {
      const memberName = memberNameText(member.name);
      if (memberName && member.body) {
        collectFunction(fqn, memberName, member, methodKind(member));
      }
    } else if (ts.isPropertyDeclaration(member)) {
      const nameText = memberNameText(member.name);
      if (nameText) {
        if (isFunctionInitializer(member.initializer)) {
          collectFunction(fqn, nameText, member.initializer, 'property-function', {
            decoratorNode: member,
            isStatic: hasStatic(member),
            rangeNode: member
          });
        } else if (member.initializer) {
          fieldInitializers.push({ initializer: member.initializer, isStatic: hasStatic(member) });
        }
        const fieldFqn = `${fqn}#${nameText}`;
        write({
          record: 'member',
          ownerFqn: fqn,
          memberType: 'field',
          kind: 'property',
          key: fieldFqn,
          name: nameText,
          dataType: typeText(member.type),
          isStatic: hasStatic(member),
          startLine: lineRange(member).start,
          endLine: lineRange(member).end
        });
        writeDecorators(member, 'fqn', fieldFqn);
      }
    }
  }
  for (const fieldInitializer of fieldInitializers) {
    const signatures = fieldInitializer.isStatic ? [moduleSignature] : constructorSignaturesFor(fqn);
    const ownerFqn = fieldInitializer.isStatic ? moduleFqn : fqn;
    for (const signature of signatures) {
      collectCalls(fieldInitializer.initializer, signature, ownerFqn, false);
    }
  }
}

function collectInterface(node, kind) {
  const name = node.name.text;
  write({
    record: 'type',
    kind,
    fqn: `${moduleFqn}.${name}`,
    name,
    framework: '',
    startLine: lineRange(node).start,
    endLine: lineRange(node).end
  });
}

function collectEnum(node) {
  const name = node.name.text;
  write({
    record: 'type',
    kind: 'class',
    fqn: `${moduleFqn}.${name}`,
    name,
    framework: '',
    startLine: lineRange(node).start,
    endLine: lineRange(node).end
  });
}

function collectVariables(node, ownerFqn) {
  for (const decl of node.declarationList.declarations) {
    if (!ts.isIdentifier(decl.name)) {
      continue;
    }
    const name = decl.name.text;
    if (isFunctionInitializer(decl.initializer)) {
      collectFunction(ownerFqn, name, decl.initializer, 'function');
    } else if (ts.isClassExpression(decl.initializer)) {
      collectClass(decl.initializer, name);
    } else {
      write({
        record: 'member',
        ownerFqn,
        memberType: 'field',
        kind: 'variable',
        key: `${ownerFqn}#${name}`,
        name,
        dataType: typeText(decl.type),
        isStatic: false,
        startLine: lineRange(decl).start,
        endLine: lineRange(decl).end
      });
    }
  }
}

function collectExportAssignment(node) {
  if (node.isExportEquals) {
    return;
  }
  const expression = unwrapExpression(node.expression);
  if (isFunctionInitializer(expression)) {
    collectFunction(moduleFqn, 'default', expression, 'function');
  } else if (ts.isClassExpression(expression)) {
    collectClass(expression, 'default');
  }
}

function collectFunction(ownerFqn, name, node, kind, options = {}) {
  if (!node.body) {
    return;
  }
  const signature = buildSignature(ownerFqn, name, node.parameters || []);
  const range = lineRange(options.rangeNode || node);
  const isStatic = options.isStatic ?? hasStatic(node);
  write({
    record: 'member',
    ownerFqn,
    memberType: 'method',
    kind,
    key: signature,
    name,
    dataType: typeText(node.type),
    isStatic,
    startLine: range.start,
    endLine: range.end
  });
  addDeclaration(name, signature);
  addScopedDeclaration(ownerFqn, name, signature, isStatic);
  for (const alias of options.aliases || []) {
    addDeclaration(alias, signature);
    addScopedDeclaration(ownerFqn, alias, signature, isStatic);
  }
  writeDecorators(options.decoratorNode || node, 'sig', signature);
  callables.push({
    signature,
    ownerFqn,
    body: node.body,
    parameters: Array.from(node.parameters || [])
  });
}

function collectCalls(node, callerSignature, callerOwnerFqn, includeNestedFunctionCalls) {
  if (!node) {
    return;
  }
  if (ts.isFunctionLike(node) && !includeNestedFunctionCalls) {
    return;
  }
  if (ts.isCallExpression(node)) {
    writeCall(callerSignature, calleeFor(node.expression, callerOwnerFqn));
  } else if (ts.isNewExpression(node)) {
    writeCall(callerSignature, constructorCalleeFor(node.expression));
  }
  ts.forEachChild(
    node,
    child => collectCalls(child, callerSignature, callerOwnerFqn, includeNestedFunctionCalls)
  );
}

function writeCall(callerSignature, callee) {
  if (!callee) {
    return;
  }
  if (typeof callee === 'string') {
    if (callee !== callerSignature) {
      write({ record: 'call', callerSignature, calleeSignature: callee });
    }
    return;
  }
  write({
    record: 'callByName',
    callerSignature,
    calleeOwnerFqn: callee.ownerFqn,
    calleeName: callee.name
  });
}

function constructorCalleeFor(expr) {
  const target = unwrapExpression(expr);
  if (!ts.isIdentifier(target)) {
    return null;
  }
  const ownerFqn = classesByName.get(target.text);
  if (!ownerFqn) {
    const imported = importedNames.get(target.text);
    return imported
      ? { ownerFqn: `${imported.moduleFqn}.${imported.importedName}`, name: '<init>' }
      : null;
  }
  const constructors = declarationsByOwnerAndName.get(`${ownerFqn}\u0000<init>`);
  if (!constructors) {
    return `${ownerFqn}.<init>()`;
  }
  return constructors.size === 1 ? constructors.values().next().value : null;
}

function calleeFor(expr, callerOwnerFqn) {
  if (ts.isIdentifier(expr)) {
    return uniqueDeclaration(expr.text) || importedCallable(expr.text);
  }
  if (ts.isPropertyAccessExpression(expr)) {
    const receiver = receiverOwner(expr.expression, callerOwnerFqn);
    if (!receiver) {
      return null;
    }
    if (receiver.byName) {
      return { ownerFqn: receiver.ownerFqn, name: expr.name.text };
    }
    return receiver.staticOnly
      ? uniqueScopedDeclaration(staticDeclarationsByOwnerAndName, receiver.ownerFqn, expr.name.text)
      : uniqueScopedDeclaration(declarationsByOwnerAndName, receiver.ownerFqn, expr.name.text);
  }
  return null;
}

function receiverOwner(expr, callerOwnerFqn) {
  const receiver = unwrapExpression(expr);
  if (receiver.kind === ts.SyntaxKind.ThisKeyword) {
    return callerOwnerFqn === moduleFqn ? null : { ownerFqn: callerOwnerFqn, staticOnly: false };
  }
  if (ts.isIdentifier(receiver)) {
    const namespaceOwner = importedNamespaces.get(receiver.text);
    if (namespaceOwner) {
      return { ownerFqn: namespaceOwner, byName: true };
    }
    const imported = importedNames.get(receiver.text);
    if (imported) {
      return { ownerFqn: `${imported.moduleFqn}.${imported.importedName}`, byName: true };
    }
    const ownerFqn = classesByName.get(receiver.text);
    return ownerFqn ? { ownerFqn, staticOnly: true } : null;
  }
  if (ts.isNewExpression(receiver)) {
    const target = unwrapExpression(receiver.expression);
    if (ts.isIdentifier(target)) {
      const ownerFqn = classesByName.get(target.text);
      if (ownerFqn) {
        return { ownerFqn, staticOnly: false };
      }
      const imported = importedNames.get(target.text);
      return imported ? { ownerFqn: `${imported.moduleFqn}.${imported.importedName}`, byName: true } : null;
    }
  }
  return null;
}

function importedCallable(name) {
  const imported = importedNames.get(name);
  return imported ? { ownerFqn: imported.moduleFqn, name: imported.importedName } : null;
}

function addDeclaration(name, signature) {
  if (!declarationsByName.has(name)) {
    declarationsByName.set(name, new Set());
  }
  declarationsByName.get(name).add(signature);
}

function addScopedDeclaration(ownerFqn, name, signature, isStatic) {
  addDeclarationTo(declarationsByOwnerAndName, ownerFqn, name, signature);
  if (isStatic) {
    addDeclarationTo(staticDeclarationsByOwnerAndName, ownerFqn, name, signature);
  }
}

function addDeclarationTo(target, ownerFqn, name, signature) {
  const key = `${ownerFqn}\u0000${name}`;
  if (!target.has(key)) {
    target.set(key, new Set());
  }
  target.get(key).add(signature);
}

function uniqueDeclaration(name) {
  const matches = declarationsByName.get(name);
  if (!matches || matches.size !== 1) {
    return null;
  }
  return matches.values().next().value;
}

function uniqueScopedDeclaration(target, ownerFqn, name) {
  const matches = target.get(`${ownerFqn}\u0000${name}`);
  if (!matches || matches.size !== 1) {
    return null;
  }
  return matches.values().next().value;
}

function constructorSignaturesFor(ownerFqn) {
  const constructors = declarationsByOwnerAndName.get(`${ownerFqn}\u0000<init>`);
  return constructors ? Array.from(constructors) : [`${ownerFqn}.<init>()`];
}

function writeDecorators(node, ownerKind, ownerKey) {
  for (const decorator of decoratorsOf(node)) {
    const name = decoratorName(decorator);
    if (!name) {
      continue;
    }
    const fqn = imports.get(name) || name;
    write({ record: 'annotation', ownerKind, ownerKey, fqn, name });
  }
}

function frameworkFor(node) {
  for (const decorator of decoratorsOf(node)) {
    const name = decoratorName(decorator);
    const fqn = name ? imports.get(name) || name : '';
    if (ANGULAR_DECORATORS.has(name) || fqn.startsWith('@angular/')) {
      return 'angular';
    }
  }
  return '';
}

function decoratorsOf(node) {
  if (typeof ts.canHaveDecorators === 'function' && ts.canHaveDecorators(node)) {
    return ts.getDecorators(node) || [];
  }
  return node.decorators ? Array.from(node.decorators) : [];
}

function decoratorName(decorator) {
  const expr = ts.isCallExpression(decorator.expression)
    ? decorator.expression.expression
    : decorator.expression;
  if (ts.isIdentifier(expr)) {
    return expr.text;
  }
  if (ts.isPropertyAccessExpression(expr)) {
    return expr.name.text;
  }
  return '';
}

function collectImports(sf) {
  const result = new Map();
  for (const statement of sf.statements) {
    if (!isNamedModuleImport(statement)) {
      continue;
    }
    const moduleNameText = statement.moduleSpecifier.text;
    const moduleFqn = localImportedModuleFqn(moduleNameText);
    const clause = statement.importClause;
    if (clause.name) {
      result.set(clause.name.text, importedSymbolFqn(moduleNameText, moduleFqn, 'default'));
    }
    const bindings = clause.namedBindings;
    if (bindings && ts.isNamedImports(bindings)) {
      for (const element of bindings.elements) {
        const imported = element.propertyName ? element.propertyName.text : element.name.text;
        result.set(element.name.text, importedSymbolFqn(moduleNameText, moduleFqn, imported));
      }
    }
  }
  return result;
}

function importedSymbolFqn(moduleSpecifier, moduleFqn, importedName) {
  return moduleFqn ? `${moduleFqn}.${importedName}` : `${moduleSpecifier}.${importedName}`;
}

function collectImportedNames(sf) {
  const result = new Map();
  for (const statement of sf.statements) {
    if (!isNamedModuleImport(statement)) {
      continue;
    }
    const moduleFqn = localImportedModuleFqn(statement.moduleSpecifier.text);
    if (!moduleFqn) {
      continue;
    }
    const clause = statement.importClause;
    if (clause.name) {
      result.set(clause.name.text, { moduleFqn, importedName: 'default' });
    }
    const bindings = clause.namedBindings;
    if (bindings && ts.isNamedImports(bindings)) {
      for (const element of bindings.elements) {
        const importedName = element.propertyName ? element.propertyName.text : element.name.text;
        result.set(element.name.text, { moduleFqn, importedName });
      }
    }
  }
  return result;
}

function collectImportedNamespaces(sf) {
  const result = new Map();
  for (const statement of sf.statements) {
    if (!isNamedModuleImport(statement)) {
      continue;
    }
    const moduleFqn = localImportedModuleFqn(statement.moduleSpecifier.text);
    const bindings = statement.importClause.namedBindings;
    if (moduleFqn && bindings && ts.isNamespaceImport(bindings)) {
      result.set(bindings.name.text, moduleFqn);
    }
  }
  return result;
}

function isNamedModuleImport(statement) {
  return ts.isImportDeclaration(statement) &&
    Boolean(statement.importClause) &&
    ts.isStringLiteral(statement.moduleSpecifier);
}

function buildSignature(ownerFqn, name, params) {
  return `${ownerFqn}.${name}(${Array.from(params).map(p => typeText(p.type)).join(', ')})`;
}

function typeText(typeNode) {
  return typeNode ? typeNode.getText(sourceFile).replace(/\s+/g, ' ') : 'any';
}

function lineRange(node) {
  const start = sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile)).line + 1;
  const end = sourceFile.getLineAndCharacterOfPosition(node.getEnd()).line + 1;
  return { start, end };
}

function memberNameText(name) {
  if (!name) {
    return '';
  }
  if (ts.isIdentifier(name) || ts.isPrivateIdentifier(name)) {
    return name.text;
  }
  if (ts.isStringLiteral(name) || ts.isNumericLiteral(name)) {
    return name.text;
  }
  return name.getText(sourceFile);
}

function methodKind(node) {
  if (ts.isGetAccessor(node)) {
    return 'getter';
  }
  if (ts.isSetAccessor(node)) {
    return 'setter';
  }
  return 'method';
}

function hasStatic(node) {
  return hasModifier(node, ts.SyntaxKind.StaticKeyword);
}

function isFunctionInitializer(initializer) {
  return Boolean(initializer) &&
    (ts.isArrowFunction(initializer) || ts.isFunctionExpression(initializer));
}

function unwrapExpression(expression) {
  let current = expression;
  while (ts.isParenthesizedExpression(current)) {
    current = current.expression;
  }
  return current;
}

function isDeclarationWithOwnCallableScope(node) {
  return ts.isFunctionDeclaration(node) || ts.isClassDeclaration(node);
}

function declarationName(node) {
  if (isDefaultExport(node)) {
    return 'default';
  }
  return node.name ? node.name.text : '';
}

function declarationAliases(node, exportedName) {
  return isDefaultExport(node) && node.name && node.name.text !== exportedName ? [node.name.text] : [];
}

function isDefaultExport(node) {
  return hasModifier(node, ts.SyntaxKind.DefaultKeyword) &&
    hasModifier(node, ts.SyntaxKind.ExportKeyword);
}

function hasModifier(node, kind) {
  const modifiers = typeof ts.canHaveModifiers === 'function' && ts.canHaveModifiers(node)
    ? ts.getModifiers(node) || []
    : node.modifiers || [];
  return Array.from(modifiers).some(modifier => modifier.kind === kind);
}

function scriptKind(name) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.tsx')) {
    return ts.ScriptKind.TSX;
  }
  if (lower.endsWith('.jsx')) {
    return ts.ScriptKind.JSX;
  }
  if (lower.endsWith('.ts') || lower.endsWith('.mts') || lower.endsWith('.cts')) {
    return ts.ScriptKind.TS;
  }
  return ts.ScriptKind.JS;
}

function sanitizePart(part) {
  const value = part.replace(/[^A-Za-z0-9_$]/g, '_').replace(/^([0-9])/, '_$1');
  return value || 'module';
}

function pathIdentityPart(part) {
  const encoded = Array.from(part).map(encodeIdentityChar).join('');
  if (!encoded) {
    return 'module';
  }
  return /^[A-Za-z_$]/.test(encoded) ? encoded : `_${encoded}`;
}

function encodeIdentityChar(char) {
  return /^[A-Za-z0-9]$/.test(char) ? char : `$${char.codePointAt(0).toString(16)}$`;
}

function localImportedModuleFqn(moduleSpecifier) {
  if (!moduleSpecifier.startsWith('.')) {
    return '';
  }
  const resolved = resolveLocalModulePath(moduleSpecifier);
  return resolved ? moduleFqnForModulePath(resolved) : '';
}

function resolveLocalModulePath(moduleSpecifier) {
  const importBase = path.resolve(root, moduleDir, moduleSpecifier);
  const candidates = [];
  if (path.extname(importBase)) {
    pushCandidate(candidates, importBase);
    for (const candidate of explicitSourceCandidates(importBase)) {
      pushCandidate(candidates, candidate);
    }
  } else {
    for (const extension of SOURCE_EXTENSIONS) {
      pushCandidate(candidates, importBase + extension);
    }
    for (const extension of SOURCE_EXTENSIONS) {
      pushCandidate(candidates, path.join(importBase, 'index' + extension));
    }
  }
  for (const candidate of candidates) {
    const relative = path.relative(root, candidate).replace(/\\/g, '/');
    if (relative.startsWith('..') || path.isAbsolute(relative)) {
      continue;
    }
    if (DECLARATION_EXTENSIONS.some(extension => relative.endsWith(extension))) {
      continue;
    }
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) {
      return relative;
    }
  }
  return '';
}

function explicitSourceCandidates(importBase) {
  const extension = path.extname(importBase).toLowerCase();
  const stem = importBase.slice(0, -extension.length);
  switch (extension) {
    case '.js':
      return [stem + '.ts', stem + '.tsx'];
    case '.jsx':
      return [stem + '.tsx'];
    case '.mjs':
      return [stem + '.mts'];
    case '.cjs':
      return [stem + '.cts'];
    default:
      return [];
  }
}

function pushCandidate(candidates, candidate) {
  if (!candidates.includes(candidate)) {
    candidates.push(candidate);
  }
}

function moduleFqnForModulePath(targetModulePath) {
  const targetDir = path.dirname(targetModulePath);
  const targetDirParts = targetDir === '.'
    ? []
    : targetDir.split('/').map(pathIdentityPart).filter(Boolean);
  return `${['js', ...targetDirParts].join('.')}.${pathIdentityPart(path.basename(targetModulePath))}`;
}

function argValue(name) {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : '';
}

function write(record) {
  process.stdout.write(`${JSON.stringify(record)}\n`);
}
