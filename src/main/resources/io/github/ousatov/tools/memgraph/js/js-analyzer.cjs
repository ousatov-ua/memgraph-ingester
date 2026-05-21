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
  : moduleDir.split('/').map(sanitizePart).filter(Boolean);
const moduleName = sanitizePart(path.basename(modulePath).replace(/\.[^.]+$/, ''));
const packageName = ['js', ...dirParts].join('.');
const moduleFqn = `${packageName}.${moduleName}`;
const moduleSignature = `${moduleFqn}.<init>()`;
const ANGULAR_DECORATORS = new Set(['Component', 'Directive', 'Injectable', 'NgModule', 'Pipe']);
const declarationsByName = new Map();
const callables = [];
const imports = collectImports(sourceFile);

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
    collectCalls(statement, moduleSignature);
  }
});
for (const item of callables) {
  if (item.body) {
    collectCalls(item.body, item.signature);
  }
}

function collectDeclaration(node) {
  if (ts.isClassDeclaration(node)) {
    const name = declarationName(node);
    if (name) {
      collectClass(node, name);
    }
  } else if (ts.isInterfaceDeclaration(node)) {
    collectInterface(node, 'interface');
  } else if (ts.isTypeAliasDeclaration(node)) {
    collectInterface(node, 'type');
  } else if (ts.isEnumDeclaration(node)) {
    collectEnum(node);
  } else if (ts.isFunctionDeclaration(node)) {
    const name = declarationName(node);
    if (name) {
      collectFunction(moduleFqn, name, node, 'function');
    }
  } else if (ts.isVariableStatement(node)) {
    collectVariables(node, moduleFqn);
  } else if (ts.isExportAssignment(node)) {
    collectExportAssignment(node);
  }
}

function collectClass(node, name) {
  const fqn = `${moduleFqn}.${name}`;
  const framework = frameworkFor(node);
  const range = lineRange(node);
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
      if (memberName) {
        collectFunction(fqn, memberName, member, methodKind(member));
      }
    } else if (ts.isPropertyDeclaration(member)) {
      const nameText = memberNameText(member.name);
      if (nameText) {
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

function collectFunction(ownerFqn, name, node, kind) {
  const signature = buildSignature(ownerFqn, name, node.parameters || []);
  const range = lineRange(node);
  write({
    record: 'member',
    ownerFqn,
    memberType: 'method',
    kind,
    key: signature,
    name,
    dataType: typeText(node.type),
    isStatic: hasStatic(node),
    startLine: range.start,
    endLine: range.end
  });
  addDeclaration(name, signature);
  writeDecorators(node, 'sig', signature);
  callables.push({ signature, body: node.body });
}

function collectCalls(node, callerSignature) {
  if (!node) {
    return;
  }
  if (ts.isFunctionLike(node)) {
    return;
  }
  if (ts.isCallExpression(node)) {
    const calleeSignature = calleeFor(node.expression);
    if (calleeSignature && calleeSignature !== callerSignature) {
      write({ record: 'call', callerSignature, calleeSignature });
    }
  }
  ts.forEachChild(node, child => collectCalls(child, callerSignature));
}

function calleeFor(expr) {
  if (ts.isIdentifier(expr)) {
    return uniqueDeclaration(expr.text);
  }
  if (ts.isPropertyAccessExpression(expr)) {
    return uniqueDeclaration(expr.name.text);
  }
  return null;
}

function addDeclaration(name, signature) {
  if (!declarationsByName.has(name)) {
    declarationsByName.set(name, new Set());
  }
  declarationsByName.get(name).add(signature);
}

function uniqueDeclaration(name) {
  const matches = declarationsByName.get(name);
  if (!matches || matches.size !== 1) {
    return null;
  }
  return matches.values().next().value;
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
    const clause = statement.importClause;
    if (clause.name) {
      result.set(clause.name.text, `${moduleNameText}.default`);
    }
    const bindings = clause.namedBindings;
    if (bindings && ts.isNamedImports(bindings)) {
      for (const element of bindings.elements) {
        const imported = element.propertyName ? element.propertyName.text : element.name.text;
        result.set(element.name.text, `${moduleNameText}.${imported}`);
      }
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
  if (node.name) {
    return node.name.text;
  }
  return hasModifier(node, ts.SyntaxKind.DefaultKeyword) &&
    hasModifier(node, ts.SyntaxKind.ExportKeyword)
    ? 'default'
    : '';
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

function argValue(name) {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : '';
}

function write(record) {
  process.stdout.write(`${JSON.stringify(record)}\n`);
}
