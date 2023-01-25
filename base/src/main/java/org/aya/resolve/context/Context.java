// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.resolve.context;

import kala.collection.Seq;
import kala.collection.SeqLike;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableList;
import org.aya.concrete.stmt.QualifiedID;
import org.aya.generic.util.InterruptException;
import org.aya.ref.AnyVar;
import org.aya.ref.GenerateKind;
import org.aya.ref.LocalVar;
import org.aya.resolve.error.NameProblem;
import org.aya.util.error.SourcePos;
import org.aya.util.reporter.Problem;
import org.aya.util.reporter.Reporter;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * @author re-xyr
 */
public interface Context {
  @Nullable Context parent();
  @NotNull Reporter reporter();
  @NotNull Path underlyingFile();

  default <T> @Nullable T iterate(@NotNull Function<@NotNull Context, @Nullable T> f) {
    var p = this;
    while (p != null) {
      var result = f.apply(p);
      if (result != null) return result;
      p = p.parent();
    }
    return null;
  }

  /**
   * The qualified module name of this module, should be absolute, not empty for non EmptyContext.
   */
  default @NotNull ImmutableSeq<String> moduleName() {
    var p = parent();
    if (p == null) return ImmutableSeq.empty();
    else return p.moduleName();
  }

  @Contract("_ -> fail") default <T> @NotNull T reportAndThrow(@NotNull Problem problem) {
    reporter().report(problem);
    throw new ResolvingInterruptedException();
  }

  @Contract("_ -> fail") default <T> @NotNull T reportAllAndThrow(@NotNull SeqLike<Problem> problems) {
    reportAll(problems);
    throw new ResolvingInterruptedException();
  }

  default void reportAll(@NotNull SeqLike<Problem> problems) {
    problems.forEach(x -> reporter().report(x));
  }

  /**
   * Getting a symbol by name {@param name}.
   *
   * @param name an id which probably unqualified
   */
  default @NotNull AnyVar get(@NotNull QualifiedID name) {
    return switch (name.component()) {
      case ModulePath.This aThis -> getUnqualified(name.name(), name.sourcePos());
      case ModulePath.Qualified qualified -> getQualified(qualified, name.name(), name.sourcePos());
    };
  }

  /**
   * @see Context#get(QualifiedID)
   */
  default @Nullable AnyVar getMaybe(@NotNull QualifiedID name) {
    return switch (name.component()) {
      case ModulePath.This aThis -> getUnqualifiedMaybe(name.name(), name.sourcePos());
      case ModulePath.Qualified qualified -> getQualifiedMaybe(qualified, name.name(), name.sourcePos());
    };
  }

  default MutableList<LocalVar> collect(@NotNull MutableList<LocalVar> container) {
    return container;
  }

  /**
   * Trying to get a symbol by unqualified name {@param name} in {@code this} context.
   */
  @Nullable AnyVar getUnqualifiedLocalMaybe(
    @NotNull String name,
    @NotNull SourcePos sourcePos
  );

  /**
   * Trying to get a symbol which can referred by unqualified name {@param name} in the whole context.
   *
   * @param name      the unqualified name
   * @param sourcePos the source pos for error reporting
   * @return null if not found
   * @see Context#getUnqualifiedLocalMaybe(String, SourcePos)
   */
  default @Nullable AnyVar getUnqualifiedMaybe(
    @NotNull String name,
    @NotNull SourcePos sourcePos
  ) {
    return iterate(c -> c.getUnqualifiedLocalMaybe(name, sourcePos));
  }

  /**
   * @see Context#getUnqualified(String, SourcePos)
   */
  default @NotNull AnyVar getUnqualified(
    @NotNull String name,
    @NotNull SourcePos sourcePos
  ) {
    var result = getUnqualifiedMaybe(name, sourcePos);
    if (result == null) reportAndThrow(new NameProblem.UnqualifiedNameNotFoundError(this, name, sourcePos));
    return result;
  }

  /**
   * Trying to get a symbol by qualified id {@code {modName}::{name}} in {@code this} context
   *
   * @return a symbol in component {@param modName}, even it is {@link ModulePath#This}; null if not found
   */
  @Nullable AnyVar getQualifiedLocalMaybe(
    @NotNull ModulePath.Qualified modName,
    @NotNull String name,
    @NotNull SourcePos sourcePos
  );

  /**
   * Trying to get a symbol by qualified id {@code {modName}::{name}} in the whole context with {@param accessibility}.
   *
   * @see Context#getQualifiedLocalMaybe(ModulePath.Qualified, String, SourcePos)
   */
  default @Nullable AnyVar getQualifiedMaybe(
    @NotNull ModulePath.Qualified modName,
    @NotNull String name,
    @NotNull SourcePos sourcePos
  ) {
    return iterate(c -> c.getQualifiedLocalMaybe(modName, name, sourcePos));
  }

  /**
   * @see Context#getQualifiedMaybe(ModulePath.Qualified, String, SourcePos)
   */
  default @NotNull AnyVar getQualified(
    @NotNull ModulePath.Qualified modName,
    @NotNull String name,
    @NotNull SourcePos sourcePos
  ) {
    var result = getQualifiedMaybe(modName, name, sourcePos);
    if (result == null)
      reportAndThrow(new NameProblem.QualifiedNameNotFoundError(modName, name, sourcePos));
    return result;
  }

  /**
   * Trying to get a {@link ModuleExport} by a module {@param modName} in {@code this} context.
   *
   * @param modName qualified module name
   * @return a ModuleExport of that module; null if no such module.
   */
  @Nullable ModuleExport getModuleLocalMaybe(@NotNull ModulePath.Qualified modName);

  /**
   * Trying to get a {@link ModuleExport} by a module {@param modName} in the whole context.
   *
   * @param modName qualified module name
   * @return a ModuleExport of that module; null if no such module.
   */
  default @Nullable ModuleExport getModuleMaybe(@NotNull ModulePath.Qualified modName) {
    return iterate(c -> c.getModuleLocalMaybe(modName));
  }

  default @NotNull Context bind(
    @NotNull LocalVar ref,
    @NotNull SourcePos sourcePos,
    @NotNull Predicate<@Nullable AnyVar> toWarn
  ) {
    return bind(ref.name(), ref, sourcePos, toWarn);
  }

  default @NotNull Context bind(@NotNull LocalVar ref, @NotNull SourcePos sourcePos) {
    return bind(ref.name(), ref, sourcePos, var -> var instanceof LocalVar);
  }

  default @NotNull Context bind(
    @NotNull String name,
    @NotNull LocalVar ref,
    @NotNull SourcePos sourcePos,
    @NotNull Predicate<@Nullable AnyVar> toWarn
  ) {
    // do not bind ignored var, and users should not try to use it
    if (ref == LocalVar.IGNORED) return this;
    var exists = getUnqualifiedMaybe(name, sourcePos);
    if (toWarn.test(exists)
      && (!(ref.generateKind() instanceof GenerateKind.Anonymous))) {
      reporter().report(new NameProblem.ShadowingWarn(name, sourcePos));
    }
    return new BindContext(this, name, ref);
  }

  default @NotNull PhysicalModuleContext derive(@NotNull String extraName) {
    return new PhysicalModuleContext(this, this.moduleName().appended(extraName));
  }

  default @NotNull PhysicalModuleContext derive(@NotNull Seq<@NotNull String> extraName) {
    return new PhysicalModuleContext(this, this.moduleName().concat(extraName));
  }

  class ResolvingInterruptedException extends InterruptException {
    @Override public InterruptStage stage() {
      return InterruptStage.Resolving;
    }
  }
}
