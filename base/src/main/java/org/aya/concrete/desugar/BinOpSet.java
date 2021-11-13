// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.desugar;

import kala.collection.mutable.MutableSet;
import org.aya.api.error.Reporter;
import org.aya.api.util.Assoc;
import org.aya.concrete.desugar.error.OperatorProblem;
import org.aya.concrete.resolve.context.Context;
import org.aya.concrete.stmt.OpDecl;
import org.aya.util.MutableGraph;
import org.aya.util.error.SourcePos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record BinOpSet(
  @NotNull Reporter reporter,
  @NotNull MutableSet<BinOP> ops,
  @NotNull MutableGraph<BinOP> tighterGraph
) {
  static final @NotNull BinOpSet.BinOP APP_ELEM = BinOP.from(SourcePos.NONE, OpDecl.APPLICATION);

  public BinOpSet(@NotNull Reporter reporter) {
    this(reporter, MutableSet.of(APP_ELEM), MutableGraph.create());
  }

  public void bind(@NotNull OpDecl op, @NotNull OpDecl.BindPred pred, @NotNull OpDecl target, @NotNull SourcePos sourcePos) {
    var opElem = ensureHasElem(op, sourcePos);
    var targetElem = ensureHasElem(target, sourcePos);
    if (opElem == targetElem) {
      reporter.report(new OperatorProblem.BindSelfError(sourcePos));
      throw new Context.ResolvingInterruptedException();
    }
    switch (pred) {
      case Tighter -> addTighter(opElem, targetElem);
      case Looser -> addTighter(targetElem, opElem);
    }
  }

  public PredCmp compare(@NotNull BinOpSet.BinOP lhs, @NotNull BinOpSet.BinOP rhs) {
    // BinOp all have lower priority than application
    if (lhs == APP_ELEM) return PredCmp.Tighter;
    if (rhs == APP_ELEM) return PredCmp.Looser;
    if (lhs == rhs) return PredCmp.Equal;
    if (tighterGraph.hasPath(lhs, rhs)) return PredCmp.Tighter;
    if (tighterGraph.hasPath(rhs, lhs)) return PredCmp.Looser;
    return PredCmp.Undefined;
  }

  public Assoc assocOf(@Nullable OpDecl opDecl) {
    if (isOperand(opDecl)) return Assoc.Invalid;
    return ensureHasElem(opDecl).assoc;
  }

  public boolean isOperand(@Nullable OpDecl opDecl) {
    return opDecl == null || opDecl.opInfo() == null;
  }

  public BinOP ensureHasElem(@NotNull OpDecl opDecl) {
    return ensureHasElem(opDecl, SourcePos.NONE);
  }

  public BinOP ensureHasElem(@NotNull OpDecl opDecl, @NotNull SourcePos sourcePos) {
    var elem = ops.find(e -> e.op == opDecl);
    if (elem.isDefined()) return elem.get();
    var newElem = BinOP.from(sourcePos, opDecl);
    ops.add(newElem);
    return newElem;
  }

  private void addTighter(@NotNull BinOpSet.BinOP from, @NotNull BinOpSet.BinOP to) {
    tighterGraph.suc(to);
    tighterGraph.suc(from).append(to);
  }

  public void reportIfCyclic() {
    var cycles = tighterGraph.findCycles();
    if (cycles.isNotEmpty()) {
      cycles.forEach(c -> reporter.report(new OperatorProblem.Circular(c)));
      throw new Context.ResolvingInterruptedException();
    }
  }

  public record BinOP(
    @NotNull SourcePos firstBind,
    @NotNull OpDecl op,
    @NotNull String name,
    @NotNull Assoc assoc
  ) {
    private static @NotNull OpDecl.OpInfo ensureOperator(@NotNull OpDecl opDecl) {
      var op = opDecl.opInfo();
      if (op == null) throw new IllegalArgumentException("not an operator");
      return op;
    }

    private static @NotNull BinOpSet.BinOP from(@NotNull SourcePos sourcePos, @NotNull OpDecl opDecl) {
      var op = ensureOperator(opDecl);
      return new BinOP(sourcePos, opDecl, op.name(), op.assoc());
    }
  }

  public enum PredCmp {
    Looser,
    Tighter,
    Undefined,
    Equal,
  }
}
