// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.repl;

import kala.collection.Seq;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.DynamicSeq;
import kala.control.Either;
import kala.value.Ref;
import org.aya.api.error.CountingReporter;
import org.aya.api.error.DelayedReporter;
import org.aya.api.error.Reporter;
import org.aya.api.error.SourceFileLocator;
import org.aya.api.util.InterruptException;
import org.aya.api.util.NormalizeMode;
import org.aya.cli.library.LibraryCompiler;
import org.aya.cli.library.source.LibraryOwner;
import org.aya.cli.parse.AyaParserImpl;
import org.aya.cli.single.CompilerFlags;
import org.aya.cli.single.SingleFileCompiler;
import org.aya.concrete.Expr;
import org.aya.concrete.resolve.context.EmptyContext;
import org.aya.concrete.resolve.context.PhysicalModuleContext;
import org.aya.concrete.resolve.module.CachedModuleLoader;
import org.aya.concrete.resolve.module.FileModuleLoader;
import org.aya.concrete.resolve.module.ModuleListLoader;
import org.aya.concrete.stmt.Stmt;
import org.aya.core.def.Def;
import org.aya.core.term.Term;
import org.aya.tyck.ExprTycker;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReplCompiler {
  final @NotNull CountingReporter reporter;
  private final @Nullable SourceFileLocator locator;
  private final @NotNull ReplContext context;
  private final @NotNull ImmutableSeq<Path> modulePaths;
  private final @NotNull CompilerFlags flags;

  ReplCompiler(@NotNull ImmutableSeq<Path> modulePaths, @NotNull Reporter reporter, @Nullable SourceFileLocator locator) {
    this.modulePaths = modulePaths;
    this.reporter = CountingReporter.delegate(reporter);
    this.locator = locator;
    this.context = new ReplContext(new EmptyContext(this.reporter, Path.of("REPL")), ImmutableSeq.of("REPL"));
    this.flags = new CompilerFlags(CompilerFlags.Message.EMOJI, false, true, null,
      modulePaths.view(), null);
  }

  private @NotNull ExprTycker.Result tyckExpr(@NotNull Expr expr) {
    var resolvedExpr = expr.resolve(context);
    // in case we have un-messaged TyckException
    try (var delayedReporter = new DelayedReporter(reporter)) {
      var tycker = new ExprTycker(delayedReporter, null);
      return tycker.zonk(expr, tycker.synthesize(resolvedExpr.desugar(delayedReporter)));
    }
  }

  /** @see ReplCompiler#compileExpr(String, NormalizeMode) */
  public int loadToContext(@NotNull Path file) throws IOException {
    if (Files.isDirectory(file)) return loadLibrary(file);
    return loadFile(file);
  }

  private int loadLibrary(@NotNull Path libraryRoot) throws IOException {
    var compiler = LibraryCompiler.newCompiler(reporter, flags, libraryRoot);
    int result = compiler.start();
    var owner = compiler.libraryOwner();
    importModule(owner);
    return result;
  }

  private void importModule(@NotNull LibraryOwner owner) {
    owner.librarySources()
      .map(src -> src.resolveInfo().value.thisModule())
      .filterIsInstance(PhysicalModuleContext.class)
      .forEach(mod -> mod.exports.forEach((name, contents) -> context.importModule(
        Stmt.Accessibility.Public,
        SourcePos.NONE,
        name,
        contents
      )));
    owner.libraryDeps().forEach(this::importModule);
  }

  private int loadFile(@NotNull Path file) throws IOException {
    return new SingleFileCompiler(reporter, null, null)
      .compile(file, r -> context, flags, null);
  }

  /**
   * Copied and adapted.
   *
   * @param text the text of code to compile, witch might either be a `program` or an `expr`.
   * @see org.aya.cli.single.SingleFileCompiler#compile
   */
  public @NotNull Either<ImmutableSeq<Def>, Term> compileToContext(@NotNull String text, @NotNull NormalizeMode normalizeMode) {
    if (text.isBlank()) return Either.left(ImmutableSeq.empty());
    var locator = this.locator != null ? this.locator : new SourceFileLocator.Module(modulePaths);
    var programOrExpr = AyaParserImpl.repl(reporter, text);
    try {
      var loader = new CachedModuleLoader<>(new ModuleListLoader(reporter, modulePaths.view().map(path ->
        new FileModuleLoader(locator, path, reporter, new AyaParserImpl(reporter), null)).toImmutableSeq()));
      return programOrExpr.map(
        program -> {
          var newDefs = new Ref<ImmutableSeq<Def>>();
          loader.tyckModule(context, program, null, ((moduleResolve, defs) -> newDefs.set(defs)));
          var defs = newDefs.get();
          if (reporter.noError()) return defs;
          else {
            // When there are errors, we need to remove the defs from the context.
            var toRemoveDef = DynamicSeq.<String>create();
            context.definitions.forEach((name, mod) -> {
              var toRemoveMod = DynamicSeq.<Seq<String>>create();
              mod.forEach((modName, def) -> {
                if (defs.anyMatch(realDef -> realDef.ref() == def)) toRemoveMod.append(modName);
              });
              if (toRemoveMod.sizeEquals(mod.size())) toRemoveDef.append(name);
              else toRemoveMod.forEach(mod::remove);
            });
            toRemoveDef.forEach(context.definitions::remove);
            return ImmutableSeq.empty();
          }
        },
        expr -> tyckExpr(expr).wellTyped().normalize(null, normalizeMode)
      );
    } catch (InterruptException ignored) {
      return Either.left(ImmutableSeq.empty());
    }
  }

  /**
   * Adapted.
   *
   * @see #loadToContext
   */
  public @Nullable Term compileExpr(@NotNull String text, @NotNull NormalizeMode normalizeMode) {
    try {
      return tyckExpr(AyaParserImpl.replExpr(reporter, text)).type().normalize(null, normalizeMode);
    } catch (InterruptException ignored) {
      return null;
    }
  }

  public @NotNull ReplContext getContext() {
    return context;
  }
}
