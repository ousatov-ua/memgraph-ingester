package io.github.ousatov.tools.memgraph.vo.writer;

import java.util.ArrayList;
import java.util.List;

/**
 * Collected structural writes for one Java declaration tree.
 *
 * @author Oleksii Usatov
 */
public final class TypeStructureWrites {

  private final List<ClassWrite> classes = new ArrayList<>();
  private final List<InterfaceWrite> interfaces = new ArrayList<>();
  private final List<TypeRelationWrite> classExtends = new ArrayList<>();
  private final List<TypeRelationWrite> interfaceExtends = new ArrayList<>();
  private final List<TypeRelationWrite> implementsRelations = new ArrayList<>();

  public List<ClassWrite> classes() {
    return classes;
  }

  public List<InterfaceWrite> interfaces() {
    return interfaces;
  }

  public List<TypeRelationWrite> classExtends() {
    return classExtends;
  }

  public List<TypeRelationWrite> interfaceExtends() {
    return interfaceExtends;
  }

  public List<TypeRelationWrite> implementsRelations() {
    return implementsRelations;
  }
}
