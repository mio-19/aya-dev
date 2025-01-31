// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.normalize;

import kala.collection.SeqView;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.immutable.ImmutableSet;
import kala.control.Either;
import kala.control.Option;
import kala.control.Result;
import org.aya.generic.Modifier;
import org.aya.syntax.compile.JitFn;
import org.aya.syntax.core.def.FnDef;
import org.aya.syntax.core.pat.PatMatcher;
import org.aya.syntax.core.term.*;
import org.aya.syntax.core.term.call.*;
import org.aya.syntax.core.term.marker.BetaRedex;
import org.aya.syntax.core.term.marker.StableWHNF;
import org.aya.syntax.core.term.repr.MetaLitTerm;
import org.aya.syntax.core.term.xtt.CoeTerm;
import org.aya.syntax.core.term.xtt.DimTerm;
import org.aya.syntax.literate.CodeOptions.NormalizeMode;
import org.aya.syntax.ref.AnyVar;
import org.aya.syntax.ref.LocalVar;
import org.aya.tyck.TyckState;
import org.aya.tyck.tycker.Stateful;
import org.aya.util.error.WithPos;
import org.jetbrains.annotations.NotNull;

import java.util.function.UnaryOperator;

import static org.aya.generic.State.Stuck;

/**
 * Unlike in pre-v0.30 Aya, we use only one normalizer, only doing head reduction,
 * and we merge conservative normalizer and the whnf normalizer.
 * <p>
 * Even though it has a field {@link #state}, do not make it extend {@link Stateful},
 * because there is a method called whnf in it, which clashes with the one here.
 */
public final class Normalizer implements UnaryOperator<Term> {
  public final @NotNull TyckState state;
  public @NotNull ImmutableSet<AnyVar> opaque = ImmutableSet.empty();
  private boolean usePostTerm = false;
  public Normalizer(@NotNull TyckState state) { this.state = state; }

  @SuppressWarnings("UnnecessaryContinue") @Override public Term apply(Term term) {
    while (true) {
      if (term instanceof StableWHNF || term instanceof FreeTerm) return term;
      // ConCall for point constructors are always in WHNF
      if (term instanceof ConCall con && !con.ref().hasEq()) return con;
      var descentedTerm = term.descent(this);
      // descent may change the java type of term, i.e. beta reduce,
      // and can also reduce the subterms. We intend to return the reduction
      // result when it beta reduces, so keep `descentedTerm` both when in NF mode or
      // the term is not a call term.
      var defaultValue = usePostTerm || term instanceof BetaRedex ? descentedTerm : term;

      switch (descentedTerm) {
        case StableWHNF _, FreeTerm _ -> {
          return descentedTerm;
        }
        case BetaRedex app -> {
          var result = app.make(this);
          if (result == app) return defaultValue;
          term = result;
          continue;
        }
        case FnCall(var fn, int ulift, var args) -> {
          switch (fn) {
            case JitFn instance -> {
              var result = instance.invoke(() -> defaultValue, args);
              if (defaultValue != result) {
                term = result.elevate(ulift);
                continue;
              }
              return result;
            }
            case FnDef.Delegate delegate -> {
              FnDef core = delegate.core();
              if (core == null) return defaultValue;
              if (!isOpaque(core)) switch (core.body()) {
                case Either.Left(var body): {
                  term = body.instantiateTele(args.view());
                  continue;
                }
                case Either.Right(var clauses): {
                  var result = tryUnfoldClauses(clauses.view().map(WithPos::data),
                    args, ulift, core.is(Modifier.Overlap));
                  // we may get stuck
                  if (result.isEmpty()) return defaultValue;
                  term = result.get();
                  continue;
                }
              }
              return defaultValue;
            }
          }
        }
        case RuleReducer reduceRule -> {
          var result = reduceRule.rule().apply(reduceRule.args());
          if (result != null) {
            term = result;
            continue;
          }
          // We can't handle it, try to delegate to FnCall
          switch (reduceRule) {
            case RuleReducer.Fn fn -> {
              term = fn.toFnCall();
              continue;
            }
            case RuleReducer.Con _ -> {
              return descentedTerm;
            }
          }
        }
        case ConCall(var head, _) when !head.ref().hasEq() -> {
          return descentedTerm;
        }
        case ConCall call when call.conArgs().getLast() instanceof DimTerm dim -> {
          return call.head().ref().equality(call.args(), dim == DimTerm.I0);
        }
        case PrimCall prim -> {
          return state.primFactory.unfold(prim, state);
        }
        case MetaPatTerm meta -> {
          return meta.inline(this);
        }
        case MetaCall meta -> {
          return state.computeSolution(meta, this);
        }
        case MetaLitTerm meta -> {
          return meta.inline(this);
        }
        case CoeTerm coe -> {
          var r = coe.r();
          var s = coe.s();
          var A = coe.type();
          if (state.isConnected(r, s)) return LamTerm.ID;

          var i = new LocalVar("i");
          switch (apply(A.apply(i))) {
            case DepTypeTerm dep -> {
              term = dep.coe(i, coe);
              continue;
            }
            case SortTerm _ -> {
              return LamTerm.ID;
            }
            // TODO: when the data is not indexed, also return ID
            case DataCall data when data.args().isEmpty() -> {
              return LamTerm.ID;
            }
            case null, default -> {
              return defaultValue;
            }
          }
        }
        case MatchTerm matchTerm -> {
          var result = tryUnfoldClauses(matchTerm.clauses().view(), matchTerm.discriminant(), 0, false);
          if (result.isEmpty()) return defaultValue;
          term = result.get();
          continue;
        }
        default -> {
          return defaultValue;
        }
      }
    }
  }

  private boolean isOpaque(@NotNull FnDef fn) {
    return opaque.contains(fn.ref()) || fn.is(Modifier.Opaque) || fn.is(Modifier.Partial);
  }

  public @NotNull Option<Term> tryUnfoldClauses(
    @NotNull SeqView<Term.Matching> clauses, @NotNull ImmutableSeq<Term> args,
    int ulift, boolean orderIndependent
  ) {
    for (var matchy : clauses) {
      var matcher = new PatMatcher(false, this);
      switch (matcher.apply(matchy.patterns(), args)) {
        case Result.Err(var st) -> {
          if (!orderIndependent && st == Stuck) return Option.none();
        }
        case Result.Ok(var subst) -> {
          return Option.some(matchy.body().elevate(ulift).instantiateTele(subst.view()));
        }
      }
    }
    return Option.none();
  }

  private class Full implements UnaryOperator<Term> {
    { usePostTerm = true; }

    @Override public Term apply(Term term) { return Normalizer.this.apply(term).descent(this); }
  }

  /**
   * Do NOT use this in the type checker.
   * This is for REPL/literate mode and testing.
   */
  public @NotNull Term normalize(Term term, NormalizeMode mode) {
    return switch (mode) {
      case HEAD -> apply(term);
      case FULL -> new Full().apply(term);
      case NULL -> new Finalizer.Freeze(() -> state).zonk(term);
      case null -> new Finalizer.Freeze(() -> state).zonk(term);
    };
  }
}
