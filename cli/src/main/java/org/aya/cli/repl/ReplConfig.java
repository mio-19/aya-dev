// Copyright (c) 2020-2022 Tesla (Yinsen) Zhang.
// Use of this source code is governed by the MIT license that can be found in the LICENSE.md file.
package org.aya.cli.repl;

import com.google.gson.GsonBuilder;
import com.google.gson.InstanceCreator;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonParseException;
import kala.control.Option;
import org.aya.cli.render.Color;
import org.aya.cli.render.RenderOptions;
import org.aya.distill.AyaDistillerOptions;
import org.aya.generic.util.AyaHome;
import org.aya.generic.util.NormalizeMode;
import org.aya.util.distill.DistillerOptions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnknownNullability;
import org.jetbrains.annotations.VisibleForTesting;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ReplConfig implements AutoCloseable {
  public static final @NotNull RenderOptions.OutputTarget DEFAULT_OUTPUT_TARGET = RenderOptions.OutputTarget.Terminal;

  public transient final Option<Path> configFile;
  public @NotNull String prompt = "> ";
  public @NotNull NormalizeMode normalizeMode = NormalizeMode.NF;
  public @NotNull AyaDistillerOptions distillerOptions = AyaDistillerOptions.pretty();
  public boolean enableUnicode = true;
  /** Disables welcome message, echoing info, etc. */
  public boolean silent = false;
  public @UnknownNullability RenderOptions renderOptions = new RenderOptions();

  public ReplConfig(@NotNull Option<Path> file) {
    this.configFile = file;
  }

  private void checkDeserialization() {
    if (distillerOptions.map.isEmpty()) distillerOptions.reset();
    // maintain the Nullability, renderOptions is probably null after deserializing
    if (renderOptions == null) renderOptions = new RenderOptions();
    renderOptions.checkDeserialization();
    try {
      renderOptions.stylist(RenderOptions.OutputTarget.Terminal);
    } catch (IOException | JsonParseException e) {
      System.err.println("Failed to load stylist from config file, using default stylist instead.");
    }
  }

  public static @NotNull ReplConfig loadFromDefault() throws IOException, JsonParseException {
    return ReplConfig.loadFrom(AyaHome.ayaHome().resolve("repl_config.json"));
  }

  public static @NotNull ReplConfig loadFrom(@NotNull Path file) throws IOException, JsonParseException {
    if (Files.notExists(file)) return new ReplConfig(Option.some(file));
    return loadFrom(Option.some(file), Files.readString(file));
  }

  @VisibleForTesting
  public static @NotNull ReplConfig loadFrom(@NotNull Option<Path> file, @NotNull String jsonText) throws JsonParseException {
    var config = newGsonBuilder()
      .registerTypeAdapter(ReplConfig.class, (InstanceCreator<ReplConfig>) type -> new ReplConfig(file))
      .create()
      .fromJson(jsonText, ReplConfig.class);
    if (config == null) return new ReplConfig(file);
    config.checkDeserialization();
    return config;
  }

  @Override public void close() throws IOException {
    if (configFile.isDefined())
      Files.writeString(configFile.get(), newGsonBuilder().create().toJson(this));
  }

  @VisibleForTesting public static GsonBuilder newGsonBuilder() {
    return new GsonBuilder()
      .registerTypeAdapter(Color.class, new Color.Adapter())
      .registerTypeAdapter(DistillerOptions.Key.class, (JsonDeserializer<DistillerOptions.Key>) (json, typeOfT, context) -> {
        try {
          return AyaDistillerOptions.Key.valueOf(json.getAsString());
        } catch (IllegalArgumentException ignored) {
          return null;
        }
      });
  }
}
