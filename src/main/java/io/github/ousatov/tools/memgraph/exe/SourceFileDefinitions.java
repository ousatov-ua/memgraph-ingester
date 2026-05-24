package io.github.ousatov.tools.memgraph.exe;

import java.util.Collection;
import java.util.List;

/**
 * Current graph identities emitted for one source file during ingestion.
 *
 * @author Oleksii Usatov
 */
record SourceFileDefinitions(
    List<String> classFqns,
    List<String> interfaceFqns,
    List<String> annotationFqns,
    List<String> methodSignatures,
    List<String> fieldFqns) {

  SourceFileDefinitions {
    classFqns = List.copyOf(classFqns);
    interfaceFqns = List.copyOf(interfaceFqns);
    annotationFqns = List.copyOf(annotationFqns);
    methodSignatures = List.copyOf(methodSignatures);
    fieldFqns = List.copyOf(fieldFqns);
  }

  static SourceFileDefinitions of(
      Collection<String> classFqns,
      Collection<String> interfaceFqns,
      Collection<String> annotationFqns,
      Collection<String> methodSignatures,
      Collection<String> fieldFqns) {
    return new SourceFileDefinitions(
        List.copyOf(classFqns),
        List.copyOf(interfaceFqns),
        List.copyOf(annotationFqns),
        List.copyOf(methodSignatures),
        List.copyOf(fieldFqns));
  }
}
