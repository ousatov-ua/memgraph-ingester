'use strict';

const fs = require('fs');
const path = require('path');
const ts = require('typescript');

const SOURCE_EXTENSIONS = ['.ts', '.tsx', '.mts', '.cts', '.js', '.jsx', '.mjs', '.cjs'];
const DECLARATION_EXTENSIONS = ['.d.ts', '.d.mts', '.d.cts'];
const KNOWN_SOURCE_EXTENSIONS = SOURCE_EXTENSIONS.concat(DECLARATION_EXTENSIONS);

function createPathContext(root, file) {
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
  const tsconfigPathMappings = loadTsconfigPathMappings(root);

  function localImportedModuleFqn(moduleSpecifier, fromDir = moduleDir) {
    const resolved = resolveLocalModulePath(moduleSpecifier, fromDir);
    return resolved ? moduleFqnForModulePath(resolved) : '';
  }

  function resolveLocalModulePath(moduleSpecifier, fromDir = moduleDir) {
    const importBases = moduleSpecifier.startsWith('.')
      ? [path.resolve(root, fromDir, moduleSpecifier)]
      : mappedImportBases(moduleSpecifier, tsconfigPathMappings);
    const candidates = [];
    for (const importBase of importBases) {
      if (hasKnownSourceExtension(importBase)) {
        for (const candidate of explicitSourceCandidates(importBase)) {
          pushCandidate(candidates, candidate);
        }
        pushCandidate(candidates, importBase);
      } else {
        appendCandidatesWithExtensions(candidates, importBase, SOURCE_EXTENSIONS);
        appendCandidatesWithExtensions(
          candidates,
          path.join(importBase, 'index'),
          SOURCE_EXTENSIONS
        );
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

  return {
    modulePath,
    moduleDir,
    moduleName,
    packageName,
    moduleFqn,
    localImportedModuleFqn,
    resolveLocalModulePath,
    moduleFqnForModulePath
  };
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

function hasKnownSourceExtension(filePath) {
  const lower = filePath.toLowerCase();
  return KNOWN_SOURCE_EXTENSIONS.some(extension => lower.endsWith(extension));
}

function loadTsconfigPathMappings(rootDir) {
  const configPath = ts.findConfigFile(rootDir, ts.sys.fileExists, 'tsconfig.json');
  if (!configPath) {
    return [];
  }
  const readResult = ts.readConfigFile(configPath, ts.sys.readFile);
  if (readResult.error || !readResult.config) {
    return [];
  }
  const parsed = ts.parseJsonConfigFileContent(
    readResult.config,
    ts.sys,
    path.dirname(configPath),
    undefined,
    configPath
  );
  const compilerOptions = parsed.options || {};
  const paths = compilerOptions.paths || {};
  const baseUrl = path.resolve(
    compilerOptions.pathsBasePath || compilerOptions.baseUrl || path.dirname(configPath)
  );
  return Object.entries(paths).map(([pattern, targets]) => ({
    pattern,
    targets: Array.isArray(targets) ? targets : [],
    baseUrl
  }));
}

function mappedImportBases(moduleSpecifier, tsconfigPathMappings) {
  const result = [];
  const matches = [];
  for (const mapping of tsconfigPathMappings) {
    const matched = matchPathPattern(mapping.pattern, moduleSpecifier);
    if (matched === null) {
      continue;
    }
    matches.push({
      mapping,
      matched,
      prefixLength: pathPatternPrefixLength(mapping.pattern)
    });
  }
  if (matches.length === 0) {
    return result;
  }
  const bestPrefixLength = Math.max(...matches.map(match => match.prefixLength));
  for (const match of matches) {
    if (match.prefixLength !== bestPrefixLength) {
      continue;
    }
    for (const target of match.mapping.targets) {
      result.push(path.resolve(match.mapping.baseUrl, target.replace('*', match.matched)));
    }
  }
  return result;
}

function pathPatternPrefixLength(pattern) {
  const starIndex = pattern.indexOf('*');
  return starIndex < 0 ? pattern.length : starIndex;
}

function matchPathPattern(pattern, moduleSpecifier) {
  const starIndex = pattern.indexOf('*');
  if (starIndex < 0) {
    return pattern === moduleSpecifier ? '' : null;
  }
  const prefix = pattern.slice(0, starIndex);
  const suffix = pattern.slice(starIndex + 1);
  if (!moduleSpecifier.startsWith(prefix) || !moduleSpecifier.endsWith(suffix)) {
    return null;
  }
  return moduleSpecifier.slice(prefix.length, moduleSpecifier.length - suffix.length);
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

function appendCandidatesWithExtensions(candidates, basePath, extensions) {
  for (const extension of extensions) {
    pushCandidate(candidates, basePath + extension);
  }
}

function moduleFqnForModulePath(targetModulePath) {
  const targetDir = path.dirname(targetModulePath);
  const targetDirParts = targetDir === '.'
    ? []
    : targetDir.split('/').map(pathIdentityPart).filter(Boolean);
  return `${['js', ...targetDirParts].join('.')}.${pathIdentityPart(path.basename(targetModulePath))}`;
}

module.exports = {
  createPathContext,
  scriptKind
};
