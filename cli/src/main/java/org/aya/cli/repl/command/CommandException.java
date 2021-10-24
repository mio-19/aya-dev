// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.repl.command;

import org.jetbrains.annotations.NotNull;

public class CommandException extends RuntimeException {
  public CommandException(@NotNull String message) {
    super(message);
  }
}
