/*
 * Copyright 2026 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.run.daemon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import io.flutter.run.FlutterDevice;
import io.flutter.run.common.RunMode;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.mock;

/**
 * Checks that a reload or restart always leaves RELOADING/RESTARTING when it completes, including when the daemon
 * answers with an error, and never brings a terminating app back to STARTED.
 */
public class FlutterAppReloadStateTest extends BasePlatformTestCase {

  private final List<String> sentCommands = new ArrayList<>();
  private DaemonApi api;
  private FlutterApp app;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    api = new DaemonApi(sentCommands::add);
    app = new FlutterApp(getProject(), RunMode.RUN, mock(FlutterDevice.class), mock(ProcessHandler.class),
                         mock(ExecutionEnvironment.class), api, mock(GeneralCommandLine.class));
    Disposer.register(getTestRootDisposable(), app);
    app.setAppId("app-1");
    app.changeState(FlutterApp.State.STARTED);
  }

  public void testReloadErrorResponseRestoresStarted() {
    final CompletableFuture<DaemonApi.RestartResult> future = app.performHotReload(true, "save");
    assertEquals(FlutterApp.State.RELOADING, app.getState());

    respondWithError();

    assertTrue(future.isCompletedExceptionally());
    assertEquals(FlutterApp.State.STARTED, app.getState());
    assertFalse(app.isReloading());
  }

  public void testRestartErrorResponseRestoresStarted() {
    final CompletableFuture<DaemonApi.RestartResult> future = app.performRestartApp("manual");
    assertEquals(FlutterApp.State.RESTARTING, app.getState());

    respondWithError();

    assertTrue(future.isCompletedExceptionally());
    assertEquals(FlutterApp.State.STARTED, app.getState());
    assertFalse(app.isReloading());
  }

  public void testReloadSuccessRestoresStarted() {
    final CompletableFuture<DaemonApi.RestartResult> future = app.performHotReload(true, "save");

    respondWithResult();

    assertTrue(future.isDone());
    assertFalse(future.isCompletedExceptionally());
    assertEquals(FlutterApp.State.STARTED, app.getState());
  }

  public void testCompletionDoesNotReviveTerminatingApp() {
    app.performHotReload(true, "save");
    app.changeState(FlutterApp.State.TERMINATING);

    respondWithResult();

    assertEquals(FlutterApp.State.TERMINATING, app.getState());
  }

  public void testCallersSeeRestoredStateInTheirCallbacks() {
    final List<FlutterApp.State> seen = new ArrayList<>();
    app.performHotReload(true, "save").whenComplete((result, t) -> seen.add(app.getState()));

    respondWithError();

    assertEquals(List.of(FlutterApp.State.STARTED), seen);
  }

  public void testTransitionStartingHotReloadOnlyFromStarted() {
    app.changeState(FlutterApp.State.TERMINATING);

    assertNull(app.transitionStartingHotReload());
    assertEquals(FlutterApp.State.TERMINATING, app.getState());

    app.changeState(FlutterApp.State.STARTED);
    assertEquals(FlutterApp.State.STARTED, app.transitionStartingHotReload());
    assertEquals(FlutterApp.State.RELOADING, app.getState());
    assertNull("a second reload must not start", app.transitionStartingHotReload());
  }

  public void testCancelHotReloadStateDoesNotReviveTerminatingApp() {
    final FlutterApp.State previous = app.transitionStartingHotReload();
    app.changeState(FlutterApp.State.TERMINATING);

    app.cancelHotReloadState(previous);

    assertEquals(FlutterApp.State.TERMINATING, app.getState());
  }

  public void testCancelHotReloadStateUndoesOwnReloading() {
    final FlutterApp.State previous = app.transitionStartingHotReload();

    app.cancelHotReloadState(previous);

    assertEquals(FlutterApp.State.STARTED, app.getState());
  }

  public void testReloadAndRestartRefuseTerminatingApp() {
    app.changeState(FlutterApp.State.TERMINATING);

    assertTrue(app.performHotReload(true, "save").isCompletedExceptionally());
    assertTrue(app.performRestartApp("manual").isCompletedExceptionally());
    assertEquals(FlutterApp.State.TERMINATING, app.getState());
    assertTrue("nothing should be sent to the daemon", sentCommands.isEmpty());
  }

  public void testReloadRefusedWhileStarting() {
    app.changeState(FlutterApp.State.STARTING);

    final CompletableFuture<DaemonApi.RestartResult> future = app.performHotReload(true, "save");

    assertTrue(future.isCompletedExceptionally());
    assertEquals(FlutterApp.State.STARTING, app.getState());
    assertTrue(sentCommands.isEmpty());
  }

  private void respondWithError() {
    final JsonObject response = new JsonObject();
    response.addProperty("id", lastCommandId());
    response.addProperty("error", "boom");
    api.dispatch(response, null);
  }

  private void respondWithResult() {
    final JsonObject result = new JsonObject();
    result.addProperty("code", 0);
    result.addProperty("message", "ok");
    final JsonObject response = new JsonObject();
    response.addProperty("id", lastCommandId());
    response.add("result", result);
    api.dispatch(response, null);
  }

  private int lastCommandId() {
    assertFalse("expected a command to the daemon", sentCommands.isEmpty());
    final @NotNull String json = sentCommands.get(sentCommands.size() - 1);
    return JsonParser.parseString(json).getAsJsonObject().get("id").getAsInt();
  }
}
