/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.actions;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import io.flutter.FlutterMessages;
import io.flutter.FlutterUtils;
import io.flutter.pub.PubRoot;
import io.flutter.run.daemon.ProgressHelper;
import io.flutter.sdk.FlutterSdk;
import io.flutter.utils.FlutterModuleUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class OpenInXcodeAction extends AnAction {
  @Override
  public void update(AnActionEvent event) {
    // Enable in global menu; action group controls context menu visibility.
    if (!SystemInfo.isMac) {
      event.getPresentation().setVisible(false);
    }
    else {
      final Presentation presentation = event.getPresentation();
      final boolean enabled = findProjectFile(event) != null;
      presentation.setEnabled(enabled);
      presentation.setVisible(enabled);
    }
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    final VirtualFile projectFile = findProjectFile(e);
    if (projectFile != null) {
      openFile(projectFile);
    }
    else {
      FlutterMessages.showError("Error Opening Xcode", "Project not found.");
    }
  }

  private VirtualFile findProjectFile(@Nullable AnActionEvent e) {
    if (e != null) {
      final VirtualFile file = CommonDataKeys.VIRTUAL_FILE.getData(e.getDataContext());
      if (file != null && file.exists()) {
        if (FlutterUtils.isXcodeFileName(file.getName())) {
          return file;
        }

        // Return null if this is an android folder.
        if (FlutterExternalIdeActionGroup.isAndroidDirectory(file)) {
          return null;
        }
      }

      final Project project = e.getProject();
      if (project != null) {
        return FlutterModuleUtils.findXcodeProjectFile(project);
      }
    }
    return null;
  }

  private static void openFile(@NotNull VirtualFile file) {
    final Project project = ProjectUtil.guessProjectForFile(file);
    final FlutterSdk sdk = project != null ? FlutterSdk.getFlutterSdk(project) : null;
    if (sdk == null) {
      FlutterSdkAction.showMissingSdkDialog(project);
      return;
    }

    final PubRoot pubRoot = PubRoot.forFile(file);
    if (pubRoot == null) {
      FlutterMessages.showError("Error Opening Xcode", "Unable to run `flutter build` (no pub root found)");
      return;
    }

    // Trigger an iOS build if necessary.
    if (!hasBeenBuilt(pubRoot)) {
      final ProgressHelper progressHelper = new ProgressHelper(project);
      progressHelper.start("Building for iOS");

      sdk.flutterBuild(pubRoot, "ios", "--debug").start(null, new ProcessAdapter() {
        @Override
        public void processTerminated(@NotNull ProcessEvent event) {
          super.processTerminated(event);
          progressHelper.done();
          openWithXcode(file.getPath());
        }
      });
    } else {
      openWithXcode(file.getPath());
    }
  }

  private static boolean hasBeenBuilt(@NotNull PubRoot pubRoot) {
    final VirtualFile buildDir = pubRoot.getRoot().findChild("build");
    return buildDir != null && buildDir.isDirectory() && buildDir.findChild("ios") != null;
  }

  private static void openWithXcode(String path) {
    try {
      final GeneralCommandLine cmd = new GeneralCommandLine().withExePath("open").withParameters(path);
      final OSProcessHandler handler = new OSProcessHandler(cmd);
      handler.addProcessListener(new ProcessAdapter() {
        @Override
        public void processTerminated(final ProcessEvent event) {
          if (event.getExitCode() != 0) {
            FlutterMessages.showError("Error Opening", path);
          }
        }
      });
      handler.startNotify();
    }
    catch (ExecutionException ex) {
      FlutterMessages.showError(
        "Error Opening",
        "Exception: " + ex.getMessage());
    }
  }
}
