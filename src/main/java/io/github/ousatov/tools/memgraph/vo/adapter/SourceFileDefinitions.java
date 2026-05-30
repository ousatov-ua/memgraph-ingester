package io.github.ousatov.tools.memgraph.vo.adapter;

import java.util.Collection;
import java.util.List;

/**
 * Current graph identities emitted for one source file during ingestion.
 *
 * @author Oleksii Usatov
 */
public record SourceFileDefinitions(
    List<String> classFqns,
    List<String> interfaceFqns,
    List<String> annotationFqns,
    List<String> methodSignatures,
    List<String> fieldFqns) {

  public SourceFileDefinitions {
    classFqns = List.copyOf(classFqns);
    interfaceFqns = List.copyOf(interfaceFqns);
    annotationFqns = List.copyOf(annotationFqns);
    methodSignatures = List.copyOf(methodSignatures);
    fieldFqns = List.copyOf(fieldFqns);
  }

  /** Creates a stable snapshot from mutable definition collections. */
  public static SourceFileDefinitions of(
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

  /** Returns an empty source-file definition snapshot. */
  public static SourceFileDefinitions empty() {
    return of(List.of(), List.of(), List.of(), List.of(), List.of());
  }

  /** Returns true when this snapshot contains no graph identities. */
  public boolean isEmpty() {
    return classFqns.isEmpty()
        && interfaceFqns.isEmpty()
        && annotationFqns.isEmpty()
        && methodSignatures.isEmpty()
        && fieldFqns.isEmpty();
  }
}
