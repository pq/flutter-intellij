/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run;

import com.google.common.collect.ImmutableMap;
import com.intellij.concurrency.JobScheduler;
import com.intellij.ide.actions.SaveAllAction;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.AnActionResult;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.ex.AnActionListener;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.lang.dart.ide.errorTreeView.DartProblemsView;
import com.jetbrains.lang.dart.psi.DartFile;
import icons.FlutterIcons;
import io.flutter.FlutterConstants;
import io.flutter.FlutterUtils;
import io.flutter.actions.FlutterAppAction;
import io.flutter.actions.ProjectActions;
import io.flutter.actions.ReloadFlutterApp;
import io.flutter.analytics.Analytics;
import io.flutter.analytics.AnalyticsConstants;
import io.flutter.logging.PluginLogger;
import io.flutter.run.common.RunMode;
import io.flutter.run.daemon.DaemonApi;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.settings.FlutterSettings;
import io.flutter.utils.FlutterModuleUtils;
import io.flutter.utils.OpenApiUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.jetbrains.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handle the mechanics of performing a hot reload on file save.
 */
public class FlutterReloadManager implements Disposable {
  private static final @NotNull Logger LOG = PluginLogger.createLogger(FlutterReloadManager.class);

  private static final Map<String, NotificationGroup> toolWindowNotificationGroups = new HashMap<>();

  private static final Map<String, String> toolWindowIdsToNotificationGroupIds = ImmutableMap.of(
    ToolWindowId.DEBUG, "flutter-debug",
    ToolWindowId.RUN, "flutter-run",
    DartProblemsView.TOOLWINDOW_ID, "flutter-analysis"
  );

  private static @Nullable NotificationGroup getNotificationGroup(@NotNull String toolWindowId) {
    if (!toolWindowNotificationGroups.containsKey(toolWindowId)) {
      final String notificationGroupId = toolWindowIdsToNotificationGroupIds.get(toolWindowId);
      if (notificationGroupId == null) {
        LOG.error("Notification group ID could not be found for toolWindowId: " + toolWindowId);
        return null;
      }

      NotificationGroupManager manager = NotificationGroupManager.getInstance();
      if (manager == null) {
        return null;
      }
      NotificationGroup group = manager.getNotificationGroup(notificationGroupId);
      toolWindowNotificationGroups.put(toolWindowId, group);
    }
    return toolWindowNotificationGroups.get(toolWindowId);
  }

  private final @NotNull Project myProject;

  private volatile Notification lastNotification;

  /**
   * Receives one reload-on-save analytics event per attempt that passes the cheap guards in
   * {@link #reloadOnSave}. Replaceable in tests, because the Dart plugin's report observer is thread-local and the
   * reload completes on another thread.
   */
  @VisibleForTesting
  interface ReloadOnSaveReporter {
    void report(@NotNull String trigger, @NotNull String outcome, @Nullable Long durationMs, @Nullable Integer errorCode);
  }

  private volatile @NotNull ReloadOnSaveReporter reloadOnSaveReporter = this::reportReloadOnSave;

  /** A Dart editor to reload from, and whether any open editor in the project has a syntax error. */
  private record SaveTarget(@Nullable Editor editor, boolean syntaxErrorInAnyEditor) {}

  /**
   * Save generations, used to tell which document saves already belong to someone else.
   * <p>
   * Every save-all fires {@code beforeAllDocumentsSaving}, which takes the next generation and queues the
   * document-save path with invokeLater. An explicit Save All also runs the save-action path, and
   * {@link #saveAllAndReload} and friends reload by themselves. Both record the range of generations their save
   * produced, and the queued runnable checks its own generation against those ranges. Unlike a time window, this
   * doesn't depend on how long the save or the EDT queue takes.
   */
  private final AtomicLong saveGeneration = new AtomicLong();

  /** Generations produced by the last explicit Save All: (from, to]. And whether its save-action path found an app. */
  private volatile long explicitSaveFrom;
  private volatile long explicitSaveTo;
  private volatile boolean explicitSaveHandled;

  /** Generations produced by the last {@link #saveAllAndReload} (or friend) save: (from, to]. */
  private volatile long ownSaveFrom;
  private volatile long ownSaveTo;

  /** Generation when the current explicit Save All began; only touched on the EDT by the action listener. */
  private long saveActionStartGeneration;

  /** Minimum gap between two reported {@code skipped_syntax_error_any_editor} events (idle autosave can repeat it). */
  @VisibleForTesting
  static final long SYNTAX_SKIP_REPORT_INTERVAL_MS = 60_000;

  private final AtomicLong lastSyntaxSkipReportedAt = new AtomicLong();

  /**
   * Initialize the reload manager for the given project.
   */
  public static void init(@NotNull Project project) {
    // Call getInstance() will init FlutterReloadManager for the given project.
    getInstance(project);
  }

  @Nullable
  public static FlutterReloadManager getInstance(@NotNull Project project) {
    return project.getService(FlutterReloadManager.class);
  }

  private FlutterReloadManager(@NotNull Project project) {
    this.myProject = project;

    final MessageBusConnection connection =
      ApplicationManager.getApplication().getMessageBus().connect(this);
    connection.subscribe(AnActionListener.TOPIC, new AnActionListener() {
      private @Nullable Project eventProject;
      private @Nullable Editor eventEditor;

      public void beforeActionPerformed(@NotNull AnAction action, @NotNull AnActionEvent event) {
        // In case of hot-reload breakages, see: https://github.com/flutter/flutter-intellij/issues/6996
        if (!(action instanceof SaveAllAction)) {
          return;
        }

        saveActionStartGeneration = saveGeneration.get();

        // Save the current project value here. If we access later (in afterActionPerformed), we can get
        // exceptions if another event occurs before afterActionPerformed is called.
        try {
          eventProject = event.getProject();
          eventEditor = CommonDataKeys.EDITOR.getData(event.getDataContext());
        }
        catch (Throwable t) {
          // A catch-all, so any exceptions don't bubble through to the users.
          FlutterUtils.warn(LOG, "Exception from FlutterReloadManager", t, true);
        }
      }

      public void afterActionPerformed(@NotNull AnAction action, @NotNull AnActionEvent event, @NotNull AnActionResult result) {
        // In case of hot-reload breakages, see: https://github.com/flutter/flutter-intellij/issues/6996
        if (!(action instanceof SaveAllAction)) {
          return;
        }

        // If this save all action is not for the current project, return.
        if (myProject != eventProject) {
          return;
        }

        boolean handled = false;
        try {
          handled = handleSaveAllNotification(eventEditor, AnalyticsConstants.TRIGGER_SAVE_ACTION, false);
        }
        catch (Throwable t) {
          FlutterUtils.warn(LOG, "Exception from hot reload on save", t, true);
        }
        finally {
          // Lets the document-save runnable that this Save All queued skip, or report itself as save_action.
          endSaveAllAction(saveActionStartGeneration, handled);
          // Context: "Released EditorImpl held by lambda in FlutterReloadManager" (https://github.com/flutter/flutter-intellij/issues/7507)
          eventProject = null;
          eventEditor = null;
        }
      }
    });
    connection.subscribe(FileDocumentManagerListener.TOPIC, new FileDocumentManagerListener() {
      @Override
      public void beforeAllDocumentsSaving() {
        // Take a generation before any early return, so the claim ranges stay accurate.
        final long generation = saveGeneration.incrementAndGet();
        if (!FlutterSettings.getInstance().isReloadOnSave()) return;
        if (myProject.isDisposed()) return;
        if (!FlutterModuleUtils.hasFlutterModule(myProject)) return;
        // The "Save files if the IDE is idle ..." option runs whether there are any changes or not.
        boolean isModified = false;
        final FileEditorManager fileEditorManager = FileEditorManager.getInstance(myProject);
        if (fileEditorManager != null) {
          for (FileEditor fileEditor : fileEditorManager.getAllEditors()) {
            if (fileEditor != null && fileEditor.isModified()) {
              isModified = true;
              break;
            }
          }
          if (!isModified) return;
        }

        OpenApiUtils.safeInvokeLater(() -> {
          final String trigger = claimDocumentSave(generation);
          if (trigger == null) {
            return; // An explicit Save All or our own reload/restart already handled this save.
          }

          // Find a Dart editor to trigger the reload, and note whether any open editor has a syntax error.
          final SaveTarget target = OpenApiUtils.safeRunReadAction(() -> {
            Editor someEditor = null;
            boolean syntaxError = false;
            final EditorFactory editorFactory = EditorFactory.getInstance();
            if (editorFactory != null) {
              for (Editor editor : editorFactory.getAllEditors()) {
                if (editor == null || editor.isDisposed() || editor.getProject() != myProject) {
                  continue;
                }
                final PsiFile psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(editor.getDocument());
                if (psiFile instanceof DartFile && someEditor == null) {
                  someEditor = editor;
                }
                if (null != PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement.class, false)) {
                  // The Dart plugin may create empty files that it then claims have a syntax error. Ignore them.
                  if (editor.getDocument().getTextLength() != 0) {
                    syntaxError = true;
                  }
                }
              }
            }
            return new SaveTarget(someEditor, syntaxError);
          });
          if (target != null) {
            handleSaveAllNotification(target.editor(), trigger, target.syntaxErrorInAnyEditor());
          }
        }, ModalityState.any());
      }
    });
  }

  /**
   * Reloads the app after a save, if reload-on-save applies.
   *
   * @param trigger                one of {@link AnalyticsConstants#TRIGGER_SAVE_ACTION} or
   *                               {@link AnalyticsConstants#TRIGGER_DOCUMENT_SAVE}
   * @param syntaxErrorInAnyEditor whether any open editor in the project has a syntax error; the reload is then
   *                               skipped silently, as before, but reported
   * @return whether there was an app to reload, so this save was handled here
   */
  private boolean handleSaveAllNotification(@Nullable Editor editor, @NotNull String trigger, boolean syntaxErrorInAnyEditor) {
    if (!FlutterSettings.getInstance().isReloadOnSave() || editor == null) {
      return false;
    }

    @NotNull String configPath = PathManager.getConfigDir().toString();
    if (SystemInfo.isWindows) {
      configPath = configPath.replace('\\', '/');
    }
    @Nullable VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
    if (file == null) {
      return false;
    }
    if (file.getPath().startsWith(configPath)) {
      return false; // Ignore changes to scratch files.
    }
    if (System.currentTimeMillis() - file.getTimeStamp() > 500) {
      // If the file was saved in the last half-second, assume it should trigger hot reload
      // because it was probably just saved before this notification was generated.
      // Files saved a long time ago should not trigger hot reload.
      return false;
    }

    final AnAction reloadAction = ProjectActions.getAction(myProject, ReloadFlutterApp.ID);
    final FlutterApp app = getApp(reloadAction);
    if (app == null) {
      return false;
    }

    reloadOnSave(app, editor.getDocument(), trigger, syntaxErrorInAnyEditor);
    return true;
  }

  /** Records that an explicit Save All, which began at {@code startGeneration}, has finished. */
  @VisibleForTesting
  void endSaveAllAction(long startGeneration, boolean handled) {
    explicitSaveHandled = handled;
    explicitSaveFrom = startGeneration;
    explicitSaveTo = saveGeneration.get();
  }

  /**
   * Decides what the document-save runnable for save {@code generation} should do.
   *
   * @return null to skip, because an explicit Save All that found an app, or one of our own reload/restart saves,
   * already handled it; {@link AnalyticsConstants#TRIGGER_SAVE_ACTION} if an explicit Save All produced this save
   * but its save-action path bailed out early (for example, no focused editor); otherwise
   * {@link AnalyticsConstants#TRIGGER_DOCUMENT_SAVE}
   */
  @VisibleForTesting
  @Nullable String claimDocumentSave(long generation) {
    if (generation > ownSaveFrom && generation <= ownSaveTo) {
      return null;
    }
    if (generation > explicitSaveFrom && generation <= explicitSaveTo) {
      return explicitSaveHandled ? null : AnalyticsConstants.TRIGGER_SAVE_ACTION;
    }
    return AnalyticsConstants.TRIGGER_DOCUMENT_SAVE;
  }

  /** Saves all documents, marking the resulting document saves as ours: the caller reloads or restarts itself. */
  private void saveAllDocumentsAsOwnSave() {
    final long from = saveGeneration.get();
    FileDocumentManager.getInstance().saveAllDocuments();
    ownSaveFrom = from;
    ownSaveTo = saveGeneration.get();
  }

  @TestOnly
  long currentSaveGenerationForTest() {
    return saveGeneration.get();
  }

  @TestOnly
  long takeSaveGenerationForTest() {
    return saveGeneration.incrementAndGet();
  }

  /**
   * The part of reload-on-save that runs once there is an app to reload.
   * <p>
   * Reports exactly one analytics event per attempt that gets past the cheap guards below (unsupported app, app not
   * started or shutting down), so autosaves in projects without a running app don't produce events.
   */
  @VisibleForTesting
  void reloadOnSave(@NotNull FlutterApp app, @NotNull Document document, @NotNull String trigger, boolean syntaxErrorInAnyEditor) {
    if (!app.getLaunchMode().supportsReload() || !app.appSupportsHotReload()) {
      return;
    }

    // isStarted() is also true while the app is TERMINATING, so check isConnected() too.
    if (!app.isStarted() || !app.isConnected()) {
      return;
    }

    final ReloadOnSaveReporter reporter = reloadOnSaveReporter;

    if (app.isReloading()) {
      reporter.report(trigger, AnalyticsConstants.OUTCOME_SKIPPED_ALREADY_RELOADING, null, null);
      return;
    }

    if (syntaxErrorInAnyEditor) {
      // As before, skip without a notification; the editor already shows the error.
      reportSyntaxErrorSkip(reporter, trigger);
      return;
    }

    // Transition the app to an about-to-reload state. This only succeeds from STARTED.
    final FlutterApp.State previousAppState = app.transitionStartingHotReload();
    if (previousAppState == null) {
      // Lost a race after the checks above: the app started terminating, or another reload or restart began.
      reporter.report(trigger, app.isConnected()
                               ? AnalyticsConstants.OUTCOME_SKIPPED_ALREADY_RELOADING
                               : AnalyticsConstants.OUTCOME_SKIPPED_NOT_CONNECTED, null, null);
      return;
    }

    JobScheduler.getScheduler().schedule(() -> {
      clearLastNotification();

      if (!app.isConnected()) {
        // The app started terminating after we moved it to RELOADING, so its state is now TERMINATING or
        // TERMINATED. Don't restore previousAppState: that would bring a terminating app back to STARTED.
        reporter.report(trigger, AnalyticsConstants.OUTCOME_SKIPPED_NOT_CONNECTED, null, null);
        return;
      }

      // Don't reload if the current file has a syntax (parse) error.
      if (hasErrorsInFile(document)) {
        app.cancelHotReloadState(previousAppState);
        showAnalysisNotification("Reload not performed", "Analysis issues found", true);
        reporter.report(trigger, AnalyticsConstants.OUTCOME_SKIPPED_SYNTAX_ERROR_CURRENT_FILE, null, null);
        return;
      }

      if (!app.isConnected()) {
        // The app started shutting down while we checked the file (a read action, which can take a while).
        reporter.report(trigger, AnalyticsConstants.OUTCOME_SKIPPED_NOT_CONNECTED, null, null);
        return;
      }

      final Notification notification = showRunNotification(app, null, "Reloading…", false);
      final long startNanos = System.nanoTime();
      final AtomicBoolean finished = new AtomicBoolean();

      // If the app shuts down mid-reload, DaemonApi drops the pending command without completing it
      // (cancelPending), so whenComplete below never runs. Clear "Reloading…" and report from here instead.
      final FlutterApp.FlutterAppListener shutdownListener = new FlutterApp.FlutterAppListener() {
        @Override
        public void stateChanged(FlutterApp.State newState) {
          if ((newState == FlutterApp.State.TERMINATING || newState == FlutterApp.State.TERMINATED) &&
              finished.compareAndSet(false, true)) {
            app.removeStateListener(this);
            if (notification != null) {
              notification.expire();
            }
            reporter.report(trigger, AnalyticsConstants.OUTCOME_ABANDONED, elapsedMs(startNanos), null);
          }
        }
      };
      app.addStateListener(shutdownListener);

      final CompletableFuture<DaemonApi.RestartResult> reload;
      try {
        reload = app.performHotReload(true, FlutterConstants.RELOAD_REASON_SAVE);
      }
      catch (RuntimeException e) {
        if (finished.compareAndSet(false, true)) {
          app.removeStateListener(shutdownListener);
          if (notification != null) {
            notification.expire();
          }
          showRunNotification(app, "Hot Reload Error", describe(e), true);
          reporter.report(trigger, AnalyticsConstants.OUTCOME_EXCEPTION, elapsedMs(startNanos), null);
        }
        return;
      }

      // whenComplete, not thenAccept: on an exceptional completion (for example, an error response from the
      // daemon), thenAccept never ran, so the "Reloading…" notification stayed up and nothing was reported.
      reload.whenComplete((result, throwable) -> {
        if (!finished.compareAndSet(false, true)) {
          return; // Already reported as abandoned.
        }
        app.removeStateListener(shutdownListener);
        final long durationMs = elapsedMs(startNanos);

        if (throwable != null && !app.isConnected()) {
          // The app started shutting down just before or during the reload (the shutdown listener may not have
          // been notified yet). That's not an error the user needs to see.
          if (notification != null) {
            notification.expire();
          }
          reporter.report(trigger, isShuttingDown(throwable)
                                   ? AnalyticsConstants.OUTCOME_SKIPPED_NOT_CONNECTED
                                   : AnalyticsConstants.OUTCOME_ABANDONED, durationMs, null);
        }
        else if (throwable != null || result == null) {
          if (notification != null) {
            notification.expire();
          }
          showRunNotification(app, "Hot Reload Error", describe(throwable), true);
          reporter.report(trigger, AnalyticsConstants.OUTCOME_EXCEPTION, durationMs, null);
        }
        else if (!result.ok()) {
          if (notification != null) {
            notification.expire();
          }
          showRunNotification(app, "Hot Reload Error", result.getMessage(), true);
          reporter.report(trigger, AnalyticsConstants.OUTCOME_ERROR, durationMs, result.getCode());
        }
        else {
          // Make sure the reloading message is displayed for at least 2 seconds (so it doesn't just flash by).
          final long delay = Math.max(0, 2000 - durationMs);

          JobScheduler.getScheduler().schedule(() -> UIUtil.invokeLaterIfNeeded(() -> {
            if (notification != null) {
              notification.expire();
            }

            // If the 'Reloading…' notification is still the most recent one, then clear it.
            if (isLastNotification(notification)) {
              removeRunNotifications(app);
            }
          }), delay, TimeUnit.MILLISECONDS);
          reporter.report(trigger, AnalyticsConstants.OUTCOME_SUCCESS, durationMs, null);
        }
      });
    }, 0, TimeUnit.MILLISECONDS);
  }

  /** Reports {@code skipped_syntax_error_any_editor} at most once per {@link #SYNTAX_SKIP_REPORT_INTERVAL_MS}. */
  private void reportSyntaxErrorSkip(@NotNull ReloadOnSaveReporter reporter, @NotNull String trigger) {
    final long now = System.currentTimeMillis();
    final long last = lastSyntaxSkipReportedAt.get();
    if (now - last >= SYNTAX_SKIP_REPORT_INTERVAL_MS && lastSyntaxSkipReportedAt.compareAndSet(last, now)) {
      reporter.report(trigger, AnalyticsConstants.OUTCOME_SKIPPED_SYNTAX_ERROR_ANY_EDITOR, null, null);
    }
  }

  private static boolean isShuttingDown(@Nullable Throwable throwable) {
    Throwable cause = throwable;
    while (cause instanceof CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause instanceof FlutterApp.AppShuttingDownException;
  }

  private static long elapsedMs(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  private void reportReloadOnSave(@NotNull String trigger,
                                  @NotNull String outcome,
                                  @Nullable Long durationMs,
                                  @Nullable Integer errorCode) {
    Analytics.reportReloadOnSave(myProject, trigger, outcome, durationMs, errorCode);
  }

  /**
   * A short, user-facing description of a failed reload or restart: the first line of the underlying message. The
   * daemon's error text can include a stack trace, so the full throwable is logged instead of shown.
   */
  @VisibleForTesting
  static @NotNull String describe(@Nullable Throwable throwable) {
    Throwable cause = throwable;
    while (cause instanceof CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause != null) {
      LOG.info("Hot reload or restart failed", cause);
    }
    final String message = cause == null ? null : cause.getMessage();
    if (message == null || message.isBlank()) {
      return "Hot reload failed (see the IDE log for details)";
    }
    final int newline = message.indexOf('\n');
    return newline < 0 ? message : message.substring(0, newline);
  }

  /** Replaces the reporter until {@code parentDisposable} is disposed. */
  @TestOnly
  void setReloadOnSaveReporterForTest(@NotNull ReloadOnSaveReporter reporter, @NotNull Disposable parentDisposable) {
    reloadOnSaveReporter = reporter;
    lastSyntaxSkipReportedAt.set(0);
    Disposer.register(parentDisposable, () -> {
      reloadOnSaveReporter = this::reportReloadOnSave;
      lastSyntaxSkipReportedAt.set(0);
    });
  }

  private void reloadApp(@NotNull FlutterApp app, @NotNull String reason) {
    // isStarted() is also true while TERMINATING; skip silently then, as there's nothing to reload.
    if (app.isStarted() && app.isConnected()) {
      app.performHotReload(true, reason).thenAccept(result -> {
        if (!result.ok()) {
          showRunNotification(app, "Hot Reload Error", result.getMessage(), true);
        }
      }).exceptionally(throwable -> {
        showRunNotification(app, "Hot Reload Error", describe(throwable), true);
        return null;
      });
    }
  }

  public void saveAllAndReload(@NotNull FlutterApp app, @NotNull String reason) {
    saveAllDocumentsAsOwnSave();

    clearLastNotification();

    reloadApp(app, reason);
  }

  public void saveAllAndReloadAll(@NotNull List<FlutterApp> appsToReload, @NotNull String reason) {
    saveAllDocumentsAsOwnSave();

    clearLastNotification();

    for (FlutterApp app : appsToReload) {
      reloadApp(app, reason);
    }
  }

  private void restartApp(@NotNull FlutterApp app, @NotNull String reason) {
    // isStarted() is also true while TERMINATING; skip silently then, as there's nothing to restart.
    if (app.isStarted() && app.isConnected()) {
      app.performRestartApp(reason).thenAccept(result -> {
        if (!result.ok()) {
          showRunNotification(app, "Hot Restart Error", result.getMessage(), true);
        }
      }).exceptionally(throwable -> {
        showRunNotification(app, "Hot Restart Error", describe(throwable), true);
        return null;
      });

      final FlutterDevice device = app.device();
      device.bringToFront();
    }
  }

  public void saveAllAndRestart(@NotNull FlutterApp app, @NotNull String reason) {
    saveAllDocumentsAsOwnSave();

    clearLastNotification();

    restartApp(app, reason);
  }

  public void saveAllAndRestartAll(@NotNull List<FlutterApp> appsToRestart, @NotNull String reason) {
    saveAllDocumentsAsOwnSave();

    clearLastNotification();

    for (FlutterApp app : appsToRestart) {
      restartApp(app, reason);
    }
  }

  @Nullable
  private FlutterApp getApp(AnAction reloadAction) {
    if (reloadAction instanceof FlutterAppAction) {
      return ((FlutterAppAction)reloadAction).getApp();
    }
    else {
      return null;
    }
  }

  private void showAnalysisNotification(@NotNull String title, @NotNull String content, boolean isError) {
    final ToolWindow dartProblemsView = ToolWindowManager.getInstance(myProject).getToolWindow(DartProblemsView.TOOLWINDOW_ID);

    final NotificationGroup notificationGroup = getNotificationGroup(DartProblemsView.TOOLWINDOW_ID);
    if (notificationGroup == null) {
      LOG.error("Could not find notification group for toolWindowId: " + DartProblemsView.TOOLWINDOW_ID);
      return;
    }
    final Notification notification = notificationGroup.createNotification(
      title, content, isError ? NotificationType.ERROR : NotificationType.INFORMATION);

    if (dartProblemsView != null && !dartProblemsView.isVisible()) {
      notification.addAction(new AnAction("View Issues") {
        @Override
        public void actionPerformed(@NotNull AnActionEvent event) {
          notification.expire();

          final ToolWindow dartProblemsView = ToolWindowManager.getInstance(myProject).getToolWindow(DartProblemsView.TOOLWINDOW_ID);
          if (dartProblemsView != null) {
            dartProblemsView.activate(null);
          }
        }
      });
    }

    notification.setIcon(FlutterIcons.Flutter);
    notification.notify(myProject);

    lastNotification = notification;
  }

  private @Nullable Notification showRunNotification(@NotNull FlutterApp app,
                                                     @Nullable String title,
                                                     @NotNull String content,
                                                     boolean isError) {
    final String toolWindowId = app.getMode() == RunMode.DEBUG ? ToolWindowId.DEBUG : ToolWindowId.RUN;
    final NotificationGroup notificationGroup = getNotificationGroup(toolWindowId);
    if (notificationGroup == null) {
      LOG.error("Could not find notification group for toolWindowId: " + DartProblemsView.TOOLWINDOW_ID);
      return null;
    }

    final Notification notification =
      notificationGroup.createNotification(content, isError ? NotificationType.ERROR : NotificationType.INFORMATION);
    notification.setIcon(FlutterIcons.Flutter);
    notification.notify(myProject);

    lastNotification = notification;

    return notification;
  }

  private boolean isLastNotification(final Notification notification) {
    return notification == lastNotification;
  }

  private void clearLastNotification() {
    lastNotification = null;
  }

  private void removeRunNotifications(FlutterApp app) {
    final String toolWindowId = app.getMode() == RunMode.DEBUG ? ToolWindowId.DEBUG : ToolWindowId.RUN;
    final Balloon balloon = ToolWindowManager.getInstance(myProject).getToolWindowBalloon(toolWindowId);
    if (balloon != null) {
      balloon.hide();
    }
  }

  private boolean hasErrorsInFile(@NotNull Document document) {
    // We use the IntelliJ parser and look for syntax errors in the current document.
    // We block reload if we find issues in the immediate file. We don't block reload if there
    // are analysis issues in other files; the compilation errors from the flutter tool
    // will indicate to the user where the problems are.

    final PsiErrorElement firstError = OpenApiUtils.safeRunReadAction(() -> {
      final PsiFile psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(document);
      if (psiFile instanceof DartFile) {
        return PsiTreeUtil.findChildOfType(psiFile, PsiErrorElement.class, false);
      }
      else {
        return null;
      }
    });
    return firstError != null;
  }

  @Override
  public void dispose() {
  }
}