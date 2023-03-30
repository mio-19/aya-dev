// Copyright (c) 2020-2023 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.concrete.remark.math;

import org.commonmark.internal.util.Parsing;
import org.commonmark.node.Block;
import org.commonmark.node.CustomBlock;
import org.commonmark.parser.SourceLine;
import org.commonmark.parser.block.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @see org.commonmark.node.FencedCodeBlock
 */
public class MathBlock extends CustomBlock {
  public char fenceChar;
  public int fenceLength;
  public int fenceIndent;

  public String literal;

  /**
   * @see org.commonmark.internal.FencedCodeBlockParser
   */
  public static class Parser extends AbstractBlockParser {
    private final @NotNull MathBlock block = new MathBlock();

    private @Nullable String firstLine;
    private final @NotNull StringBuilder otherLines = new StringBuilder();

    public Parser(char fenceChar, int fenceLength, int fenceIndent) {
      block.fenceChar = fenceChar;
      block.fenceLength = fenceLength;
      block.fenceIndent = fenceIndent;
    }

    @Override public Block getBlock() {
      return block;
    }

    @Override public BlockContinue tryContinue(ParserState state) {
      int nextNonSpace = state.getNextNonSpaceIndex();
      int newIndex = state.getIndex();
      var line = state.getLine().getContent();
      if (state.getIndent() < Parsing.CODE_BLOCK_INDENT && nextNonSpace < line.length() && line.charAt(nextNonSpace) == block.fenceChar && isClosing(line, nextNonSpace)) {
        // closing fence - we're at the end of line, so we can finalize now
        return BlockContinue.finished();
      } else {
        // skip optional spaces of fence indent
        int i = block.fenceIndent;
        int length = line.length();
        while (i > 0 && newIndex < length && line.charAt(newIndex) == ' ') {
          newIndex++;
          i--;
        }
      }
      return BlockContinue.atIndex(newIndex);
    }

    @Override public void addLine(SourceLine line) {
      if (firstLine == null) {
        firstLine = line.getContent().toString();
      } else {
        otherLines.append(line.getContent());
        otherLines.append('\n');
      }
    }

    @Override public void closeBlock() {
      block.literal = otherLines.toString();
    }

    // spec: The content of the code block consists of all subsequent lines, until a closing code fence of the same type
    // as the code block began with (backticks or tildes), and with at least as many backticks or tildes as the opening
    // code fence.
    private boolean isClosing(CharSequence line, int index) {
      char fenceChar = block.fenceChar;
      int fenceLength = block.fenceLength;
      int fences = Parsing.skip(fenceChar, line, index, line.length()) - index;
      if (fences < fenceLength) {
        return false;
      }
      // spec: The closing code fence [...] may be followed only by spaces, which are ignored.
      int after = Parsing.skipSpaceTab(line, index + fences, line.length());
      return after == line.length();
    }
  }

  public static class Factory extends AbstractBlockParserFactory {
    public static final @NotNull Factory INSTANCE = new Factory();

    @Override public @Nullable BlockStart tryStart(ParserState state, MatchedBlockParser matchedBlockParser) {
      int indent = state.getIndent();
      if (indent >= Parsing.CODE_BLOCK_INDENT) {
        return BlockStart.none();
      }

      int nextNonSpace = state.getNextNonSpaceIndex();
      var blockParser = checkOpener(state.getLine().getContent(), nextNonSpace, indent);
      if (blockParser != null) {
        return BlockStart.of(blockParser).atIndex(nextNonSpace + blockParser.block.fenceLength);
      } else {
        return BlockStart.none();
      }
    }
  }

  private static @Nullable MathBlock.Parser checkOpener(CharSequence line, int index, int indent) {
    int backticks = 0;
    for (int i = index; i < line.length(); i++) {
      if (line.charAt(i) == '$') backticks++;
      else break;
    }
    return backticks == 2 ? new Parser('$', backticks, indent) : null;
  }
}
