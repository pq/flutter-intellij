/*
 * Copyright 2021 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.utils;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.TripleFunction;
import io.flutter.FlutterUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineMetrics;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Properties;

@SuppressWarnings("UseJBColor")
public class IconPreviewGenerator {
  private static final Logger LOG = Logger.getInstance(IconPreviewGenerator.class);

  @NotNull final String fontFilePath;
  int iconSize = 16;
  int fontSize = 16;
  @NotNull Color fontColor = Color.gray;

  public IconPreviewGenerator(@NotNull String fontFilePath) {
    this.fontFilePath = fontFilePath;
  }

  public IconPreviewGenerator(@NotNull String fontFilePath, int iconSize, int fontSize, @Nullable Color fontColor) {
    this(fontFilePath);
    this.iconSize = iconSize;
    this.fontSize = fontSize;
    if (fontColor != null) {
      this.fontColor = fontColor;
    }
  }

  public Icon convert(String hexString) {
    if (hexString.startsWith("0x") || hexString.startsWith("0X")) {
      hexString = hexString.substring(2);
    }
    return convert(Integer.parseInt(hexString, 16));
  }

  public Icon convert(int code) {
    return runInGraphicsContext((BufferedImage image, Graphics2D graphics, FontRenderContext frc) -> {
      char ch = Character.toChars(code)[0];
      String codepoint = Character.toString(ch);

      drawGlyph(codepoint, graphics, frc);
      return new ImageIcon(image);
    });
  }

  // Given a file at path-to-font-properties in the format generated by tools_metadata (on github),
  // to generate double-size icons for all glyphs in a font:
  // IconPreviewGenerator ipg = new IconPreviewGenerator("path-to-ttf-file", 32, 32, Color.black);
  // ipg.batchConvert("path-to-out-dir", "path-to-font-properties", "@2x");
  public void batchConvert(@NotNull String outPath, @NotNull String path, @NotNull String suffix) {
    final String outputPath = outPath.endsWith("/") ? outPath : outPath + "/";
    //noinspection ResultOfMethodCallIgnored
    new File(outputPath).mkdirs();

    runInGraphicsContext((BufferedImage image, Graphics2D graphics, FontRenderContext frc) -> {
      Properties fontMap = new Properties();
      File file = new File(path);
      try (InputStream stream = new BufferedInputStream(new FileInputStream(file))) {
        fontMap.load(stream);
        for (Object nextKey : fontMap.keySet()) {
          if (!(nextKey instanceof String)) continue;
          String codepoint = (String)nextKey;
          if (!codepoint.endsWith(".codepoint")) continue;
          String iconName = fontMap.getProperty(codepoint);
          codepoint = codepoint.substring(0, codepoint.indexOf(".codepoint"));
          int code = Integer.parseInt(codepoint, 16);
          char ch = Character.toChars(code)[0];
          codepoint = Character.toString(ch);

          drawGlyph(codepoint, graphics, frc);
          ImageIO.write(image, "PNG", new File(outputPath + iconName + suffix + ".png"));
        }
      }
      catch (IOException ex) {
        FlutterUtils.warn(LOG, ex);
      }
      return null;
    });
  }

  private Icon runInGraphicsContext(TripleFunction<BufferedImage, Graphics2D, FontRenderContext, Icon> callback) {
    Icon result = null;
    Graphics2D graphics = null;
    //noinspection UndesirableClassUsage
    BufferedImage image = new BufferedImage(iconSize, iconSize, BufferedImage.TYPE_4BYTE_ABGR);
    try (InputStream inputStream = new FileInputStream(new File(fontFilePath))) {
      Font font = Font.createFont(Font.TRUETYPE_FONT, inputStream).deriveFont(Font.PLAIN, fontSize);
      graphics = image.createGraphics();
      graphics.setFont(font);
      graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
      result = callback.fun(image, graphics, frc);
    }
    catch (IOException | FontFormatException ex) {
      FlutterUtils.warn(LOG, ex);
    }
    finally {
      if (graphics != null) graphics.dispose();
    }
    return result;
  }

  private void drawGlyph(String codepoint, Graphics2D graphics, FontRenderContext frc) {
    Font font = graphics.getFont();
    Rectangle2D rect = font.getStringBounds(codepoint, frc);
    LineMetrics metrics = font.getLineMetrics(codepoint, frc);
    float lineHeight = metrics.getHeight();
    float ascent = metrics.getAscent();
    float width = (float)rect.getWidth();
    float x0 = (iconSize - width) / 2.0f;
    float y0 = (iconSize - lineHeight) / 2.0f + ascent + (lineHeight - ascent) / 2.0f;

    graphics.setComposite(AlphaComposite.Clear);
    graphics.fillRect(0, 0, iconSize, iconSize);

    graphics.setComposite(AlphaComposite.Src);
    graphics.setColor(fontColor);
    graphics.drawString(codepoint, x0, y0);
  }
}
