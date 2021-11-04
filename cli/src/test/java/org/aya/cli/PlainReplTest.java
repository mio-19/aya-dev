// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli;

import kala.tuple.Tuple;
import kala.tuple.Tuple2;
import org.aya.cli.repl.Repl;
import org.aya.cli.repl.ReplConfig;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlainReplTest {
  public static final @NotNull Path configDir = Paths.get("build", "test_config.json");
  public static final @NotNull ReplConfig config = new ReplConfig(configDir);

  @BeforeAll public static void setup() {
    config.enableUnicode = false;
  }

  @Test public void exit() {
    assertNotNull(repl(""));
  }

  @Test public void help() {
    var repl = repl(":help")._1;
    assertTrue(repl.contains("help"));
    assertTrue(repl.contains("REPL"));
  }

  @Test public void emptyLine() {
    assertNotNull(repl("\n\n\n"));
  }

  @Test public void redefinition() {
    assertNotNull(repl("def test => Type\ndef test => Type"));
  }

  @Test public void typeType() {
    assertTrue(repl(":type Type")._1.contains("Type"));
  }

  @Test public void amb() {
    assertTrue(repl(":p")._2.contains("Ambiguous"));
  }

  @Test public void notFound() {
    assertTrue(repl(":cthulhu")._2.contains("not found"));
  }

  @Test public void type() {
    assertTrue(repl("Type")._1.contains("Type"));
  }

  @Test public void disableUnicode() {
    assertTrue(repl(":unicode no")._1.contains("disable"));
    assertTrue(repl(":unicode yes")._1.contains("enable"));
    assertTrue(repl(":unicode false")._1.contains("disable"));
  }

  @Test public void pwd() {
    assertTrue(repl(":pwd")._1.contains("aya"));
  }

  @Test public void typeSuc() {
    var repl = repl("data Nat : Type | suc Nat | zero\n:type Nat::suc")._1;
    assertTrue(repl.contains("Nat"));
    assertTrue(repl.contains("->"));
  }

  private @NotNull Tuple2<String, String> repl(@NotNull String input) {
    var out = new StringWriter();
    var err = new StringWriter();
    var reader = new StringReader(input + "\n:exit");
    var repl = new Repl.PlainRepl(config, reader, out, err);
    repl.run();
    return Tuple.of(out.toString(), err.toString());
  }
}