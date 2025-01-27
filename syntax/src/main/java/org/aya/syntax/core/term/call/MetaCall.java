// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.syntax.core.term.call;

import kala.collection.immutable.ImmutableSeq;
import kala.function.IndexedFunction;
import org.aya.generic.term.DTKind;
import org.aya.syntax.core.term.AppTerm;
import org.aya.syntax.core.term.DepTypeTerm;
import org.aya.syntax.core.term.SortTerm;
import org.aya.syntax.core.term.Term;
import org.aya.syntax.core.term.marker.BetaRedex;
import org.aya.syntax.core.term.marker.TyckInternal;
import org.aya.syntax.ref.MetaVar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.UnaryOperator;

/**
 * @param args can grow!! See {@link BetaRedex#make(java.util.function.UnaryOperator)}
 */
public record MetaCall(
  @NotNull MetaVar ref,
  @Override @NotNull ImmutableSeq<Term> args
) implements Callable, TyckInternal {
  public static @NotNull Term app(Term rhs, ImmutableSeq<Term> args, int ctxSize) {
    var directArgs = args.sliceView(0, ctxSize);
    var restArgs = args.sliceView(ctxSize, args.size());
    return AppTerm.make(rhs.instantiateTele(directArgs), restArgs);
  }
  public static @NotNull Term appType(MetaCall call, Term rhsType) {
    var ref = call.ref;
    var args = call.args;
    var directArgs = args.sliceView(0, ref.ctxSize());
    var restArgs = args.sliceView(ref.ctxSize(), args.size());
    return DepTypeTerm.substBody(rhsType.instantiateTele(directArgs), restArgs);
  }

  public @NotNull MetaCall asPiDom(@NotNull SortTerm result) {
    return ref.asPiDom(result, args);
  }

  public @Nullable DepTypeTerm asDt(UnaryOperator<Term> whnf, String dom, String cod, DTKind kind) {
    return ref.asDt(whnf, dom, cod, kind, args);
  }

    public @NotNull Term update(@NotNull ImmutableSeq<Term> args) {
    return args.sameElements(args(), true) ? this : new MetaCall(ref, args);
  }

  @Override public @NotNull Term descent(@NotNull IndexedFunction<Term, Term> f) {
    return update(Callable.descent(args, f));
  }
}
