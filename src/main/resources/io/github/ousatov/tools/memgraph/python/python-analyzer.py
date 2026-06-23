#!/usr/bin/env python3
"""Emit Python source structure as newline-delimited JSON records."""

from __future__ import annotations

import argparse
import ast
import json
import keyword
from pathlib import Path
import re
import sys
from typing import Dict, List, Optional, Set, Tuple


CLASS = "class"
FUNCTION = "function"
INIT = "<init>"
MODULE = "module"
VARIABLE = "variable"
VOID = "void"
ANY = "Any"
SKIPPED_CALL_SCOPES = (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", required=True, action="append")
    parser.add_argument("--root", required=True)
    args = parser.parse_args()

    require_ast_unparse()
    for file in args.file:
        analyzer = PythonSourceAnalyzer(Path(args.root), Path(file))
        analyzer.analyze()
    return 0


class PythonSourceAnalyzer:
    """AST visitor that maps one Python source file into graph records."""

    def __init__(self, root: Path, file: Path) -> None:
        self.root = root.resolve()
        self.file = file.resolve()
        self.module_path = normalized_relative_path(self.root, self.file)
        self.module_dir = str(Path(self.module_path).parent)
        if self.module_dir == ".":
            self.module_dir = ""
        self.module_name = sanitize_part(self.file.stem)
        self.package_name = package_name_for_module_path(self.module_path)
        self.module_fqn = module_fqn_for_module_path(self.module_path)
        self.module_signature = f"{self.module_fqn}.{INIT}()"
        self.tree = None
        self.local_classes = {}  # type: Dict[str, str]
        self.local_functions = {}  # type: Dict[str, str]
        self.imported_modules = {}  # type: Dict[str, ImportedModule]
        self.imported_symbols = {}  # type: Dict[str, ImportedSymbol]
        self.module_symbol_cache = {}  # type: Dict[str, Dict[str, ImportedSymbol]]
        self.class_stack = []  # type: List[str]

    def analyze(self) -> None:
        source = self.file.read_text(encoding="utf-8")
        self.tree = parse_python(source, self.file)
        write(
            record="module",
            moduleFqn=self.module_fqn,
            moduleName=self.module_name,
            packageName=self.package_name,
            modulePath=self.module_path,
            startLine=1,
            endLine=max(1, len(source.splitlines())),
        )
        self.collect_imports(self.tree.body)
        self.predeclare_module_declarations(self.tree.body)
        self.collect_module_declarations(self.tree.body)
        self.collect_module_members(self.tree.body)
        self.collect_calls(self.tree.body, self.module_signature, self.module_fqn)

    def collect_imports(self, body: List[ast.stmt]) -> None:
        imported_modules, imported_symbols = self.import_bindings(body)
        self.imported_modules.update(imported_modules)
        self.imported_symbols.update(imported_symbols)

    def import_bindings(self, body: List[ast.stmt]) -> Tuple[Dict[str, "ImportedModule"], Dict[str, "ImportedSymbol"]]:
        imported_modules = {}  # type: Dict[str, ImportedModule]
        imported_symbols = {}  # type: Dict[str, ImportedSymbol]
        for node in body:
            if isinstance(node, SKIPPED_CALL_SCOPES):
                continue
            for current in walk_without_nested_scopes(node):
                if isinstance(current, ast.Import):
                    self.add_import_bindings(current, imported_modules)
                elif isinstance(current, ast.ImportFrom):
                    self.add_import_from_bindings(current, imported_modules, imported_symbols)
        return imported_modules, imported_symbols

    def add_import_bindings(self, node: ast.Import, imported_modules: Dict[str, "ImportedModule"]) -> None:
        for alias in node.names:
            local_name = alias.asname or alias.name
            relative = alias.name.replace(".", "/")
            module_fqn = self.resolve_import_module(alias.name)
            if module_fqn:
                imported_modules[local_name] = ImportedModule(module_fqn, relative)
            if alias.asname or "." not in alias.name:
                continue
            package_name = alias.name.split(".", 1)[0]
            package_fqn = self.resolve_import_module(package_name)
            if package_fqn:
                imported_modules.setdefault(package_name, ImportedModule(package_fqn, package_name))

    def add_import_from_bindings(  # NOSONAR python:S3776 - AST import resolution is deliberately centralized.
        self,
        node: ast.ImportFrom,
        imported_modules: Dict[str, "ImportedModule"],
        imported_symbols: Dict[str, "ImportedSymbol"],
    ) -> None:
        base_relative = self.import_from_relative_path(node, None)
        for alias in node.names:
            if alias.name == "*":
                continue
            local_name = alias.asname or alias.name
            imported = self.module_symbol_info(base_relative, alias.name) if base_relative else None
            if imported is None:
                submodule = self.resolve_imported_submodule(node, alias.name)
                if submodule:
                    imported = ImportedSymbol(
                        module_fqn=submodule,
                        name=alias.name,
                        is_module=True,
                        kind=MODULE,
                        relative_path=self.import_from_relative_path(node, alias.name),
                    )
            if imported is None:
                base_module = self.module_fqn_for_import_relative_path(base_relative) if base_relative else ""
                if base_module:
                    imported = ImportedSymbol(base_module, alias.name, False, VARIABLE)
            if imported is None:
                continue
            imported_symbols[local_name] = imported
            if imported.is_module:
                imported_modules[local_name] = ImportedModule(
                    imported.module_fqn,
                    imported.relative_path or self.import_from_relative_path(node, alias.name),
                )

    def collect_calls_with_local_imports(
        self, body: List[ast.stmt], caller_signature: str, caller_owner_fqn: str
    ) -> None:
        local_modules, local_symbols = self.import_bindings(body)
        if not local_modules and not local_symbols:
            self.collect_calls(body, caller_signature, caller_owner_fqn)
            return
        imported_modules = self.imported_modules
        imported_symbols = self.imported_symbols
        self.imported_modules = {**imported_modules, **local_modules}
        self.imported_symbols = {**imported_symbols, **local_symbols}
        try:
            self.collect_calls(body, caller_signature, caller_owner_fqn)
        finally:
            self.imported_modules = imported_modules
            self.imported_symbols = imported_symbols

    def predeclare_module_declarations(self, body: List[ast.stmt]) -> None:
        for node in body:
            if isinstance(node, ast.ClassDef):
                self.local_classes[node.name] = class_fqn(self.module_fqn, node.name)
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                self.local_functions[node.name] = signature_for(self.module_fqn, node.name)

    def collect_module_declarations(self, body: List[ast.stmt]) -> None:
        for node in body:
            if isinstance(node, ast.ClassDef):
                self.collect_class(node, self.module_fqn)
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                self.collect_function(self.module_fqn, node, function_kind(node))

    def collect_module_members(self, body: List[ast.stmt]) -> None:
        for node in assignment_nodes(body):
            for name, data_type in assigned_names(node):
                self.write_field(self.module_fqn, name, data_type, True, "variable", node)

    def collect_class(self, node: ast.ClassDef, owner_fqn: str) -> None:
        fqn = class_fqn(owner_fqn, node.name)
        if owner_fqn == self.module_fqn:
            self.local_classes[node.name] = fqn
        has_constructor = any(
            isinstance(member, (ast.FunctionDef, ast.AsyncFunctionDef)) and member.name == "__init__"
            for member in node.body
        )
        write(
            record="type",
            kind=CLASS,
            fqn=fqn,
            name=node.name,
            framework="",
            hasConstructor=has_constructor,
            isAbstract=is_abstract_class(node),
            startLine=line(node),
            endLine=end_line(node),
        )
        for decorator in node.decorator_list:
            self.write_annotation("fqn", fqn, decorator)
        for base in node.bases:
            target = self.resolve_type_expr(base)
            if target:
                write(record="relation", kind="classExtends", childFqn=fqn, targetFqn=target)
        self.class_stack.append(fqn)
        try:
            for member in node.body:
                if isinstance(member, ast.ClassDef):
                    self.collect_class(member, fqn)
                elif isinstance(member, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    self.collect_method(fqn, member)
            for member in assignment_nodes(node.body):
                for name, data_type in assigned_names(member):
                    self.write_field(fqn, name, data_type, True, "class-field", member)
        finally:
            self.class_stack.pop()

    def collect_function(
        self, owner_fqn: str, node, kind: str
    ) -> str:
        signature = signature_for(owner_fqn, node.name)
        if owner_fqn == self.module_fqn:
            self.local_functions[node.name] = signature
        write(
            record="member",
            ownerFqn=owner_fqn,
            ownerKind="Class",
            memberType="method",
            kind=kind,
            key=signature,
            name=node.name,
            dataType=annotation_text(node.returns),
            isStatic=True,
            startLine=line(node),
            endLine=end_line(node),
        )
        for decorator in node.decorator_list:
            self.write_annotation("sig", signature, decorator)
        self.collect_calls_with_local_imports(node.body, signature, owner_fqn)
        return signature

    def collect_method(
        self, owner_fqn: str, node
    ) -> None:
        method_name = INIT if node.name == "__init__" else node.name
        signature = signature_for(owner_fqn, method_name)
        kind = "constructor" if method_name == INIT else function_kind(node)
        is_static = has_decorator(node, {"staticmethod", "classmethod"})
        write(
            record="member",
            ownerFqn=owner_fqn,
            ownerKind="Class",
            memberType="method",
            kind=kind,
            key=signature,
            name=method_name,
            dataType=VOID if method_name == INIT else annotation_text(node.returns),
            isStatic=is_static,
            startLine=line(node),
            endLine=end_line(node),
        )
        for decorator in node.decorator_list:
            self.write_annotation("sig", signature, decorator)
        for field_name, data_type in instance_fields(node):
            self.write_field(owner_fqn, field_name, data_type, False, "instance-field", node)
        self.collect_calls_with_local_imports(node.body, signature, owner_fqn)

    def collect_calls(self, body: List[ast.stmt], caller_signature: str, caller_owner_fqn: str) -> None:
        for node in body:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                continue
            for call in walk_without_nested_scopes(node):
                if isinstance(call, ast.Call):
                    self.write_call(call, caller_signature, caller_owner_fqn)

    def write_call(self, node: ast.Call, caller_signature: str, caller_owner_fqn: str) -> None:
        resolved = self.resolve_call(node.func, caller_owner_fqn)
        if not resolved:
            return
        if resolved.signature:
            write(
                record="call",
                callerSignature=caller_signature,
                calleeSignature=resolved.signature,
                calleeOwnerFqn="",
                calleeName="",
            )
        else:
            write(
                record="callByName",
                callerSignature=caller_signature,
                calleeSignature="",
                calleeOwnerFqn=resolved.owner_fqn,
                calleeName=resolved.name,
            )

    def resolve_call(  # NOSONAR python:S3776 - call resolution mirrors Python AST cases.
        self, func: ast.expr, caller_owner_fqn: str
    ) -> Optional["ResolvedCall"]:
        if isinstance(func, ast.Name):
            if func.id in self.local_functions:
                return ResolvedCall(signature=self.local_functions[func.id])
            if func.id in self.local_classes:
                return ResolvedCall(owner_fqn=self.local_classes[func.id], name=INIT)
            imported = self.imported_symbols.get(func.id)
            if imported:
                if imported.kind == CLASS:
                    return ResolvedCall(owner_fqn=class_fqn(imported.module_fqn, imported.name), name=INIT)
                if imported.is_module:
                    return ResolvedCall(owner_fqn=imported.module_fqn, name=INIT)
                return ResolvedCall(owner_fqn=imported.module_fqn, name=imported.name)
            return None
        if isinstance(func, ast.Attribute):
            receiver = func.value
            if isinstance(receiver, ast.Name):
                if receiver.id in {"self", "cls"}:
                    return ResolvedCall(owner_fqn=caller_owner_fqn, name=func.attr)
                if receiver.id in self.local_classes:
                    return ResolvedCall(owner_fqn=self.local_classes[receiver.id], name=func.attr)
                if receiver.id in self.imported_modules:
                    imported_module = self.imported_modules[receiver.id]
                    return self.resolve_module_member_call(imported_module, func.attr)
                imported = self.imported_symbols.get(receiver.id)
                if imported and not imported.is_module:
                    return ResolvedCall(owner_fqn=f"{imported.module_fqn}.{imported.name}", name=func.attr)
            imported_module = self.resolve_imported_module_expr(receiver)
            if imported_module:
                return self.resolve_module_member_call(imported_module, func.attr)
            owner = self.resolve_type_expr(receiver)
            if owner:
                return ResolvedCall(owner_fqn=owner, name=func.attr)
        return None

    def resolve_module_member_call(self, module: "ImportedModule", name: str) -> "ResolvedCall":
        if self.module_defines_class(module.relative_path, name):
            return ResolvedCall(owner_fqn=class_fqn(module.module_fqn, name), name=INIT)
        return ResolvedCall(owner_fqn=module.module_fqn, name=name)

    def resolve_type_expr(self, expr: ast.expr) -> str:  # NOSONAR python:S3776 - type resolution is AST case analysis.
        if isinstance(expr, ast.Name):
            if expr.id in self.local_classes:
                return self.local_classes[expr.id]
            imported = self.imported_symbols.get(expr.id)
            if imported and not imported.is_module:
                return f"{imported.module_fqn}.{imported.name}"
            return expr.id
        if isinstance(expr, ast.Attribute):
            parts = attribute_parts(expr)
            if not parts:
                return ""
            imported_module, consumed = self.resolve_imported_module_parts(parts)
            module_fqn = imported_module.module_fqn if imported_module else ""
            if module_fqn:
                return ".".join([module_fqn, *parts[consumed:]])
            imported = self.imported_symbols.get(parts[0])
            if imported:
                base = imported.module_fqn if imported.is_module else f"{imported.module_fqn}.{imported.name}"
                return ".".join([base, *parts[1:]])
            return ".".join(parts)
        if isinstance(expr, ast.Subscript):
            return self.resolve_type_expr(expr.value)
        return ""

    def resolve_imported_module_expr(self, expr: ast.expr) -> Optional["ImportedModule"]:
        if isinstance(expr, ast.Name):
            return self.imported_modules.get(expr.id)
        if isinstance(expr, ast.Attribute):
            parts = attribute_parts(expr)
            imported_module, consumed = self.resolve_imported_module_parts(parts)
            return imported_module if imported_module and consumed == len(parts) else None
        return None

    def resolve_imported_module_parts(self, parts: List[str]) -> Tuple[Optional["ImportedModule"], int]:
        for end in range(len(parts), 0, -1):
            imported = self.imported_modules.get(".".join(parts[:end]))
            if imported:
                for submodule_end in range(len(parts), end, -1):
                    relative = "/".join([imported.relative_path, *parts[end:submodule_end]])
                    module_fqn = self.module_fqn_for_import_relative_path(relative)
                    if module_fqn:
                        return ImportedModule(module_fqn, relative), submodule_end
                return imported, end
        return None, 0

    def resolve_import_module(self, module_name: str) -> str:
        relative = module_name.replace(".", "/")
        return self.module_fqn_for_import_relative_path(relative)

    def resolve_import_from_module(self, node: ast.ImportFrom) -> str:
        relative = self.import_from_relative_path(node, None)
        return self.module_fqn_for_import_relative_path(relative) if relative else ""

    def resolve_imported_submodule(self, node: ast.ImportFrom, name: str) -> str:
        relative = self.import_from_relative_path(node, name)
        return self.module_fqn_for_import_relative_path(relative) if relative else ""

    def import_from_relative_path(self, node: ast.ImportFrom, imported_name: Optional[str]) -> str:
        return self.import_from_relative_path_for_module(self.module_path, node, imported_name)

    def import_from_relative_path_for_module(
        self, module_path: str, node: ast.ImportFrom, imported_name: Optional[str]
    ) -> str:
        parts = []
        module_dir = str(Path(module_path).parent)
        if module_dir == ".":
            module_dir = ""
        if node.level:
            base_parts = [] if not module_dir else module_dir.split("/")
            keep = max(0, len(base_parts) - node.level + 1)
            parts.extend(base_parts[:keep])
        module = node.module or ""
        if module:
            parts.extend(module.split("."))
        if imported_name:
            parts.append(imported_name)
        return "/".join(part for part in parts if part)

    def module_fqn_for_import_relative_path(self, relative: str) -> str:
        module_path = self.module_path_for_import_relative_path(relative)
        return module_fqn_for_module_path(module_path) if module_path else ""

    def module_path_for_import_relative_path(self, relative: str) -> str:
        for module_path in self.module_path_candidates(relative):
            if (self.root / module_path).is_file():
                return module_path
        return ""

    def module_path_candidates(self, relative: str) -> List[str]:
        candidates = []
        if not relative:
            candidates.extend(["__init__.py", "__init__.pyi"])
        else:
            candidates.extend(
                [f"{relative}.py", f"{relative}.pyi", f"{relative}/__init__.py", f"{relative}/__init__.pyi"]
            )
        return candidates

    def module_defines_name(self, relative: str, name: str) -> bool:
        return self.module_symbol_info(relative, name) is not None

    def module_defines_class(self, relative: str, name: str) -> bool:
        symbol = self.module_symbol_info(relative, name)
        return bool(symbol and symbol.kind == CLASS)

    def module_symbol_info(self, relative: str, name: str) -> Optional["ImportedSymbol"]:
        module_path = self.module_path_for_import_relative_path(relative)
        if not module_path:
            return None
        return self.module_symbols(module_path).get(name)

    def module_symbols(  # NOSONAR python:S3776 - recursive module symbol discovery is intentionally local.
        self, module_path: str, seen: Optional[Set[str]] = None
    ) -> Dict[str, "ImportedSymbol"]:
        cached = self.module_symbol_cache.get(module_path)
        if cached is not None:
            return cached
        seen = set() if seen is None else set(seen)
        if module_path in seen:
            return {}
        seen.add(module_path)
        symbols = {}  # type: Dict[str, ImportedSymbol]
        module_fqn = module_fqn_for_module_path(module_path)
        path = self.root / module_path
        try:
            tree = parse_python(path.read_text(encoding="utf-8"), path)
        except (OSError, SyntaxError):
            self.module_symbol_cache[module_path] = symbols
            return symbols
        for node in tree.body:
            if isinstance(node, ast.ClassDef):
                symbols[node.name] = ImportedSymbol(module_fqn, node.name, False, CLASS)
            elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                symbols[node.name] = ImportedSymbol(module_fqn, node.name, False, FUNCTION)
            elif isinstance(node, (ast.Assign, ast.AnnAssign)):
                for name, _ in assigned_names(node):
                    symbols.setdefault(name, ImportedSymbol(module_fqn, name, False, VARIABLE))
            elif isinstance(node, ast.ImportFrom):
                for alias in node.names:
                    if alias.name == "*":
                        continue
                    local_name = alias.asname or alias.name
                    imported = self.imported_symbol_info_for_module(module_path, node, alias.name, seen)
                    symbols[local_name] = imported or ImportedSymbol(module_fqn, alias.name, False, VARIABLE)
            elif isinstance(node, ast.Import):
                for alias in node.names:
                    local_name = alias.asname or alias.name.split(".", 1)[0]
                    relative = alias.name.replace(".", "/")
                    imported_module_fqn = self.module_fqn_for_import_relative_path(relative)
                    if imported_module_fqn:
                        symbols[local_name] = ImportedSymbol(
                            imported_module_fqn, local_name, True, MODULE, relative
                        )
        self.module_symbol_cache[module_path] = symbols
        return symbols

    def imported_symbol_info_for_module(
        self, module_path: str, node: ast.ImportFrom, name: str, seen: Set[str]
    ) -> Optional["ImportedSymbol"]:
        base_relative = self.import_from_relative_path_for_module(module_path, node, None)
        base_module_path = self.module_path_for_import_relative_path(base_relative) if base_relative else ""
        if base_module_path:
            imported = self.module_symbols(base_module_path, seen).get(name)
            if imported:
                return imported
        submodule_relative = self.import_from_relative_path_for_module(module_path, node, name)
        submodule_fqn = self.module_fqn_for_import_relative_path(submodule_relative) if submodule_relative else ""
        if submodule_fqn:
            return ImportedSymbol(submodule_fqn, name, True, MODULE, submodule_relative)
        return None

    def write_field(
        self,
        owner_fqn: str,
        name: str,
        data_type: str,
        is_static: bool,
        kind: str,
        node: ast.AST,
    ) -> None:
        write(
            record="member",
            ownerFqn=owner_fqn,
            ownerKind="Class",
            memberType="field",
            kind=kind,
            key=f"{owner_fqn}#{name}",
            name=name,
            dataType=data_type,
            isStatic=is_static,
            startLine=line(node),
            endLine=end_line(node),
        )

    def write_annotation(self, owner_kind: str, owner_key: str, decorator: ast.expr) -> None:
        fqn = decorator_fqn(decorator)
        if not fqn:
            return
        write(record="annotation", ownerKind=owner_kind, ownerKey=owner_key, fqn=fqn, name=fqn.split(".")[-1])


class ImportedSymbol:
    """Local import binding discovered from one import statement."""

    def __init__(
        self, module_fqn: str, name: str, is_module: bool, kind: str, relative_path: str = ""
    ) -> None:
        self.module_fqn = module_fqn
        self.name = name
        self.is_module = is_module
        self.kind = kind
        self.relative_path = relative_path


class ImportedModule:
    """Local module import binding and its import-relative path."""

    def __init__(self, module_fqn: str, relative_path: str) -> None:
        self.module_fqn = module_fqn
        self.relative_path = relative_path


class ResolvedCall:
    """Resolved or deferred call target."""

    def __init__(self, signature: str = "", owner_fqn: str = "", name: str = "") -> None:
        self.signature = signature
        self.owner_fqn = owner_fqn
        self.name = name


def parse_python(source: str, file: Path) -> ast.Module:
    try:
        return ast.parse(source, filename=str(file), type_comments=True)
    except SyntaxError as first:
        max_minor = sys.version_info[:2][1]
        for minor in range(max_minor, 6, -1):
            try:
                return ast.parse(source, filename=str(file), type_comments=True, feature_version=(3, minor))
            except SyntaxError:
                continue
        raise first


def write(**record: object) -> None:
    print(json.dumps(record, ensure_ascii=False, separators=(",", ":")))


def normalized_relative_path(root: Path, file: Path) -> str:
    return file.relative_to(root).as_posix()


def package_name_for_module_path(module_path: str) -> str:
    parent = Path(module_path).parent
    parts = [] if str(parent) == "." else [identity_part(part) for part in parent.parts]
    return ".".join(["python", *parts])


def module_fqn_for_module_path(module_path: str) -> str:
    package = package_name_for_module_path(module_path)
    return f"{package}.{identity_part(Path(module_path).name)}"


def identity_part(part: str) -> str:
    encoded = "".join(encode_identity_char(char) for char in part)
    if not encoded:
        return "module"
    return encoded if re.match(r"^[A-Za-z_]", encoded) else f"_{encoded}"


def encode_identity_char(char: str) -> str:
    return char if re.match(r"^[A-Za-z0-9]$", char) else f"${ord(char):x}$"


def sanitize_part(part: str) -> str:
    value = re.sub(r"\W", "_", part)
    if not value:
        return "module"
    if value[0].isdigit() or keyword.iskeyword(value):
        return f"_{value}"
    return value


def class_fqn(owner_fqn: str, name: str) -> str:
    return f"{owner_fqn}.{name}"


def signature_for(owner_fqn: str, name: str) -> str:
    return f"{owner_fqn}.{name}()"


def line(node: ast.AST) -> int:
    return getattr(node, "lineno", 1)


def end_line(node: ast.AST) -> int:
    return getattr(node, "end_lineno", line(node))


def function_kind(node) -> str:
    return "async-function" if isinstance(node, ast.AsyncFunctionDef) else "function"


def annotation_text(annotation: Optional[ast.expr]) -> str:
    return expression_text(annotation) if annotation is not None else ANY


def expression_text(node: Optional[ast.AST]) -> str:
    if node is None:
        return ""
    return ast.unparse(node)


def require_ast_unparse() -> None:
    if not hasattr(ast, "unparse"):
        raise RuntimeError(
            "Python analyzer requires Python 3.9+ with ast.unparse; "
            "use --python-runtime-mode managed or a newer system Python."
        )


def assigned_names(node: ast.AST) -> List[Tuple[str, str]]:
    result = []
    if isinstance(node, ast.Assign):
        for target in node.targets:
            result.extend((name, ANY) for name in target_names(target))
    elif isinstance(node, ast.AnnAssign):
        result.extend((name, annotation_text(node.annotation)) for name in target_names(node.target))
    return result


def target_names(target: ast.AST) -> List[str]:
    if isinstance(target, ast.Name):
        return [target.id]
    if isinstance(target, (ast.Tuple, ast.List)):
        names = []
        for element in target.elts:
            names.extend(target_names(element))
        return names
    return []


def instance_fields(node) -> List[Tuple[str, str]]:
    fields = {}  # type: Dict[str, str]
    for current in walk_without_nested_scopes(node):
        if isinstance(current, ast.AnnAssign) and is_self_attribute(current.target):
            fields[current.target.attr] = annotation_text(current.annotation)
        elif isinstance(current, ast.Assign):
            for target in current.targets:
                if is_self_attribute(target):
                    fields.setdefault(target.attr, ANY)
    return list(fields.items())


def is_self_attribute(target: ast.AST) -> bool:
    return (
        isinstance(target, ast.Attribute)
        and isinstance(target.value, ast.Name)
        and target.value.id == "self"
    )


def has_decorator(node, names) -> bool:
    return any(decorator_name(decorator) in names for decorator in node.decorator_list)


def decorator_name(decorator: ast.expr) -> str:
    if isinstance(decorator, ast.Name):
        return decorator.id
    if isinstance(decorator, ast.Attribute):
        return decorator.attr
    if isinstance(decorator, ast.Call):
        return decorator_name(decorator.func)
    return ""


def decorator_fqn(decorator: ast.expr) -> str:
    if isinstance(decorator, ast.Call):
        return decorator_fqn(decorator.func)
    return expression_text(decorator)


def is_abstract_class(node: ast.ClassDef) -> bool:
    for base in node.bases:
        text = expression_text(base)
        if text in {"ABC", "abc.ABC"} or text.endswith(".ABC"):
            return True
    for keyword_node in node.keywords:
        if keyword_node.arg == "metaclass" and "ABCMeta" in expression_text(keyword_node.value):
            return True
    return False


def attribute_parts(node: ast.Attribute) -> List[str]:
    parts = [node.attr]
    current = node.value
    while isinstance(current, ast.Attribute):
        parts.append(current.attr)
        current = current.value
    if isinstance(current, ast.Name):
        parts.append(current.id)
        return list(reversed(parts))
    return []


def walk_without_nested_scopes(node: ast.AST):
    stack = [node]
    while stack:
        current = stack.pop()
        if current is not node and isinstance(current, SKIPPED_CALL_SCOPES):
            continue
        yield current
        stack.extend(reversed(list(ast.iter_child_nodes(current))))


def assignment_nodes(body: List[ast.stmt]):
    for node in body:
        if isinstance(node, SKIPPED_CALL_SCOPES):
            continue
        yield from walk_without_nested_scopes(node)

if __name__ == "__main__":
    raise SystemExit(main())
