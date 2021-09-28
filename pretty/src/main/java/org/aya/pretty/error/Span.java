// Copyright (c) 2020-2021 Yinsen (Tesla) Zhang.
// Use of this source code is governed by the GNU GPLv3 license that can be found in the LICENSE file.
package org.aya.pretty.error;

import org.jetbrains.annotations.NotNull;

public interface Span {
  @NotNull String input();

  @NotNull Span.Data normalize(PrettyErrorConfig config);

  record Data(
    int startLine,
    int startCol,
    int endLine,
    int endCol
  ) {
    public boolean contains(int line, int column) {
      return line >= startLine && line <= endLine && column >= startCol && column <= endCol;
    }

    public @NotNull Data union(@NotNull Data other) {
      return new Data(
        Math.min(startLine, other.startLine),
        Math.max(startCol, other.startCol),
        Math.max(endLine, other.endLine),
        Math.max(endCol, other.endCol)
      );
    }
  }
}
