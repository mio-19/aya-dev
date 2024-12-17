// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.compiler.serializers;

import kala.collection.immutable.ImmutableSeq;
import org.aya.compiler.free.FreeClassBuilder;
import org.aya.compiler.free.FreeExprBuilder;
import org.aya.compiler.free.FreeJavaExpr;
import org.aya.compiler.free.FreeUtil;
import org.aya.compiler.serializers.ModuleSerializer.MatchyRecorder;
import org.aya.syntax.compile.CompiledAya;
import org.aya.syntax.core.def.TyckDef;
import org.aya.syntax.core.repr.CodeShape;
import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Annotation;
import java.util.function.Consumer;

public abstract class JitDefSerializer<T extends TyckDef> extends ClassTargetSerializer<T> {
  protected JitDefSerializer(@NotNull Class<?> superClass, @NotNull MatchyRecorder recorder) {
    super(superClass, recorder);
  }

  private static @NotNull CompiledAya mkCompiledAya(
    @NotNull String[] module, int fileModuleSize,
    @NotNull String name, int assoc, int shape,
    @NotNull CodeShape.GlobalId[] recognition
  ) {
    return new CompiledAya() {
      @Override public Class<? extends Annotation> annotationType() { return CompiledAya.class; }
      @Override public @NotNull String[] module() { return module; }
      @Override public int fileModuleSize() { return fileModuleSize; }
      @Override public @NotNull String name() { return name; }
      @Override public int assoc() { return assoc; }
      @Override public int shape() { return shape; }
      @Override public @NotNull CodeShape.GlobalId[] recognition() { return recognition; }
    };
  }

  /**
   * @see CompiledAya
   */
  protected @NotNull CompiledAya buildMetadata(@NotNull T unit) {
    var ref = unit.ref();
    var module = ref.module;
    var assoc = ref.assoc();
    var assocIdx = assoc == null ? -1 : assoc.ordinal();
    assert module != null;
    return mkCompiledAya(
      module.module().module().toArray(String.class),
      module.fileModuleSize(),
      ref.name(),
      assocIdx,
      buildShape(unit),
      buildRecognition(unit)
    );
  }

  protected int buildShape(T unit) { return -1; }
  protected CodeShape.GlobalId[] buildRecognition(T unit) { return new CodeShape.GlobalId[0]; }

  protected abstract boolean shouldBuildEmptyCall(@NotNull T unit);

  protected abstract @NotNull Class<?> callClass();

  protected final FreeJavaExpr buildEmptyCall(@NotNull FreeExprBuilder builder, @NotNull TyckDef def) {
    return builder.mkNew(callClass(), ImmutableSeq.of(AbstractExprializer.getInstance(builder, def)));
  }

  @Override protected @NotNull String className(T unit) {
    return NameSerializer.javifyClassName(unit.ref());
  }

  protected void buildFramework(@NotNull FreeClassBuilder builder, @NotNull T unit, @NotNull Consumer<FreeClassBuilder> continuation) {
    var metadata = buildMetadata(unit);
    super.buildFramework(metadata, builder, unit, nestBuilder -> {
      if (shouldBuildEmptyCall(unit)) {
        nestBuilder.buildConstantField(FreeUtil.fromClass(callClass()),
          AyaSerializer.FIELD_EMPTYCALL, cb ->
            buildEmptyCall(cb, unit));
      }

      continuation.accept(nestBuilder);
    });
  }

  @Override
  public abstract @NotNull JitDefSerializer<T> serialize(@NotNull FreeClassBuilder builder, T unit);
}
