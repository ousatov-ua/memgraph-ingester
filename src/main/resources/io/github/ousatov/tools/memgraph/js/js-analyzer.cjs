#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const ts = require('typescript');
const { createAstHelpers } = require('./js-analyzer-ast.cjs');
const { createPathContext, scriptKind } = require('./js-analyzer-paths.cjs');

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

const pathContext = createPathContext(root, file);
const {
  modulePath,
  moduleName,
  packageName,
  moduleFqn,
  localImportedModuleFqn,
  resolveLocalModulePath,
  moduleFqnForModulePath
} = pathContext;
const {
  isNamedModuleImport,
  buildSignature,
  buildSignatureFromTypes,
  typeText,
  lineRange,
  memberNameText,
  indexSignatureName,
  methodKind,
  shouldEmitBodylessMethod,
  classMethodSignatureKind,
  classFieldKind,
  hasStatic,
  isFunctionInitializer,
  unwrapExpression,
  expressionNameParts,
  isDeclarationWithOwnCallableScope,
  declarationName,
  declarationAliases,
  isDefaultExport,
  hasModifier
} = createAstHelpers(ts, sourceFile);
const moduleSignature = `${moduleFqn}.<init>()`;
const ANGULAR_DECORATORS = new Set(['Component', 'Directive', 'Injectable', 'NgModule', 'Pipe']);
const typeFqnByNode = new WeakMap();
const typeNameByNode = new WeakMap();
const localTypeScopesByNode = new WeakMap();
const callableByNode = new WeakMap();
const declarationsByName = new Map();
const declarationsByOwnerAndName = new Map();
const staticDeclarationsByOwnerAndName = new Map();
const instancePropertyTypesByOwnerAndName = new Map();
const classSelfNamesByOwnerFqn = new Map();
const classesByName = new Map();
const callables = [];
const SHADOWED_CLASS_SELF = Symbol('shadowedClassSelf');
const imports = collectImports(sourceFile);
const importedNames = collectImportedNames(sourceFile);
const importedNamespaces = collectImportedNamespaces(sourceFile);
const namespaceImports = collectNamespaceImports(sourceFile);
const localTypeFqnsByName = new Map();
collectLocalTypeMetadata(sourceFile);
const topLevelFunctionsByName = new Map();
const topLevelClassesByName = new Map();
const emittedExportAliases = new Set();
const exportModelsByModulePath = new Map();

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
sourceFile.statements.forEach(collectExportBinding);
sourceFile.statements.forEach(collectNestedTypeDeclarations);
sourceFile.statements.forEach(statement => {
  if (!isDeclarationWithOwnCallableScope(statement)) {
    collectCalls(statement, moduleSignature, moduleFqn, true);
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
    collectTypeAlias(node);
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
  }
}

function collectNestedTypeDeclarations(node) {
  visitNestedTypeDeclaration(node, moduleSignature, moduleFqn, null, null, true);
}

function visitNestedTypeDeclaration(
  node,
  callerSignature,
  callerOwnerFqn,
  parent,
  grandparent,
  collectStaticInitializerCalls
) {
  const callable = callableByNode.get(node);
  const unevaluatedFunctionScope =
    parent &&
    ts.isFunctionLike(node) &&
    !callable &&
    !shouldTraverseNestedFunction(node, true, parent, grandparent);
  const nextCallerSignature = callable ? callable.signature : callerSignature;
  const nextCallerOwnerFqn = callable ? callable.ownerFqn : callerOwnerFqn;
  const nextCollectStaticInitializerCalls =
    collectStaticInitializerCalls && !unevaluatedFunctionScope;
  if (ts.isClassDeclaration(node)) {
    if (!isTopLevelDeclaration(node)) {
      const name = declarationName(node);
      if (name) {
        collectClass(node, name, declarationAliases(node, name), {
          initializerCallerOwnerFqn: callerOwnerFqn,
          initializerCallerSignature: callerSignature,
          skipStaticInitializerCalls: !collectStaticInitializerCalls,
          skipClassNameLookup: true,
          skipMemberUnscopedLookup: true,
          skipExportModel: true
        });
      }
    }
  } else if (ts.isInterfaceDeclaration(node)) {
    if (!isTopLevelDeclaration(node)) {
      collectInterface(node, 'interface');
    }
  } else if (ts.isTypeAliasDeclaration(node)) {
    if (!isTopLevelDeclaration(node)) {
      collectTypeAlias(node);
    }
  } else if (ts.isEnumDeclaration(node)) {
    if (!isTopLevelDeclaration(node)) {
      collectEnum(node);
    }
  } else if (
    ts.isVariableDeclaration(node) &&
    !isTopLevelVariableDeclaration(node) &&
    ts.isIdentifier(node.name)
  ) {
    const classExpression = classExpressionInitializer(node.initializer);
    if (classExpression) {
      collectClass(classExpression, node.name.text, [], {
        initializerCallerOwnerFqn: callerOwnerFqn,
        initializerCallerSignature: callerSignature,
        skipStaticInitializerCalls: !collectStaticInitializerCalls,
        skipClassNameLookup: true,
        skipMemberUnscopedLookup: true,
        skipExportModel: true
      });
    }
  } else if (shouldCollectNamedClassExpression(node)) {
    collectClass(node, node.name.text, [], {
      initializerCallerOwnerFqn: callerOwnerFqn,
      initializerCallerSignature: callerSignature,
      skipStaticInitializerCalls: !collectStaticInitializerCalls,
      skipClassNameLookup: true,
      skipMemberUnscopedLookup: true,
      skipExportModel: true
    });
  }
  ts.forEachChild(node, child =>
    visitNestedTypeDeclaration(
      child,
      nextCallerSignature,
      nextCallerOwnerFqn,
      node,
      parent,
      nextCollectStaticInitializerCalls
    )
  );
}

function collectClass(node, name, aliases = [], options = {}) {
  const fqn = typeFqnFor(node, name);
  if (!options.skipClassNameLookup) {
    registerUniqueName(classesByName, name, fqn);
    for (const alias of aliases) {
      registerUniqueName(classesByName, alias, fqn);
    }
  }
  if (!options.skipExportModel) {
    const model = { node };
    topLevelClassesByName.set(name, model);
    for (const alias of aliases) {
      topLevelClassesByName.set(alias, model);
    }
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
    isAbstract: hasModifier(node, ts.SyntaxKind.AbstractKeyword),
    startLine: range.start,
    endLine: range.end
  });
  writeDecorators(node, 'fqn', fqn);
  writeHeritageRelations(node, fqn, 'class');
  registerInstancePropertyTypes(fqn, node);
  for (const member of node.members) {
    if (ts.isConstructorDeclaration(member)) {
      if (member.body) {
        collectFunction(fqn, '<init>', member, 'constructor', {
          skipUnscopedLookup: options.skipMemberUnscopedLookup
        });
      }
    } else if (
      ts.isMethodDeclaration(member) ||
      ts.isGetAccessor(member) ||
      ts.isSetAccessor(member)
    ) {
      const memberName = memberNameText(member.name);
      if (memberName && member.body) {
        collectFunction(fqn, memberName, member, methodKind(member), {
          skipUnscopedLookup: options.skipMemberUnscopedLookup
        });
      } else if (memberName && shouldEmitBodylessMethod(member)) {
        writeFunctionSignature(fqn, memberName, member, classMethodSignatureKind(member), {
          isStatic: hasStatic(member),
          skipUnscopedLookup: true
        });
      }
    } else if (ts.isPropertyDeclaration(member)) {
      const nameText = memberNameText(member.name);
      if (nameText) {
        if (isFunctionInitializer(member.initializer)) {
          collectFunction(fqn, nameText, member.initializer, 'property-function', {
            decoratorNode: member,
            isStatic: hasStatic(member),
            rangeNode: member,
            skipUnscopedLookup: options.skipMemberUnscopedLookup
          });
        } else if (member.initializer) {
          fieldInitializers.push({ initializer: member.initializer, isStatic: hasStatic(member) });
        }
        writeField(
          fqn,
          nameText,
          typeText(member.type),
          classFieldKind(member),
          member,
          hasStatic(member)
        );
        const fieldFqn = `${fqn}#${nameText}`;
        writeDecorators(member, 'fqn', fieldFqn);
      }
    } else if (ts.isClassStaticBlockDeclaration(member) && !options.skipStaticInitializerCalls) {
      collectCalls(
        member.body,
        options.initializerCallerSignature || moduleSignature,
        options.initializerCallerOwnerFqn || moduleFqn,
        true
      );
    }
  }
  for (const fieldInitializer of fieldInitializers) {
    if (fieldInitializer.isStatic && options.skipStaticInitializerCalls) {
      continue;
    }
    const signatures = fieldInitializer.isStatic
      ? [options.initializerCallerSignature || moduleSignature]
      : constructorSignaturesFor(fqn);
    const ownerFqn = fieldInitializer.isStatic
      ? (options.initializerCallerOwnerFqn || moduleFqn)
      : fqn;
    for (const signature of signatures) {
      collectCalls(fieldInitializer.initializer, signature, ownerFqn, false);
    }
  }
}

function collectInterface(node, kind) {
  const name = node.name.text;
  const fqn = typeFqnFor(node, name);
  write({
    record: 'type',
    kind,
    fqn,
    name,
    framework: '',
    isAbstract: false,
    startLine: lineRange(node).start,
    endLine: lineRange(node).end
  });
  writeHeritageRelations(node, fqn, 'interface');
  writeTypeMembers(fqn, node.members);
}

function collectTypeAlias(node) {
  collectInterface(node, 'type');
  if (ts.isTypeLiteralNode(node.type)) {
    writeTypeMembers(typeFqnFor(node, node.name.text), node.type.members);
  }
}

function collectEnum(node) {
  const name = node.name.text;
  const fqn = typeFqnFor(node, name);
  write({
    record: 'type',
    kind: 'enum',
    fqn,
    name,
    framework: '',
    isAbstract: false,
    startLine: lineRange(node).start,
    endLine: lineRange(node).end
  });
  for (const member of node.members) {
    const nameText = memberNameText(member.name);
    if (nameText) {
      writeField(fqn, nameText, fqn, 'enum-member', member, true);
    }
  }
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
      const classExpression = classExpressionInitializer(decl.initializer);
      if (classExpression) {
        collectClass(classExpression, name);
      } else {
        writeField(ownerFqn, name, typeText(decl.type), 'variable', decl, false);
      }
    }
  }
}

function collectExportBinding(node) {
  if (ts.isExportAssignment(node)) {
    collectExportAssignment(node);
  } else if (ts.isExportDeclaration(node)) {
    collectExportDeclarationAliases(node);
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
  } else if (ts.isIdentifier(expression)) {
    collectExportAlias(expression.text, 'default');
  }
}

function collectExportDeclarationAliases(node) {
  if (!node.exportClause || !ts.isNamedExports(node.exportClause)) {
    return;
  }
  for (const element of node.exportClause.elements) {
    const localName = element.propertyName ? element.propertyName.text : element.name.text;
    if (node.moduleSpecifier && ts.isStringLiteral(node.moduleSpecifier)) {
      collectReExportAlias(node.moduleSpecifier.text, localName, element.name.text, element);
    } else {
      collectExportAlias(localName, element.name.text);
    }
  }
}

function collectReExportAlias(moduleSpecifier, importedName, exportedName, element) {
  const targetModulePath = resolveLocalModulePath(moduleSpecifier);
  const targetModuleFqn = targetModulePath ? moduleFqnForModulePath(targetModulePath) : '';
  if (!targetModuleFqn || !importedName || !exportedName) {
    return;
  }
  const key = `${moduleSpecifier}\u0000${importedName}\u0000${exportedName}`;
  if (emittedExportAliases.has(key)) {
    return;
  }
  emittedExportAliases.add(key);
  const classModel = exportedClassModel(targetModulePath, importedName);
  if (classModel) {
    writeReExportClassAlias(exportedName, element, classModel);
    return;
  }
  const signature = buildSignature(moduleFqn, exportedName, []);
  const range = lineRange(element);
  write({
    record: 'member',
    ownerFqn: moduleFqn,
    memberType: 'method',
    kind: 'reexport',
    key: signature,
    name: exportedName,
    dataType: 'any',
    isStatic: false,
    startLine: range.start,
    endLine: range.end
  });
  addScopedDeclaration(moduleFqn, exportedName, signature, false);
  writeCall(signature, { ownerFqn: targetModuleFqn, name: importedName });
}

function writeReExportClassAlias(exportedName, element, targetModel) {
  const aliasFqn = `${moduleFqn}.${exportedName}`;
  const range = lineRange(element);
  write({
    record: 'type',
    kind: 'class',
    fqn: aliasFqn,
    name: exportedName,
    framework: '',
    hasConstructor: targetModel.constructors.length > 0,
    startLine: range.start,
    endLine: range.end
  });
  if (targetModel.constructors.length === 0) {
    writeCall(`${aliasFqn}.<init>()`, { ownerFqn: targetModel.fqn, name: '<init>' });
    return;
  }
  for (const constructor of targetModel.constructors) {
    const signature = buildSignatureFromTypes(aliasFqn, '<init>', constructor.paramTypes);
    write({
      record: 'member',
      ownerFqn: aliasFqn,
      memberType: 'method',
      kind: 'reexport-constructor',
      key: signature,
      name: '<init>',
      dataType: 'any',
      isStatic: false,
      startLine: range.start,
      endLine: range.end
    });
    addScopedDeclaration(aliasFqn, '<init>', signature, false);
    writeCall(signature, { ownerFqn: targetModel.fqn, name: '<init>' });
  }
}

function exportedClassModel(targetModulePath, exportedName, seen = new Set()) {
  if (!targetModulePath || !exportedName || seen.has(targetModulePath)) {
    return null;
  }
  return exportedClassModels(targetModulePath, seen).get(exportedName) || null;
}

function exportedClassModels(targetModulePath, seen = new Set()) {
  if (exportModelsByModulePath.has(targetModulePath)) {
    return exportModelsByModulePath.get(targetModulePath);
  }
  if (seen.has(targetModulePath)) {
    return new Map();
  }
  seen.add(targetModulePath);
  const exported = new Map();
  const targetModuleFqn = moduleFqnForModulePath(targetModulePath);
  let targetSourceFile;
  try {
    const targetFile = path.resolve(root, targetModulePath);
    targetSourceFile = ts.createSourceFile(
      targetFile,
      fs.readFileSync(targetFile, 'utf8'),
      ts.ScriptTarget.Latest,
      true,
      scriptKind(targetFile)
    );
  } catch (_) {
    exportModelsByModulePath.set(targetModulePath, exported);
    seen.delete(targetModulePath);
    return exported;
  }

  const localClassShapes = new Map();
  for (const statement of targetSourceFile.statements) {
    if (ts.isClassDeclaration(statement) && statement.name) {
      localClassShapes.set(statement.name.text, classShape(statement, targetSourceFile));
    } else if (ts.isVariableStatement(statement)) {
      collectLocalClassExpressionShapes(statement, targetSourceFile, localClassShapes);
    }
  }

  for (const statement of targetSourceFile.statements) {
    if (ts.isClassDeclaration(statement)) {
      collectExportedClassDeclaration(statement, targetModuleFqn, targetSourceFile, exported);
    } else if (ts.isVariableStatement(statement) && hasModifier(statement, ts.SyntaxKind.ExportKeyword)) {
      collectExportedClassExpressionShapes(statement, targetModuleFqn, targetSourceFile, exported);
    } else if (ts.isExportAssignment(statement)) {
      collectExportedClassAssignment(statement, targetModuleFqn, targetSourceFile, localClassShapes, exported);
    } else if (ts.isExportDeclaration(statement)) {
      collectExportedClassAliases(statement, targetModulePath, targetModuleFqn, localClassShapes, exported, seen);
    }
  }

  exportModelsByModulePath.set(targetModulePath, exported);
  seen.delete(targetModulePath);
  return exported;
}

function collectLocalClassExpressionShapes(statement, sf, target) {
  for (const decl of statement.declarationList.declarations) {
    const classExpression = classExpressionInitializer(decl.initializer);
    if (ts.isIdentifier(decl.name) && classExpression) {
      target.set(decl.name.text, classShape(classExpression, sf));
    }
  }
}

function collectExportedClassDeclaration(statement, targetModuleFqn, sf, exported) {
  if (isDefaultExport(statement)) {
    exported.set('default', classModel(targetModuleFqn, 'default', classShape(statement, sf)));
    return;
  }
  if (statement.name && hasModifier(statement, ts.SyntaxKind.ExportKeyword)) {
    const name = statement.name.text;
    exported.set(name, classModel(targetModuleFqn, name, classShape(statement, sf)));
  }
}

function collectExportedClassExpressionShapes(statement, targetModuleFqn, sf, exported) {
  for (const decl of statement.declarationList.declarations) {
    const classExpression = classExpressionInitializer(decl.initializer);
    if (ts.isIdentifier(decl.name) && classExpression) {
      const name = decl.name.text;
      exported.set(name, classModel(targetModuleFqn, name, classShape(classExpression, sf)));
    }
  }
}

function collectExportedClassAssignment(statement, targetModuleFqn, sf, localClassShapes, exported) {
  if (statement.isExportEquals) {
    return;
  }
  const expression = unwrapExpression(statement.expression);
  if (ts.isClassExpression(expression)) {
    exported.set('default', classModel(targetModuleFqn, 'default', classShape(expression, sf)));
  } else if (ts.isIdentifier(expression)) {
    const shape = localClassShapes.get(expression.text);
    if (shape) {
      exported.set('default', classModel(targetModuleFqn, 'default', shape));
    }
  }
}

function collectExportedClassAliases(
  statement,
  targetModulePath,
  targetModuleFqn,
  localClassShapes,
  exported,
  seen
) {
  if (!statement.exportClause || !ts.isNamedExports(statement.exportClause)) {
    return;
  }
  for (const element of statement.exportClause.elements) {
    const localName = element.propertyName ? element.propertyName.text : element.name.text;
    const exportedName = element.name.text;
    if (statement.moduleSpecifier && ts.isStringLiteral(statement.moduleSpecifier)) {
      const nestedPath = resolveLocalModulePath(
        statement.moduleSpecifier.text,
        path.dirname(targetModulePath)
      );
      const nestedModel = exportedClassModel(nestedPath, localName, seen);
      if (nestedModel) {
        exported.set(exportedName, classModel(targetModuleFqn, exportedName, nestedModel));
      }
      continue;
    }
    const shape = localClassShapes.get(localName);
    if (shape) {
      exported.set(exportedName, classModel(targetModuleFqn, exportedName, shape));
    }
  }
}

function classShape(node, sf) {
  const constructors = [];
  for (const member of node.members || []) {
    if (ts.isConstructorDeclaration(member) && member.body) {
      constructors.push({
        paramTypes: Array.from(member.parameters || []).map(parameter => typeText(parameter.type, sf))
      });
    }
  }
  return { constructors };
}

function classModel(targetModuleFqn, exportedName, shape) {
  return {
    fqn: `${targetModuleFqn}.${exportedName}`,
    constructors: shape.constructors
  };
}

function collectExportAlias(localName, exportedName) {
  if (!localName || !exportedName || localName === exportedName) {
    return;
  }
  const key = `${localName}\u0000${exportedName}`;
  if (emittedExportAliases.has(key)) {
    return;
  }
  const fn = topLevelFunctionsByName.get(localName);
  if (fn) {
    emittedExportAliases.add(key);
    collectFunction(moduleFqn, exportedName, fn.node, fn.kind, {
      decoratorNode: fn.decoratorNode,
      isStatic: fn.isStatic,
      rangeNode: fn.rangeNode,
      skipExportModel: true,
      skipUnscopedLookup: true
    });
    return;
  }
  const cls = topLevelClassesByName.get(localName);
  if (cls) {
    emittedExportAliases.add(key);
    collectClass(cls.node, exportedName, [], {
      skipClassNameLookup: true,
      skipExportModel: true
    });
  }
}

function collectFunction(ownerFqn, name, node, kind, options = {}) {
  if (!node.body) {
    return;
  }
  const signature = buildSignature(ownerFqn, name, node.parameters || []);
  if (!callableByNode.has(node)) {
    callableByNode.set(node, { signature, ownerFqn });
  }
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
  if (ownerFqn === moduleFqn && !options.skipExportModel) {
    const model = {
      node,
      kind,
      decoratorNode: options.decoratorNode,
      isStatic,
      rangeNode: options.rangeNode
    };
    topLevelFunctionsByName.set(name, model);
    for (const alias of options.aliases || []) {
      topLevelFunctionsByName.set(alias, model);
    }
  }
  if (!options.skipUnscopedLookup) {
    addDeclaration(name, signature);
  }
  addScopedDeclaration(ownerFqn, name, signature, isStatic);
  for (const alias of options.aliases || []) {
    if (!options.skipUnscopedLookup) {
      addDeclaration(alias, signature);
    }
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

function writeFunctionSignature(ownerFqn, name, node, kind, options = {}) {
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
    dataType: name === '<init>' ? 'void' : typeText(node.type),
    isStatic,
    startLine: range.start,
    endLine: range.end
  });
  if (!options.skipUnscopedLookup) {
    addDeclaration(name, signature);
  }
  addScopedDeclaration(ownerFqn, name, signature, isStatic);
}

function writeField(ownerFqn, name, dataType, kind, node, isStatic) {
  const range = lineRange(node);
  write({
    record: 'member',
    ownerFqn,
    memberType: 'field',
    kind,
    key: `${ownerFqn}#${name}`,
    name,
    dataType,
    isStatic,
    startLine: range.start,
    endLine: range.end
  });
}

function writeTypeMembers(ownerFqn, members) {
  for (const member of members || []) {
    if (ts.isPropertySignature(member)) {
      const name = memberNameText(member.name);
      if (name) {
        writeField(
          ownerFqn,
          name,
          typeText(member.type),
          member.questionToken ? 'optional-interface-property' : 'interface-property',
          member,
          false
        );
      }
    } else if (ts.isMethodSignature(member)) {
      const name = memberNameText(member.name);
      if (name) {
        writeFunctionSignature(
          ownerFqn,
          name,
          member,
          member.questionToken ? 'optional-interface-method' : 'interface-method',
          { skipUnscopedLookup: true }
        );
      }
    } else if (ts.isIndexSignatureDeclaration(member)) {
      writeField(
        ownerFqn,
        indexSignatureName(member),
        typeText(member.type),
        'index-signature',
        member,
        false
      );
    } else if (ts.isCallSignatureDeclaration(member)) {
      writeFunctionSignature(ownerFqn, '<call>', member, 'call-signature', {
        skipUnscopedLookup: true
      });
    } else if (ts.isConstructSignatureDeclaration(member)) {
      writeFunctionSignature(ownerFqn, '<init>', member, 'construct-signature', {
        skipUnscopedLookup: true
      });
    }
  }
}

function writeHeritageRelations(node, childFqn, ownerKind) {
  for (const clause of node.heritageClauses || []) {
    const relationKind = relationKindFor(ownerKind, clause.token);
    if (!relationKind) {
      continue;
    }
    for (const typeNode of clause.types || []) {
      const targetFqn = typeReferenceFqn(typeNode.expression, node);
      if (targetFqn) {
        write({ record: 'relation', kind: relationKind, childFqn, targetFqn });
      }
    }
  }
}

function relationKindFor(ownerKind, token) {
  if (ownerKind === 'class' && token === ts.SyntaxKind.ExtendsKeyword) {
    return 'classExtends';
  }
  if (ownerKind === 'class' && token === ts.SyntaxKind.ImplementsKeyword) {
    return 'implements';
  }
  if (ownerKind === 'interface' && token === ts.SyntaxKind.ExtendsKeyword) {
    return 'interfaceExtends';
  }
  return '';
}

function collectCalls(
  node,
  callerSignature,
  callerOwnerFqn,
  includeNestedFunctionCalls,
  parent = null,
  grandparent = null
) {
  if (!node) {
    return;
  }
  if (parent && (ts.isClassDeclaration(node) || emittedClassExpression(node))) {
    return;
  }
  if (
    ts.isFunctionLike(node) &&
    parent &&
    !shouldTraverseNestedFunction(node, includeNestedFunctionCalls, parent, grandparent)
  ) {
    return;
  }
  if (ts.isCallExpression(node)) {
    writeCall(callerSignature, calleeFor(node.expression, callerOwnerFqn));
  } else if (ts.isNewExpression(node)) {
    writeCall(callerSignature, constructorCalleeFor(node.expression, callerOwnerFqn));
  }
  ts.forEachChild(
    node,
    child => collectCalls(
      child,
      callerSignature,
      callerOwnerFqn,
      includeNestedFunctionCalls,
      node,
      parent
    )
  );
}

function shouldTraverseNestedFunction(node, includeNestedFunctionCalls, parent, grandparent) {
  if (!includeNestedFunctionCalls) {
    return false;
  }
  return isCallArgument(node, parent) ||
    (ts.isParenthesizedExpression(parent) && isCallArgument(parent, grandparent)) ||
    isImmediatelyInvoked(node, parent) ||
    (ts.isParenthesizedExpression(parent) && isImmediatelyInvoked(parent, grandparent));
}

function isCallArgument(candidate, parent) {
  if (!parent || !(ts.isCallExpression(parent) || ts.isNewExpression(parent)) || !parent.arguments) {
    return false;
  }
  const unwrapped = unwrapExpression(candidate);
  return Array.from(parent.arguments).some(argument => unwrapExpression(argument) === unwrapped);
}

function isImmediatelyInvoked(candidate, parent) {
  return Boolean(parent) &&
    ts.isCallExpression(parent) &&
    unwrapExpression(parent.expression) === unwrapExpression(candidate);
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

function constructorCalleeFor(expr, callerOwnerFqn) {
  const target = unwrapExpression(expr);
  const namespaceOwner = namespaceQualifiedOwner(target);
  if (namespaceOwner) {
    return { ownerFqn: namespaceOwner, name: '<init>' };
  }
  if (!ts.isIdentifier(target)) {
    return null;
  }
  const classSelfFqn = classSelfTypeFqn(callerOwnerFqn, target.text, target);
  if (classSelfFqn === SHADOWED_CLASS_SELF) {
    return null;
  }
  const ownerFqn = classSelfFqn || classesByName.get(target.text);
  if (!ownerFqn) {
    const constructorFunction = uniqueDeclaration(target.text);
    if (constructorFunction) {
      return constructorFunction;
    }
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
  const namespaceOwner = namespaceQualifiedOwner(receiver);
  if (namespaceOwner) {
    return { ownerFqn: namespaceOwner, byName: true };
  }
  if (ts.isPropertyAccessExpression(receiver)) {
    const ownerFqn = instancePropertyReceiverOwner(receiver, callerOwnerFqn);
    if (ownerFqn) {
      return { ownerFqn, byName: true };
    }
  }
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
    const classSelfFqn = classSelfTypeFqn(callerOwnerFqn, receiver.text, receiver);
    if (classSelfFqn === SHADOWED_CLASS_SELF) {
      return null;
    }
    const ownerFqn = classSelfFqn || classesByName.get(receiver.text);
    return ownerFqn ? { ownerFqn, staticOnly: true } : null;
  }
  if (ts.isNewExpression(receiver)) {
    const target = unwrapExpression(receiver.expression);
    if (ts.isIdentifier(target)) {
      const classSelfFqn = classSelfTypeFqn(callerOwnerFqn, target.text, target);
      if (classSelfFqn === SHADOWED_CLASS_SELF) {
        return null;
      }
      const ownerFqn = classSelfFqn || classesByName.get(target.text);
      if (ownerFqn) {
        return { ownerFqn, staticOnly: false };
      }
      const imported = importedNames.get(target.text);
      return imported ? { ownerFqn: `${imported.moduleFqn}.${imported.importedName}`, byName: true } : null;
    }
  }
  return null;
}

function instancePropertyReceiverOwner(receiver, callerOwnerFqn) {
  if (callerOwnerFqn === moduleFqn || unwrapExpression(receiver.expression).kind !== ts.SyntaxKind.ThisKeyword) {
    return '';
  }
  return instancePropertyTypesByOwnerAndName.get(`${callerOwnerFqn}\u0000${receiver.name.text}`) || '';
}

function namespaceQualifiedOwner(expr) {
  const parts = expressionNameParts(expr);
  if (parts.length < 2) {
    return '';
  }
  const namespaceOwner = importedNamespaces.get(parts[0]);
  return namespaceOwner ? [namespaceOwner, ...parts.slice(1)].join('.') : '';
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
    const { name, fqn } = decoratorReference(decorator);
    if (!name) {
      continue;
    }
    write({ record: 'annotation', ownerKind, ownerKey, fqn, name });
  }
}

function frameworkFor(node) {
  for (const decorator of decoratorsOf(node)) {
    const { name, fqn } = decoratorReference(decorator);
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
  return decoratorReference(decorator).name;
}

function decoratorReference(decorator) {
  const expr = ts.isCallExpression(decorator.expression)
    ? decorator.expression.expression
    : decorator.expression;
  const parts = expressionNameParts(expr);
  if (parts.length === 0) {
    return { name: '', fqn: '' };
  }
  const name = parts[parts.length - 1];
  if (parts.length === 1) {
    return { name, fqn: imports.get(name) || name };
  }
  const namespaceOwner = namespaceImports.get(parts[0]);
  const fqn = namespaceOwner ? [namespaceOwner, ...parts.slice(1)].join('.') : parts.join('.');
  return { name, fqn };
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

function collectNamespaceImports(sf) {
  const result = new Map();
  for (const statement of sf.statements) {
    if (!isNamedModuleImport(statement)) {
      continue;
    }
    const moduleNameText = statement.moduleSpecifier.text;
    const bindings = statement.importClause.namedBindings;
    if (bindings && ts.isNamespaceImport(bindings)) {
      result.set(bindings.name.text, localImportedModuleFqn(moduleNameText) || moduleNameText);
    }
  }
  return result;
}

function registerInstancePropertyTypes(ownerFqn, node) {
  for (const member of node.members || []) {
    if (
      ts.isPropertyDeclaration(member) &&
      !hasStatic(member) &&
      member.name &&
      ts.isIdentifier(member.name)
    ) {
      registerInstancePropertyType(ownerFqn, member.name.text, member.type, member);
    } else if (ts.isConstructorDeclaration(member)) {
      for (const parameter of member.parameters || []) {
        if (isConstructorParameterProperty(parameter) && ts.isIdentifier(parameter.name)) {
          registerInstancePropertyType(ownerFqn, parameter.name.text, parameter.type, parameter);
        }
      }
    }
  }
}

function registerInstancePropertyType(ownerFqn, name, typeNode, contextNode) {
  const targetFqn = typeNodeReferenceFqn(typeNode, contextNode);
  if (isInternalTypeFqn(targetFqn)) {
    instancePropertyTypesByOwnerAndName.set(`${ownerFqn}\u0000${name}`, targetFqn);
  }
}

function isConstructorParameterProperty(parameter) {
  return hasModifier(parameter, ts.SyntaxKind.PrivateKeyword) ||
    hasModifier(parameter, ts.SyntaxKind.PublicKeyword) ||
    hasModifier(parameter, ts.SyntaxKind.ProtectedKeyword) ||
    hasModifier(parameter, ts.SyntaxKind.ReadonlyKeyword);
}

function typeNodeReferenceFqn(typeNode, contextNode) {
  if (!typeNode || !ts.isTypeReferenceNode(typeNode)) {
    return '';
  }
  return typeNameReferenceFqn(typeNode.typeName, contextNode);
}

function typeNameReferenceFqn(typeName, contextNode) {
  const parts = typeNameParts(typeName);
  if (parts.length === 0) {
    return '';
  }
  if (parts.length === 1) {
    const name = parts[0];
    return localTypeFqn(name, contextNode) || imports.get(name) || classesByName.get(name) || '';
  }
  const namespaceOwner = namespaceImports.get(parts[0]) || importedNamespaces.get(parts[0]);
  if (namespaceOwner) {
    return [namespaceOwner, ...parts.slice(1)].join('.');
  }
  const importedTopLevel = imports.get(parts[0]);
  return importedTopLevel ? [importedTopLevel, ...parts.slice(1)].join('.') : '';
}

function typeNameParts(typeName) {
  if (ts.isIdentifier(typeName)) {
    return [typeName.text];
  }
  if (ts.isQualifiedName(typeName)) {
    return [...typeNameParts(typeName.left), typeName.right.text];
  }
  return [];
}

function isInternalTypeFqn(fqn) {
  return fqn.startsWith('js.');
}

function collectLocalTypeMetadata(node) {
  if (ts.isClassDeclaration(node)) {
    const graphName = declarationName(node);
    if (graphName) {
      const topLevel = isTopLevelDeclaration(node);
      const fqn = registerTypeNode(node, graphName, topLevel);
      if (topLevel) {
        registerUniqueName(classesByName, graphName, fqn);
        for (const alias of declarationAliases(node, graphName)) {
          registerUniqueName(localTypeFqnsByName, alias, fqn);
          registerUniqueName(classesByName, alias, fqn);
        }
      }
    }
  } else if (
    ts.isInterfaceDeclaration(node) ||
    ts.isTypeAliasDeclaration(node) ||
    ts.isEnumDeclaration(node)
  ) {
    registerTypeNode(node, node.name.text, isTopLevelDeclaration(node));
  } else if (
    ts.isVariableDeclaration(node) &&
    ts.isIdentifier(node.name)
  ) {
    const classExpression = classExpressionInitializer(node.initializer);
    if (classExpression) {
      const topLevel = isTopLevelVariableDeclaration(node);
      const fqn = registerTypeNode(classExpression, node.name.text, topLevel);
      if (topLevel) {
        registerUniqueName(classesByName, node.name.text, fqn);
      }
    }
  } else if (shouldCollectNamedClassExpression(node)) {
    registerTypeNode(node, node.name.text, false, { registerScoped: false });
  }
  ts.forEachChild(node, collectLocalTypeMetadata);
}

function classExpressionInitializer(initializer) {
  if (!initializer) {
    return null;
  }
  const expression = unwrapClassInitializerExpression(initializer);
  return ts.isClassExpression(expression) ? expression : null;
}

function unwrapClassInitializerExpression(expression) {
  let current = expression;
  while (isClassInitializerWrapper(current)) {
    current = current.expression;
  }
  return current;
}

function shouldCollectNamedClassExpression(node) {
  return ts.isClassExpression(node) &&
    Boolean(node.name) &&
    !isVariableClassExpressionInitializer(node) &&
    !isExportAssignmentClassExpression(node);
}

function isVariableClassExpressionInitializer(node) {
  let current = node;
  let parent = current.parent;
  while (
    parent &&
    isClassInitializerWrapper(parent) &&
    parent.expression === current
  ) {
    current = parent;
    parent = current.parent;
  }
  return Boolean(
    parent &&
      ts.isVariableDeclaration(parent) &&
      parent.initializer === current &&
      ts.isIdentifier(parent.name)
  );
}

function isExportAssignmentClassExpression(node) {
  let current = node;
  let parent = current.parent;
  while (
    parent &&
    isClassInitializerWrapper(parent) &&
    parent.expression === current
  ) {
    current = parent;
    parent = current.parent;
  }
  return Boolean(
    parent &&
      ts.isExportAssignment(parent) &&
      unwrapExpression(parent.expression) === unwrapExpression(current)
  );
}

function isClassInitializerWrapper(node) {
  return ts.isParenthesizedExpression(node) ||
    ts.isAsExpression(node) ||
    ts.isTypeAssertionExpression(node) ||
    ts.isNonNullExpression(node) ||
    (ts.isSatisfiesExpression && ts.isSatisfiesExpression(node));
}

function registerTypeNode(node, name, topLevel, options = {}) {
  const fqn = topLevel ? `${moduleFqn}.${name}` : localTypeFqnFor(node, name);
  typeFqnByNode.set(node, fqn);
  typeNameByNode.set(node, name);
  if (ts.isClassExpression(node) && node.name) {
    registerClassSelfType(node, node.name.text, fqn);
  }
  if (options.registerScoped !== false) {
    registerScopedLocalType(node, name, fqn);
  }
  if (topLevel) {
    registerUniqueName(localTypeFqnsByName, name, fqn);
  }
  return fqn;
}

function registerScopedLocalType(node, name, fqn) {
  const scope = lexicalTypeScope(node);
  registerLocalType(scope, name, fqn);
}

function registerClassSelfType(node, name, fqn) {
  registerLocalType(node, name, fqn);
  classSelfNamesByOwnerFqn.set(`${fqn}\u0000${name}`, fqn);
}

function registerLocalType(scope, name, fqn) {
  if (!localTypeScopesByNode.has(scope)) {
    localTypeScopesByNode.set(scope, new Map());
  }
  registerUniqueName(localTypeScopesByNode.get(scope), name, fqn);
}

function registerUniqueName(target, name, fqn) {
  if (!name || !fqn) {
    return;
  }
  if (!target.has(name)) {
    target.set(name, fqn);
    return;
  }
  if (target.get(name) !== fqn) {
    target.set(name, '');
  }
}

function typeFqnFor(node, name) {
  return typeNameByNode.get(node) === name && typeFqnByNode.get(node)
    ? typeFqnByNode.get(node)
    : `${moduleFqn}.${name}`;
}

function localTypeFqnFor(node, name) {
  const start = sourceFile.getLineAndCharacterOfPosition(node.getStart(sourceFile));
  return `${moduleFqn}.$local$${start.line + 1}$${start.character + 1}.${name}`;
}

function isTopLevelDeclaration(node) {
  return node.parent === sourceFile;
}

function isTopLevelVariableDeclaration(node) {
  const declarationList = node.parent;
  const statement = declarationList ? declarationList.parent : null;
  return Boolean(
    declarationList &&
      ts.isVariableDeclarationList(declarationList) &&
      statement &&
      ts.isVariableStatement(statement) &&
      statement.parent === sourceFile
  );
}

function lexicalTypeScope(node) {
  let current = node.parent;
  while (current && !isTypeScope(current)) {
    current = current.parent;
  }
  return current || sourceFile;
}

function parentTypeScope(scope) {
  let current = scope.parent;
  while (current && !isTypeScope(current)) {
    current = current.parent;
  }
  return current || null;
}

function isTypeScope(node) {
  return node === sourceFile ||
    ts.isClassDeclaration(node) ||
    ts.isClassExpression(node) ||
    ts.isBlock(node) ||
    ts.isModuleBlock(node) ||
    node.kind === ts.SyntaxKind.CaseBlock ||
    ts.isForStatement(node) ||
    ts.isForInStatement(node) ||
    ts.isForOfStatement(node);
}

function classSelfTypeFqn(ownerFqn, name, contextNode = null) {
  const fqn = classSelfNamesByOwnerFqn.get(`${ownerFqn}\u0000${name}`) || '';
  if (!fqn || !contextNode) {
    return fqn;
  }
  return isClassSelfNameShadowed(contextNode, ownerFqn, name) ? SHADOWED_CLASS_SELF : fqn;
}

function isClassSelfNameShadowed(contextNode, ownerFqn, name) {
  let current = contextNode.parent;
  while (current) {
    if (ts.isClassExpression(current) && typeFqnByNode.get(current) === ownerFqn) {
      return false;
    }
    if (scopeDeclaresValueName(current, name)) {
      return true;
    }
    current = current.parent;
  }
  return false;
}

function scopeDeclaresValueName(scope, name) {
  if (ts.isFunctionLike(scope)) {
    return Array.from(scope.parameters || []).some(parameter =>
      bindingNameContains(parameter.name, name)
    );
  }
  if (ts.isBlock(scope) || scope === sourceFile) {
    return Array.from(scope.statements || []).some(statement =>
      statementDeclaresValueName(statement, name)
    );
  }
  if (scope.kind === ts.SyntaxKind.CaseClause || scope.kind === ts.SyntaxKind.DefaultClause) {
    return Array.from(scope.statements || []).some(statement =>
      statementDeclaresValueName(statement, name)
    );
  }
  if (ts.isForStatement(scope)) {
    return variableDeclarationListContains(scope.initializer, name);
  }
  if (ts.isForInStatement(scope) || ts.isForOfStatement(scope)) {
    return variableDeclarationListContains(scope.initializer, name);
  }
  if (ts.isCatchClause(scope)) {
    return Boolean(scope.variableDeclaration) &&
      bindingNameContains(scope.variableDeclaration.name, name);
  }
  return false;
}

function statementDeclaresValueName(statement, name) {
  if (ts.isVariableStatement(statement)) {
    return variableDeclarationListContains(statement.declarationList, name);
  }
  return (ts.isFunctionDeclaration(statement) || ts.isClassDeclaration(statement)) &&
    Boolean(statement.name) &&
    statement.name.text === name;
}

function variableDeclarationListContains(node, name) {
  return Boolean(node) &&
    ts.isVariableDeclarationList(node) &&
    Array.from(node.declarations || []).some(decl => bindingNameContains(decl.name, name));
}

function bindingNameContains(bindingName, name) {
  if (ts.isIdentifier(bindingName)) {
    return bindingName.text === name;
  }
  return Array.from(bindingName.elements || []).some(element =>
    !ts.isOmittedExpression(element) && bindingNameContains(element.name, name)
  );
}

function emittedClassExpression(node) {
  return ts.isClassExpression(node) && typeFqnByNode.has(node);
}

function typeReferenceFqn(expression, contextNode = null) {
  const parts = expressionNameParts(expression);
  if (parts.length === 0) {
    return '';
  }
  if (parts.length === 1) {
    const name = parts[0];
    return localTypeFqn(name, contextNode) || imports.get(name) || classesByName.get(name) || name;
  }
  const namespaceOwner = namespaceImports.get(parts[0]) || importedNamespaces.get(parts[0]);
  if (namespaceOwner) {
    return [namespaceOwner, ...parts.slice(1)].join('.');
  }
  const importedTopLevel = imports.get(parts[0]);
  if (importedTopLevel) {
    return [importedTopLevel, ...parts.slice(1)].join('.');
  }
  const localFqn = localTypeFqn(parts[0], contextNode);
  return localFqn ? [localFqn, ...parts.slice(1)].join('.') : parts.join('.');
}

function localTypeFqn(name, contextNode = null) {
  if (contextNode) {
    let scope = lexicalTypeScope(contextNode);
    while (scope) {
      const scopedTypes = localTypeScopesByNode.get(scope);
      if (scopedTypes && scopedTypes.has(name)) {
        return scopedTypes.get(name) || '';
      }
      scope = parentTypeScope(scope);
    }
  }
  return localTypeFqnsByName.get(name) || '';
}

function argValue(name) {
  const index = args.indexOf(name);
  return index >= 0 && index + 1 < args.length ? args[index + 1] : '';
}

function write(record) {
  process.stdout.write(`${JSON.stringify(record)}\n`);
}
