/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.configurations.*;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.UrlFilter;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.options.SettingsEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileSystemItem;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.listeners.UndoRefactoringElementAdapter;
import com.intellij.util.PathUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters;
import com.intellij.util.xmlb.XmlSerializer;
import com.jetbrains.lang.dart.ide.runner.DartConsoleFilter;
import io.flutter.FlutterUtils;
import io.flutter.console.FlutterConsoleFilter;
import io.flutter.dart.FlutterDartAnalysisServer;
import io.flutter.run.common.RunMode;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.sdk.FlutterSdkManager;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

import static java.nio.file.FileVisitResult.CONTINUE;

/**
 * Run configuration used for launching a Flutter app using the Flutter SDK.
 */
public class SdkRunConfig extends LocatableConfigurationBase<LaunchState>
  implements LaunchState.RunConfig, RefactoringListenerProvider, RunConfigurationWithSuppressedDefaultRunAction {

  private static final @NotNull Logger LOG = Logger.getInstance(SdkRunConfig.class);
  private boolean firstRun = true;

  private @NotNull SdkFields fields = new SdkFields();

  public SdkRunConfig(final @NotNull Project project, final @NotNull ConfigurationFactory factory, final @NotNull String name) {
    super(project, factory, name);
  }

  @NotNull
  public SdkFields getFields() {
    return fields;
  }

  public void setFields(@NotNull SdkFields newFields) {
    fields = newFields;
  }

  @Override
  public void checkConfiguration() throws RuntimeConfigurationException {
    fields.checkRunnable(getProject());
  }

  @NotNull
  @Override
  public SettingsEditor<? extends RunConfiguration> getConfigurationEditor() {
    return new FlutterConfigurationEditorForm(getProject());
  }

  public static class RecursiveDeleter extends SimpleFileVisitor<Path> {
    private final PathMatcher matcher;

    RecursiveDeleter(String pattern) {
      matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    @Override
    public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) {
      final Path name = file.getFileName();
      if (name != null && matcher.matches(name)) {
        try {
          Files.delete(file);
        }
        catch (IOException e) {
          FlutterUtils.warn(LOG, e);
          // TODO(jacobr): consider aborting.
        }
      }
      return CONTINUE;
    }

    @Override
    public @NotNull FileVisitResult preVisitDirectory(@NotNull Path dir, @NotNull BasicFileAttributes attrs) {
      return CONTINUE;
    }

    @Override
    public @NotNull FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exc) {
      FlutterUtils.warn(LOG, exc);
      return CONTINUE;
    }
  }

  @NotNull
  @Override
  public LaunchState getState(@NotNull Executor executor, @NotNull ExecutionEnvironment env) throws ExecutionException {
    final SdkFields launchFields = fields.copy();
    try {
      launchFields.checkRunnable(env.getProject());
    }
    catch (RuntimeConfigurationError e) {
      throw new ExecutionException(e);
    }

    final MainFile mainFile = MainFile.verify(launchFields.getFilePath(), env.getProject()).get();
    final Project project = env.getProject();
    final RunMode mode = RunMode.fromEnv(env);

    final LaunchState.CreateAppCallback createAppCallback = (@Nullable FlutterDevice device) -> {
      if (device == null) return null;
      if (mainFile == null) return null;

      final GeneralCommandLine command = getCommand(env, device);

      // Workaround for https://github.com/flutter/flutter/issues/16766
      // TODO(jacobr): remove once flutter tool incremental building works
      // properly with --track-widget-creation.
      final Path buildPath = command.getWorkDirectory().toPath().resolve("build");
      final Path cachedParametersPath = buildPath.resolve("last_build_run.json");
      final String[] parametersToTrack = {"--preview-dart-2", "--track-widget-creation"};
      final JsonArray jsonArray = new JsonArray();
      for (String parameter : command.getParametersList().getList()) {
        for (String allowedParameter : parametersToTrack) {
          if (parameter.startsWith(allowedParameter)) {
            jsonArray.add(new JsonPrimitive(parameter));
            break;
          }
        }
      }
      final String json = new Gson().toJson(jsonArray);
      String existingJson = null;
      if (Files.exists(cachedParametersPath)) {
        try {
          existingJson = Files.readString(cachedParametersPath);
        }
        catch (IOException e) {
          FlutterUtils.warn(LOG, "Unable to get existing json from " + cachedParametersPath);
        }
      }
      if (!StringUtil.equals(json, existingJson)) {
        // We don't have proof the current run is consistent with the existing run.
        // Be safe and delete cached files that could cause problems due to
        // https://github.com/flutter/flutter/issues/16766
        // We could just delete app.dill and snapshot_blob.bin.d.fingerprint
        // but it is safer to just delete everything as we won't be broken by future changes
        // to the underlying Flutter build rules.
        try {
          if (Files.exists(buildPath)) {
            if (Files.isDirectory(buildPath)) {
              Files.walkFileTree(buildPath, new RecursiveDeleter("*.{fingerprint,dill}"));
            }
          }
          else {
            Files.createDirectory(buildPath);
          }
          Files.writeString(cachedParametersPath, json);
        }
        catch (IOException e) {
          FlutterUtils.warn(LOG, e);
        }
      }

      var module = ModuleUtilCore.findModuleForFile(mainFile.getFile(), env.getProject());
      if (module == null) return null;

      return getFlutterApp(env, device, project, module, mode, command);
    };

    final LaunchState launcher = new LaunchState(env, mainFile.getAppDir(), mainFile.getFile(), this, createAppCallback);
    addConsoleFilters(launcher, env, mainFile, null /* look up the module in an async read context */);
    return launcher;
  }

  static @NotNull FlutterApp getFlutterApp(@NotNull ExecutionEnvironment env,
                                           @NotNull FlutterDevice device,
                                           Project project,
                                           Module module,
                                           RunMode mode,
                                           GeneralCommandLine command) throws ExecutionException {
    final FlutterApp app = FlutterApp.start(env, project, module, mode, device, command,
                                            StringUtil.capitalize(mode.mode()) + "App",
                                            "StopApp");

    // Stop the app if the Flutter SDK changes.
    final FlutterSdkManager.Listener sdkListener = new FlutterSdkManager.Listener() {
      @Override
      public void flutterSdkRemoved() {
        app.shutdownAsync();
      }
    };
    FlutterSdkManager.getInstance(project).addListener(sdkListener);
    Disposer.register(app, () -> FlutterSdkManager.getInstance(project).removeListener(sdkListener));

    return app;
  }

  protected void addConsoleFilters(@NotNull LaunchState launcher,
                                   @NotNull ExecutionEnvironment env,
                                   @NotNull MainFile mainFile,
                                   // If unspecified, we'll try and find it in a non-blocking read context.
                                   @Nullable Module module) {
    // Creating console filters is expensive so we want to make sure we are not blocking.
    // See: https://github.com/flutter/flutter-intellij/issues/8089
    ReadAction.nonBlocking(() -> {
        // Make a copy of the module reference, since we may update it in this lambda.
        var moduleReference = module;
        // If no module was passed in, try and find one.
        if (moduleReference == null) {
          moduleReference = ModuleUtilCore.findModuleForFile(mainFile.getFile(), env.getProject());
        }

        // Set up additional console filters.
        final TextConsoleBuilder builder = launcher.getConsoleBuilder();
        if (builder == null) return null;
        // file:, package:, and dart: references
        builder.addFilter(new DartConsoleFilter(env.getProject(), mainFile.getFile()));
        //// links often found when running tests
        //builder.addFilter(new DartRelativePathsConsoleFilter(env.getProject(), mainFile.getAppDir().getPath()));
        if (moduleReference != null) {
          // various flutter run links
          builder.addFilter(new FlutterConsoleFilter(moduleReference));
        }
        // general urls
        builder.addFilter(new UrlFilter());
        return null;
      })
      .expireWith(FlutterDartAnalysisServer.getInstance(getProject()))
      .submit(AppExecutorUtil.getAppExecutorService());
  }

  @NotNull
  @Override
  public GeneralCommandLine getCommand(@NotNull ExecutionEnvironment env, @NotNull FlutterDevice device) throws ExecutionException {
    final SdkFields launchFields = fields.copy();
    final Project project = env.getProject();
    final RunMode mode = RunMode.fromEnv(env);
    final boolean initialFirstRun = firstRun;
    firstRun = false;
    return fields.createFlutterSdkRunCommand(project, mode, FlutterLaunchMode.fromEnv(env), device, initialFirstRun);
  }

  @Override
  @Nullable
  public String suggestedName() {
    final String filePath = fields.getFilePath();
    return filePath == null ? null : PathUtil.getFileName(filePath);
  }

  @Override
  public SdkRunConfig clone() {
    final SdkRunConfig clone = (SdkRunConfig)super.clone();
    clone.fields = fields.copy();
    return clone;
  }

  @Override
  public void writeExternal(@NotNull final Element element) throws WriteExternalException {
    super.writeExternal(element);
    XmlSerializer.serializeInto(getFields(), element, new SkipDefaultValuesSerializationFilters());
  }

  @Override
  public void readExternal(@NotNull final Element element) throws InvalidDataException {
    super.readExternal(element);
    XmlSerializer.deserializeInto(getFields(), element);
  }

  @Nullable
  @Override
  public RefactoringElementListener getRefactoringElementListener(final PsiElement element) {
    final String filePath = getFields().getFilePath();
    if (filePath == null) return null;

    final String affectedPath = getAffectedPath(element);
    if (affectedPath == null) return null;

    if (element instanceof PsiFile && filePath.equals(affectedPath)) {
      return new RenameRefactoringListener(affectedPath);
    }

    if (element instanceof PsiDirectory && filePath.startsWith(affectedPath + "/")) {
      return new RenameRefactoringListener(affectedPath);
    }

    return null;
  }

  private String getAffectedPath(PsiElement element) {
    if (!(element instanceof PsiFileSystemItem)) return null;
    final VirtualFile file = ((PsiFileSystemItem)element).getVirtualFile();
    return file == null ? null : file.getPath();
  }

  private class RenameRefactoringListener extends UndoRefactoringElementAdapter {
    private @NotNull String myAffectedPath;

    private RenameRefactoringListener(final @NotNull String affectedPath) {
      myAffectedPath = affectedPath;
    }

    private String getNewPathAndUpdateAffectedPath(final @NotNull PsiElement newElement) {
      final String oldPath = fields.getFilePath();

      final VirtualFile newFile = newElement instanceof PsiFileSystemItem ? ((PsiFileSystemItem)newElement).getVirtualFile() : null;
      if (newFile != null && oldPath != null && oldPath.startsWith(myAffectedPath)) {
        final String newPath = newFile.getPath() + oldPath.substring(myAffectedPath.length());
        myAffectedPath = newFile.getPath(); // needed if refactoring will be undone
        return newPath;
      }

      return oldPath;
    }

    @Override
    protected void refactored(@NotNull final PsiElement element, @Nullable final String oldQualifiedName) {
      final boolean generatedName = getName().equals(suggestedName());
      final String filePath = fields.getFilePath();

      final String newPath = getNewPathAndUpdateAffectedPath(element);
      fields.setFilePath(newPath);

      if (generatedName) {
        setGeneratedName();
      }
    }
  }
}
