'use strict';

function createAstHelpers(ts, sourceFile) {
  function isNamedModuleImport(statement) {
    return ts.isImportDeclaration(statement) &&
      Boolean(statement.importClause) &&
      ts.isStringLiteral(statement.moduleSpecifier);
  }

  function buildSignature(ownerFqn, name, params) {
    return buildSignatureFromTypes(ownerFqn, name, Array.from(params).map(p => typeText(p.type)));
  }

  function buildSignatureFromTypes(ownerFqn, name, paramTypes) {
    return `${ownerFqn}.${name}(${paramTypes.join(', ')})`;
  }

  function typeText(typeNode, sf = sourceFile) {
    return typeNode ? typeNode.getText(sf).replace(/\s+/g, ' ') : 'any';
  }

  function lineRange(node, sf = sourceFile) {
    const start = sf.getLineAndCharacterOfPosition(node.getStart(sf)).line + 1;
    const end = sf.getLineAndCharacterOfPosition(node.getEnd()).line + 1;
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

  function indexSignatureName(node) {
    const parameters = Array.from(node.parameters || []).map(parameter => parameter.getText(sourceFile));
    return `[${parameters.join(', ')}]`;
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

  function shouldEmitBodylessMethod(node) {
    return hasModifier(node, ts.SyntaxKind.AbstractKeyword) || Boolean(node.questionToken);
  }

  function classMethodSignatureKind(node) {
    const parts = [];
    if (hasModifier(node, ts.SyntaxKind.AbstractKeyword)) {
      parts.push('abstract');
    }
    if (node.questionToken) {
      parts.push('optional');
    }
    parts.push(methodKind(node));
    parts.push('signature');
    return parts.join('-');
  }

  function classFieldKind(node) {
    const parts = [];
    if (hasModifier(node, ts.SyntaxKind.AbstractKeyword)) {
      parts.push('abstract');
    }
    if (node.questionToken) {
      parts.push('optional');
    }
    parts.push('property');
    return parts.join('-');
  }

  function hasStatic(node) {
    return hasModifier(node, ts.SyntaxKind.StaticKeyword);
  }

  function isFunctionInitializer(initializer) {
    return Boolean(initializer) &&
      (ts.isArrowFunction(initializer) || ts.isFunctionExpression(initializer));
  }

  function isClassExpressionInitializer(initializer) {
    return Boolean(initializer) && ts.isClassExpression(initializer);
  }

  function unwrapExpression(expression) {
    let current = expression;
    while (ts.isParenthesizedExpression(current)) {
      current = current.expression;
    }
    return current;
  }

  function expressionNameParts(expression) {
    const current = unwrapExpression(expression);
    if (ts.isIdentifier(current)) {
      return [current.text];
    }
    if (ts.isPropertyAccessExpression(current)) {
      const prefix = expressionNameParts(current.expression);
      return prefix.length ? [...prefix, current.name.text] : [];
    }
    return [];
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

  return {
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
    isClassExpressionInitializer,
    unwrapExpression,
    expressionNameParts,
    isDeclarationWithOwnCallableScope,
    declarationName,
    declarationAliases,
    isDefaultExport,
    hasModifier
  };
}

module.exports = { createAstHelpers };
