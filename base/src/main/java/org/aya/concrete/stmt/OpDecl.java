// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.stmt;

import kala.collection.immutable.ImmutableSeq;
import kala.value.Ref;
import org.aya.api.error.SourcePos;
import org.aya.api.util.Assoc;
import org.aya.concrete.resolve.context.Context;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface OpDecl {
  enum BindPred {
    Tighter("tighter"),
    Looser("looser");

    public final @NotNull String keyword;

    BindPred(@NotNull String keyword) {
      this.keyword = keyword;
    }
  }

  /**
   * @author kiva
   */
  record BindBlock(
    @Override @NotNull SourcePos sourcePos,
    @NotNull Ref<@Nullable Context> context,
    @NotNull ImmutableSeq<QualifiedID> loosers,
    @NotNull ImmutableSeq<QualifiedID> tighters,
    @NotNull Ref<ImmutableSeq<OpDecl>> resolvedLoosers,
    @NotNull Ref<ImmutableSeq<OpDecl>> resolvedTighters
  ) {
  }

  @Nullable OpInfo opInfo();

  record OpInfo(@NotNull String name, @NotNull Assoc assoc) {
  }

  @NotNull OpDecl APPLICATION = () -> new OpInfo("application", Assoc.InfixL);
}
