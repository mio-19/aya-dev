// Copyright (c) 2020-2024 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.compiler.serializers;

import kala.collection.immutable.ImmutableArray;
import kala.collection.immutable.ImmutableSeq;
import kala.collection.mutable.MutableLinkedHashMap;
import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import org.aya.compiler.free.ArgumentProvider;
import org.aya.compiler.free.Constants;
import org.aya.compiler.free.FreeExprBuilder;
import org.aya.compiler.free.FreeJavaExpr;
import org.aya.compiler.free.data.MethodRef;
import org.aya.compiler.serializers.ModuleSerializer.MatchyRecorder;
import org.aya.generic.stmt.Shaped;
import org.aya.prettier.FindUsage;
import org.aya.syntax.compile.JitFn;
import org.aya.syntax.core.Closure;
import org.aya.syntax.core.def.AnyDef;
import org.aya.syntax.core.def.FnDef;
import org.aya.syntax.core.def.Matchy;
import org.aya.syntax.core.term.*;
import org.aya.syntax.core.term.call.*;
import org.aya.syntax.core.term.marker.TyckInternal;
import org.aya.syntax.core.term.repr.*;
import org.aya.syntax.core.term.xtt.*;
import org.aya.syntax.ref.LocalVar;
import org.aya.util.error.Panic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.constant.ClassDesc;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Build the "constructor form" of {@link Term}, but in Java.
 */
public final class TermExprializer extends AbstractExprializer<Term> {
  /**
   * Terms that should be instantiated
   */
  private final @NotNull ImmutableSeq<FreeJavaExpr> instantiates;
  private final @NotNull MutableLinkedHashMap<LocalVar, FreeJavaExpr> binds;

  /**
   * Whether allow LocalTerm, false in default (in order to report unexpected LocalTerm)
   */
  private final boolean allowLocalTerm;
  private final @NotNull MatchyRecorder recorder;

  public TermExprializer(
    @NotNull FreeExprBuilder builder, @NotNull ImmutableSeq<FreeJavaExpr> instantiates,
    @NotNull MatchyRecorder recorder
  ) {
    this(builder, instantiates, false, recorder);
  }

  public TermExprializer(
    @NotNull FreeExprBuilder builder, @NotNull ImmutableSeq<FreeJavaExpr> instantiates,
    boolean allowLocalTer, @NotNull MatchyRecorder recorder
  ) {
    super(builder);
    this.instantiates = instantiates;
    this.allowLocalTerm = allowLocalTer;
    this.binds = MutableLinkedHashMap.of();
    this.recorder = recorder;
  }

  private TermExprializer(
    @NotNull FreeExprBuilder builder,
    @NotNull MutableLinkedHashMap<LocalVar, FreeJavaExpr> newBinds,
    boolean allowLocalTerm, @NotNull MatchyRecorder recorder
  ) {
    super(builder);
    this.instantiates = ImmutableSeq.empty();
    this.binds = newBinds;
    this.allowLocalTerm = allowLocalTerm;
    this.recorder = recorder;
  }

  private @NotNull FreeJavaExpr serializeApplicable(@NotNull Shaped.Applicable<?> applicable) {
    return switch (applicable) {
      case IntegerOps.ConRule conRule -> builder.mkNew(IntegerOps.ConRule.class, ImmutableSeq.of(
        getInstance(conRule.ref()),
        doSerialize(conRule.zero())
      ));
      case IntegerOps.FnRule fnRule -> builder.mkNew(IntegerOps.FnRule.class, ImmutableSeq.of(
        getInstance(fnRule.ref()),
        builder.refEnum(fnRule.kind())
      ));
      case ListOps.ConRule conRule -> builder.mkNew(ListOps.ConRule.class, ImmutableSeq.of(
        getInstance(conRule.ref()),
        doSerialize(conRule.empty())
      ));
      default -> Panic.unreachable();
    };
  }

  /**
   * TODO: remove {@param fixed}, it is used by FnCall only
   * <p>
   * This code requires that {@link FnCall}, {@link RuleReducer.Fn} and {@link RuleReducer.Con}
   * {@code ulift} is the second parameter, {@code args.get(i)} is the {@code i + 3}th parameter
   *
   * @param reducibleType the ref class of {@param reducible}, used when {@param fixed} is true
   * @param fixed         whether {@param reducible} has fixed `invoke`,
   *                      i.e. {@link JitFn} does but {@link org.aya.generic.stmt.Shaped.Applicable} doesn't.
   */
  private @NotNull FreeJavaExpr buildReducibleCall(
    @Nullable ClassDesc reducibleType,
    @NotNull FreeJavaExpr reducible,
    @NotNull Class<?> callName,
    int ulift,
    @NotNull ImmutableSeq<ImmutableSeq<Term>> args,
    boolean fixed
  ) {
    var seredArgs = args.map(x -> x.map(this::doSerialize));
    var seredSeq = seredArgs.map(x -> makeImmutableSeq(Term.class, x));
    var flatArgs = seredArgs.flatMap(x -> x);

    var callArgs = new FreeJavaExpr[seredSeq.size() + 2];
    callArgs[0] = reducible;
    callArgs[1] = builder.iconst(0); // elevate later
    for (var i = 0; i < seredSeq.size(); ++i) {
      callArgs[i + 2] = seredSeq.get(i);
    }

    var onStuck = makeThunk(te -> te.builder.mkNew(callName, ImmutableArray.Unsafe.wrap(callArgs)));
    var finalInvocation = fixed
      ? builder.invoke(
      FnSerializer.resolveInvoke(Objects.requireNonNull(reducibleType), flatArgs.size()),
      reducible,
      flatArgs.view().prepended(onStuck).toImmutableSeq())
      : builder.invoke(Constants.REDUCIBLE_INVOKE, reducible, ImmutableSeq.of(
        onStuck,
        makeImmutableSeq(Term.class, flatArgs)
      ));

    return ulift == 0
      ? finalInvocation
      : builder.invoke(Constants.ELEVATE, finalInvocation, ImmutableSeq.of(builder.iconst(ulift)));
  }

  @Override protected @NotNull FreeJavaExpr doSerialize(@NotNull Term term) {
    return switch (term) {
      case FreeTerm(var bind) -> {
        // It is possible that we meet bind here,
        // the serializer will instantiate some variable while serializing LamTerm
        var subst = binds.getOrNull(bind);
        if (subst == null) {
          throw new Panic("No substitution for " + bind + " during serialization");
        }

        yield subst;
      }
      case TyckInternal i -> throw new Panic(i.getClass().toString());
      case Callable.SharableCall call when call.ulift() == 0 && call.args().isEmpty() ->
        getCallInstance(CallKind.from(call), call.ref());
      case ClassCall(var ref, var ulift, var args) -> builder.mkNew(ClassCall.class, ImmutableSeq.of(
        getInstance(ref),
        builder.iconst(ulift),
        serializeClosureToImmutableSeq(args)
      ));
      case MemberCall(var of, var ref, var ulift, var args) -> builder.mkNew(MemberCall.class, ImmutableSeq.of(
        doSerialize(of),
        getInstance(ref),
        builder.iconst(ulift),
        serializeToImmutableSeq(Term.class, args)
      ));
      case AppTerm appTerm -> makeAppNew(AppTerm.class, appTerm.fun(), appTerm.arg());
      case LocalTerm _ when !allowLocalTerm -> throw new Panic("LocalTerm");
      case LocalTerm(var index) -> builder.mkNew(LocalTerm.class, ImmutableSeq.of(builder.iconst(index)));
      case LamTerm lamTerm -> builder.mkNew(LamTerm.class, ImmutableSeq.of(serializeClosure(lamTerm.body())));
      case DataCall(var ref, var ulift, var args) -> builder.mkNew(DataCall.class, ImmutableSeq.of(
        getInstance(ref),
        builder.iconst(ulift),
        serializeToImmutableSeq(Term.class, args)
      ));
      case ConCall(var head, var args) -> builder.mkNew(ConCall.class, ImmutableSeq.of(
        getInstance(head.ref()),
        serializeToImmutableSeq(Term.class, head.ownerArgs()),
        builder.iconst(head.ulift()),
        serializeToImmutableSeq(Term.class, args)
      ));
      case FnCall call -> {
        var anyDef = switch (call.ref()) {
          case JitFn jit -> jit;
          case FnDef.Delegate def -> AnyDef.fromVar(def.ref);
        };

        var ref = getInstance(anyDef);
        var args = call.args();
        yield buildReducibleCall(NameSerializer.getClassDesc(anyDef), ref, FnCall.class, call.ulift(), ImmutableSeq.of(args), true);
      }
      case RuleReducer.Con conRuler -> buildReducibleCall(
        null,
        serializeApplicable(conRuler.rule()),
        RuleReducer.Con.class, conRuler.ulift(),
        ImmutableSeq.of(conRuler.ownerArgs(), conRuler.conArgs()),
        false
      );
      case RuleReducer.Fn fnRuler -> buildReducibleCall(
        null,
        serializeApplicable(fnRuler.rule()),
        RuleReducer.Fn.class, fnRuler.ulift(),
        ImmutableSeq.of(fnRuler.args()),
        false
      );
      // TODO: make the resolving const
      case SortTerm sort when sort.equals(SortTerm.Type0) ->
        builder.refField(builder.resolver().resolve(SortTerm.class, "Type0"));
      case SortTerm sort when sort.equals(SortTerm.ISet) ->
        builder.refField(builder.resolver().resolve(SortTerm.class, "ISet"));
      case SortTerm(var kind, var ulift) ->
        builder.mkNew(SortTerm.class, ImmutableSeq.of(builder.refEnum(kind), builder.iconst(ulift)));
      case DepTypeTerm(var kind, var param, var body) -> builder.mkNew(DepTypeTerm.class, ImmutableSeq.of(
        builder.refEnum(kind),
        doSerialize(param),
        serializeClosure(body)
      ));
      case CoeTerm(var type, var r, var s) -> builder.mkNew(CoeTerm.class, ImmutableSeq.of(
        serializeClosure(type),
        doSerialize(r),
        doSerialize(s)
      ));
      case ProjTerm(var of, var fst) -> builder.mkNew(ProjTerm.class, ImmutableSeq.of(
        doSerialize(of),
        builder.iconst(fst)
      ));
      case PAppTerm(var fun, var arg, var a, var b) -> makeAppNew(PAppTerm.class,
        fun, arg, a, b
      );
      case EqTerm(var A, var a, var b) -> builder.mkNew(EqTerm.class, ImmutableSeq.of(
        serializeClosure(A),
        doSerialize(a), doSerialize(b)
      ));
      case DimTyTerm _ -> builder.refEnum(DimTyTerm.INSTANCE);
      case DimTerm dim -> builder.refEnum(dim);
      case TupTerm(var l, var r) -> builder.mkNew(TupTerm.class, ImmutableSeq.of(
        doSerialize(l), doSerialize(r)
      ));
      case PrimCall(var ref, var ulift, var args) -> builder.mkNew(PrimCall.class, ImmutableSeq.of(
        getInstance(ref),
        builder.iconst(ulift),
        serializeToImmutableSeq(Term.class, args)
      ));
      case IntegerTerm(var repr, var zero, var suc, var type) -> builder.mkNew(IntegerTerm.class, ImmutableSeq.of(
        builder.iconst(repr),
        getInstance(zero),
        getInstance(suc),
        doSerialize(type)
      ));
      case ListTerm(var repr, var nil, var cons, var type) -> builder.mkNew(ListTerm.class, ImmutableSeq.of(
        makeImmutableSeq(builder, Constants.IMMTREESEQ, Term.class, repr.map(this::doSerialize)),
        getInstance(nil),
        getInstance(cons),
        doSerialize(type)
      ));
      case StringTerm stringTerm -> builder.mkNew(StringTerm.class, ImmutableSeq.of(
        builder.aconst(stringTerm.string())
      ));
      case ClassCastTerm(var classRef, var subterm, var rember, var forgor) ->
        builder.mkNew(ClassCastTerm.class, ImmutableSeq.of(
          getInstance(classRef),
          serialize(subterm),
          serializeClosureToImmutableSeq(rember),
          serializeClosureToImmutableSeq(forgor)
        ));
      case MatchCall(var ref, var args, var captures) -> {
        if (ref instanceof Matchy matchy) recorder.addMatchy(matchy, args.size(), captures.size());
        yield builder.mkNew(MatchCall.class, ImmutableSeq.of(
          getInstance(ref),
          serializeToImmutableSeq(Term.class, args),
          serializeToImmutableSeq(Term.class, captures)
        ));
      }
      case NewTerm(var classCall) -> builder.mkNew(NewTerm.class, ImmutableSeq.of(doSerialize(classCall)));
    };
  }

  private @NotNull FreeJavaExpr makeLambda(
    @NotNull MethodRef lambdaType,
    @NotNull BiFunction<ArgumentProvider, TermExprializer, FreeJavaExpr> cont
  ) {
    var binds = MutableLinkedHashMap.from(this.binds);
    var entries = binds.toImmutableSeq();
    return builder.mkLambda(entries.map(Tuple2::component2), lambdaType, (ap, builder) -> {
      var captured = entries.mapIndexed((i, tup) -> Tuple.of(tup.component1(), ap.capture(i)));
      var result = cont.apply(ap, new TermExprializer(this.builder,
        MutableLinkedHashMap.from(captured), this.allowLocalTerm, recorder));
      builder.returnWith(result);
    });
  }

  // TODO: unify with makeClosure
  private @NotNull FreeJavaExpr makeThunk(@NotNull Function<TermExprializer, FreeJavaExpr> cont) {
    return makeLambda(Constants.THUNK, (_, te) -> cont.apply(te));
  }

  private @NotNull FreeJavaExpr makeClosure(@NotNull BiFunction<TermExprializer, FreeJavaExpr, FreeJavaExpr> cont) {
    return makeLambda(Constants.CLOSURE, (ap, te) -> cont.apply(te, ap.arg(0).ref()));
  }

  private @NotNull FreeJavaExpr serializeClosureToImmutableSeq(@NotNull ImmutableSeq<Closure> cls) {
    return makeImmutableSeq(Closure.class, cls.map(this::serializeClosure));
  }

  private @NotNull FreeJavaExpr with(
    @NotNull LocalVar var,
    @NotNull FreeJavaExpr subst,
    @NotNull Supplier<FreeJavaExpr> continuation
  ) {
    this.binds.put(var, subst);
    var result = continuation.get();
    this.binds.remove(var);
    return result;
  }

  private @NotNull FreeJavaExpr serializeClosure(@NotNull Closure body) {
    if (body instanceof Closure.Const(var inside)) return serializeConst(inside);

    var var = new LocalVar("<jit>");
    var appliedBody = body.apply(var);
    if (FindUsage.free(appliedBody, var) == 0) return serializeConst(appliedBody);

    var closure = makeClosure((te, arg) ->
      te.with(var, arg, () -> te.doSerialize(appliedBody)));

    return builder.mkNew(Closure.Jit.class, ImmutableSeq.of(closure));
  }

  private @NotNull FreeJavaExpr serializeConst(Term appliedBody) {
    return builder.invoke(Constants.CLOSURE_MKCONST, ImmutableSeq.of(doSerialize(appliedBody)));
  }

  private @NotNull FreeJavaExpr makeAppNew(@NotNull Class<?> className, Term... terms) {
    var obj = builder.mkNew(className, ImmutableSeq.from(terms).map(this::doSerialize));
    return builder.invoke(Constants.BETAMAKE, obj, ImmutableSeq.empty());
  }

  @Override public @NotNull FreeJavaExpr serialize(Term unit) {
    binds.clear();
    var vars = ImmutableSeq.fill(instantiates.size(), i -> new LocalVar("arg" + i));
    unit = unit.instTeleVar(vars.view());
    vars.forEachWith(instantiates, binds::put);

    return doSerialize(unit);
  }
}