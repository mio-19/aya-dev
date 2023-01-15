// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli;

import kala.collection.immutable.ImmutableSeq;
import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import org.aya.cli.parse.ModifierParser;
import org.aya.cli.parse.error.DuplicatedModifierWarn;
import org.aya.concrete.stmt.DeclInfo;
import org.aya.concrete.stmt.Stmt;
import org.aya.util.error.SourcePos;
import org.aya.util.error.WithPos;
import org.aya.util.reporter.BufferReporter;
import org.aya.util.reporter.CollectingReporter;
import org.aya.util.reporter.Problem;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.aya.cli.parse.ModifierParser.Modifier.Example;
import static org.aya.cli.parse.ModifierParser.Modifier.Private;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

public class ModifierParserTest {
  private <R> @NotNull Tuple2<CollectingReporter, R> withParser(@NotNull Function<ModifierParser, R> action) {
    var reporter = new BufferReporter();
    var parser = new ModifierParser(reporter);
    var result = action.apply(parser);

    return Tuple.of(reporter, result);
  }

  public @NotNull ImmutableSeq<WithPos<ModifierParser.Modifier>> posed(@NotNull ImmutableSeq<ModifierParser.Modifier> modifiers) {
    return modifiers.map(x -> new WithPos<>(SourcePos.NONE, x));
  }

  @Test
  public void implication() {
    var modis = ImmutableSeq.of(
      Private,
      Example
    );

    var posModis = modis.mapIndexed((idx, x) -> new WithPos<>(SourcePos.NONE, x));

    var result = withParser(parser -> parser.parse(posModis));
    var reporter = result.component1();
    var returns = result.component2();

    assertEquals(1, reporter.problems().size());
    assertEquals(1, reporter.problemSize(Problem.Severity.WARN));

    var warn = reporter.problems().first();
    assertInstanceOf(DuplicatedModifierWarn.class, warn);
    assertEquals(Private, ((DuplicatedModifierWarn) warn).modifier());

    var modis2 = ImmutableSeq.of(new WithPos<>(SourcePos.NONE, Example));
    var returns2 = withParser(parser -> parser.parse(modis2)).component2();

    assertEquals(new ModifierParser.ModifierSet(
      new WithPos<>(SourcePos.NONE, Stmt.Accessibility.Private),
      new WithPos<>(SourcePos.NONE, DeclInfo.Personality.EXAMPLE),
      null
    ), returns);
    assertEquals(returns, returns2);
  }
}
