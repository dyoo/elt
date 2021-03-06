/*
 * Copyright (c) 2012 Google Inc.
 *
 * All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License v1.0 which accompanies this distribution, and is available at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.google.eclipse.elt.view.connector;

import static org.eclipse.core.runtime.IStatus.*;

import static com.google.eclipse.elt.emulator.provisional.api.TerminalState.*;
import static com.google.eclipse.elt.pty.util.Platform.*;
import static com.google.eclipse.elt.view.Activator.*;
import static com.google.eclipse.elt.view.connector.Messages.*;
import static com.google.eclipse.elt.view.connector.PseudoTerminal.isPlatformSupported;
import static com.google.eclipse.elt.view.util.Platform.*;

import java.io.*;

import org.eclipse.core.runtime.*;
import org.eclipse.debug.core.IStreamListener;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.internal.core.StreamsProxy;
import org.eclipse.osgi.util.NLS;

import com.google.eclipse.elt.emulator.connector.TerminalConnector;
import com.google.eclipse.elt.emulator.provisional.api.*;
import com.google.eclipse.elt.emulator.provisional.api.provider.TerminalConnectorDelegate;

/**
 * Connector to local terminal.
 *
 * @author alruiz@google.com (Alex Ruiz)
 */
@SuppressWarnings("restriction") // StreamsProxy is internal API
public class LocalTerminalConnector extends TerminalConnectorDelegate implements LifeCycleListener {
  private static final String ID = "com.google.eclipse.terminal.local.core.connector";

  public static ITerminalConnector createLocalTerminalConnector(final String encoding) {
    TerminalConnector.Factory factory = new TerminalConnector.Factory(){
      @Override public TerminalConnectorDelegate makeConnector() {
        return new LocalTerminalConnector(encoding);
      }
    };
    TerminalConnector connector = new TerminalConnector(factory, ID, localTerminalName);
    String errorMessage = connector.getInitializationErrorMessage();
    if (errorMessage != null) {
      throw new IllegalStateException(errorMessage);
    }
    return connector;
  }

  private IPath workingDirectory;
  private PseudoTerminal pseudoTerminal;

  private StreamsProxy streamsProxy;
  private OutputStream terminalToRemoteStream;

  private final String encoding;

  private LocalTerminalConnector(String encoding) {
    this.encoding = encoding;
  }

  /**
   * Verifies that PTY support is available on this platform.
   * @throws CoreException if PTY support is <strong>not</strong> available on this platform.
   * @see TerminalConnectorDelegate#initialize()
   */
  @Override public void initialize() throws CoreException {
    if (!isPlatformSupported()) {
      String message = NLS.bind(errorNoPseudoTerminalSupport, getOS(), getOSArch());
      throw new CoreException(new Status(WARNING, PLUGIN_ID, message));
    }
  }

  @Override protected void connect() {
    terminalControl.setState(CONNECTING);
    File workingDirectory = workingDirectory();
    pseudoTerminal = new PseudoTerminal(workingDirectory);
    pseudoTerminal.addLifeCycleListener(this);
    try {
      pseudoTerminal.launch();
      streamsProxy = new StreamsProxy(pseudoTerminal.systemProcess(), encoding);
      terminalToRemoteStream = new BufferedOutputStream(new TerminalOutputStream(streamsProxy, encoding), 1024);
      addListeners(terminalControl, streamsProxy.getOutputStreamMonitor(), streamsProxy.getErrorStreamMonitor());
      if (streamsProxy != null) {
        terminalControl.setState(CONNECTED);
        return;
      }
    } catch (Throwable t) {
      log(new Status(INFO, PLUGIN_ID, OK, "Unable to start terminal", t));
    }
    terminalControl.setState(CLOSED);
  }

  private File workingDirectory() {
    IPath path = (workingDirectory != null) ? workingDirectory : userHomeDirectory();
    if (path == null) {
      return null;
    }
    File file = path.toFile();
    return (file.isDirectory()) ? file : null;
  }

  private void addListeners(ITerminalControl control, IStreamMonitor...monitors) throws UnsupportedEncodingException {
    for (IStreamMonitor monitor : monitors) {
      addListener(monitor, new TerminalOutputListener(control, encoding));
    }
  }

  private void addListener(IStreamMonitor monitor, IStreamListener listener) {
    monitor.addListener(listener);
    listener.streamAppended(monitor.getContents(), monitor);
  }

  @Override public OutputStream getTerminalToRemoteStream() {
    return terminalToRemoteStream;
  }

  /**
   * Returns the system's default shell location as the settings summary.
   * @return the system's default shell location as the settings summary.
   */
  @Override public String getSettingsSummary() {
    return defaultShell().toString();
  }

  /**
   * Notifies the pseudo-terminal that the size of the terminal has changed.
   * @param newWidth the new terminal width (in columns.)
   * @param newHeight the new terminal height (in lines.)
   */
  @Override public void setTerminalSize(int newWidth, int newHeight) {
    if (pseudoTerminal != null) {
      pseudoTerminal.updateSize(newWidth, newHeight);
    }
  }

  public void setWorkingDirectory(IPath workingDirectory) {
    this.workingDirectory = workingDirectory;
  }

  @Override protected void onDisconnect() {
    pseudoTerminal.disconnect();
  }

  @Override public void executionFinished() {
    terminalControl.setState(CLOSED);
    if (streamsProxy != null) {
      streamsProxy.close();
    }
  }

  public void addLifeCycleListener(LifeCycleListener listener) {
    pseudoTerminal.addLifeCycleListener(listener);
  }
}
