/*
 * Copyright (c) 2012 Google Inc.
 *
 * All rights reserved. This program and the accompanying materials are made available under the terms of the Eclipse
 * Public License v1.0 which accompanies this distribution, and is available at
 *
 * http://www.eclipse.org/legal/epl-v10.html
 */
package com.google.eclipse.elt.view.ui;

import static org.eclipse.core.runtime.Path.fromOSString;
import static org.eclipse.core.runtime.Status.OK_STATUS;
import static org.eclipse.jface.resource.JFaceResources.TEXT_FONT;
import static org.eclipse.jface.window.Window.OK;
import static org.eclipse.ui.IWorkbenchPage.VIEW_ACTIVATE;
import static com.google.eclipse.elt.view.Activator.*;
import static com.google.eclipse.elt.view.ImageKeys.*;
import static com.google.eclipse.elt.view.preferences.ColorsAndFontsPreferences.*;
import static com.google.eclipse.elt.view.preferences.GeneralPreferences.*;
import static com.google.eclipse.elt.view.ui.Messages.*;
import static com.google.eclipse.elt.view.util.Platform.userHomeDirectory;

import org.eclipse.core.runtime.*;
import org.eclipse.jface.action.*;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.util.*;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.ui.contexts.*;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.progress.UIJob;

import com.google.eclipse.elt.emulator.control.ITerminalListener;
import com.google.eclipse.elt.emulator.provisional.api.TerminalState;
import com.google.eclipse.elt.view.connector.LifeCycleListener;
import com.google.eclipse.elt.view.preferences.AbstractPreferencesChangeListener;

/**
 * @author alruiz@google.com (Alex Ruiz)
 */
public class TerminalView extends ViewPart implements ISaveablePart2 {
  private static final String SCROLL_LOCK_ENABLED = "scrollLock";
  private static final String TITLE_STATE_TYPE = "title";
  private static final String WORKING_DIRECTORY_STATE_TYPE = "workingDirectory";

  private static final String VIEW_ID = "com.google.eclipse.terminal.local.localTerminalView";

  private IPropertyChangeListener preferencesChangeListener;
  private IPropertyChangeListener textFontChangeListener;
  private IMemento savedState;
  private TerminalWidget terminalWidget;
  private IPath workingDirectory;

  private Action newTerminalAction;
  private Action scrollLockAction;

  private boolean checkCanBeClosed;
  private boolean forceClose;

  private IContextActivation contextActivation;

  /**
   * Creates a new terminal view.
   * @param workingDirectory
   */
  public static void openTerminalView(IPath workingDirectory) {
    openNewTerminalView(workingDirectory);
  }

  private static int gensym = 0;
  
  /**
   * Returns a fresh integer.
   */
  private static Integer nextGensym() {
    return gensym++;
  }
  
  /**
   * Opens a new {@link TerminalView}.
   * 
   * @param workingDirectory The working directory to be opened on initialization.
   */
  private static void openNewTerminalView(IPath workingDirectory) {
    IWorkbenchPage page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    IPath safeWorkingDirectory = (workingDirectory != null) ? workingDirectory : userHomeDirectory();
    try {
      String directoryName = safeWorkingDirectory.lastSegment();
      // Use of gensym to force freshness of the identifier.
      String secondaryId = nextGensym().toString();
      TerminalView view = (TerminalView) page.showView(VIEW_ID, secondaryId, VIEW_ACTIVATE);
      view.setPartName(directoryName);
      view.open(safeWorkingDirectory);
    } catch (PartInitException e) {
      log("Unable to create Terminal View", e);
    }
  }

  @Override public void init(IViewSite site, IMemento memento) throws PartInitException {
    super.init(site, memento);
    savedState = memento;
  }

  @Override public void saveState(IMemento memento) {
    saveState(memento, SCROLL_LOCK_ENABLED, String.valueOf(terminalWidget.isScrollLockEnabled()));
    saveState(memento, TITLE_STATE_TYPE, getPartName());
    saveState(memento, WORKING_DIRECTORY_STATE_TYPE, workingDirectory.toOSString());
  }

  private void saveState(IMemento memento, String type, String data) {
    IMemento child = memento.createChild(type);
    child.putTextData(data);
  }

  @Override public void createPartControl(Composite parent) {
    terminalWidget = new TerminalWidget(parent, getViewSite());
    terminalWidget.setLifeCycleListener(new LifeCycleListener() {
      @Override public void executionFinished() {
        closeViewOnExitIfPossible();
      }
    });
    terminalWidget.setTerminalListener(new ITerminalListener() {
      @Override public void setTerminalTitle(final String title) {
        updatePartName(title);
      }

      @Override public void setState(TerminalState state) {}
    });
    IViewSite viewSite = getViewSite();
    preferencesChangeListener = new AbstractPreferencesChangeListener() {
      @Override protected void onBufferLineCountChanged() {
        updateBufferLineCount();
      }

      @Override protected void onColorChanged() {
        updateColors();
      }

      @Override protected void onFontChanged() {
        updateFont();
      }

      @Override protected void onUseBlinkingCursorChanged() {
        updateUsageOfBlinkingCursor();
      }
    };
    preferenceStore().addPropertyChangeListener(preferencesChangeListener);
    updateBufferLineCount();
    updateColors();
    updateUsageOfBlinkingCursor();
    textFontChangeListener = new IPropertyChangeListener() {
      @Override public void propertyChange(PropertyChangeEvent event) {
        if (TEXT_FONT.equals(event.getProperty())) {
          if (!useCustomFont()) {
            setFont(JFaceResources.getTextFont());
          }
        }
      }
    };
    JFaceResources.getFontRegistry().addListener(textFontChangeListener);
    updateFont();
    setupToolBarActions();
    IContextService contextService = contextService();
    if (contextService != null) {
      contextActivation = contextService.activateContext("com.google.eclipse.terminal.local.context.localTerminal");
    }
    if (savedState != null) {
      updateScrollLockUsingSavedState();
      connectUsingSavedState();
      return;
    }
    if (viewSite.getSecondaryId() == null) {
      setPartName(defaultViewTitle);
      open(userHomeDirectory());
    }
    enableScrollLock(scrollLockAction.isChecked());
  }

  private void closeViewOnExitIfPossible() {
    if (closeViewOnExit() && terminalWidget != null && !terminalWidget.isDisposed()) {
      // must run in UI thread.
      forceClose = true;
      terminalWidget.getDisplay().asyncExec(new Runnable() {
        @Override public void run() {
          IWorkbenchPartSite site = getSite();
          site.getPage().hideView((IViewPart) site.getPart());
        }
      });
    }
  }

  private void updateColors() {
    terminalWidget.setColors(background(), foreground());
  }

  private void updateFont() {
    setFont(terminalFont());
  }

  private void updateUsageOfBlinkingCursor() {
    terminalWidget.setBlinkingCursor(useBlinkingCursor());
  }

  private Font terminalFont() {
    if (useCustomFont()) {
      return new Font(Display.getDefault(), customFontData());
    }
    return JFaceResources.getTextFont();
  }

  private void setFont(Font font) {
    terminalWidget.setFont(font);
  }

  private void updateBufferLineCount() {
    terminalWidget.setBufferLineCount(bufferLineCount());
  }

  private void setupToolBarActions() {
    IToolBarManager toolBarManager = getViewSite().getActionBars().getToolBarManager();
    toolBarManager.add(new ChangeViewNameAction());
    toolBarManager.add(new Separator());
    newTerminalAction = new NewTerminalAction();
    toolBarManager.add(newTerminalAction);
    scrollLockAction = new ScrollLockAction();
    toolBarManager.add(scrollLockAction);
  }

  private void updateScrollLockUsingSavedState() {
    boolean newValue = Boolean.valueOf(savedState(SCROLL_LOCK_ENABLED));
    enableScrollLockAndUpdateAction(newValue);
  }

  private void enableScrollLockAndUpdateAction(boolean enabled) {
    enableScrollLock(enabled);
    scrollLockAction.setChecked(enabled);
  }

  private void enableScrollLock(boolean enabled) {
    terminalWidget.enableScrollLock(enabled);
  }

  private void connectUsingSavedState() {
    String title = savedState(TITLE_STATE_TYPE);
    setPartName(title);
    String savedWorkingDirectory = savedState(WORKING_DIRECTORY_STATE_TYPE);
    if (savedWorkingDirectory != null) {
      open(fromOSString(savedWorkingDirectory));
    }
  }

  private String savedState(String type) {
    IMemento child = savedState.getChild(type);
    return (child != null) ? child.getTextData() : null;
  }

  private void open(IPath workingDirectory) {
    if (terminalWidget.isConnected()) {
      return;
    }
    this.workingDirectory = workingDirectory;
    terminalWidget.setWorkingDirectory(workingDirectory);
    terminalWidget.connect();
  }

  private void updatePartName(final String value) {
    UIJob job = new UIJob("Update terminal view title") {
      @Override public IStatus runInUIThread(IProgressMonitor monitor) {
        setPartName(value);
        return OK_STATUS;
      }
    };
    job.schedule();
  }

  @Override public void setFocus() {
    terminalWidget.setFocus();
  }

  @Override public void dispose() {
    if (contextActivation != null) {
      IContextService contextService = contextService();
      if (contextService != null) {
        contextService.deactivateContext(contextActivation);
      }
    }
    if (preferencesChangeListener != null) {
      preferenceStore().removePropertyChangeListener(preferencesChangeListener);
    }
    if (textFontChangeListener != null) {
      JFaceResources.getFontRegistry().removeListener(textFontChangeListener);
    }
    super.dispose();
  }

  private IContextService contextService() {
    return (IContextService) getSite().getService(IContextService.class);
  }

  @Override public boolean isDirty() {
    if (checkCanBeClosed) {
      checkCanBeClosed = false;
      return true;
    }
    return false;
  }

  @Override public boolean isSaveOnCloseNeeded() {
    if (forceClose) {
      return false;
    }
    checkCanBeClosed = true;
    return true;
  }

  @Override public int promptToSaveOnClose() {
    if (warnOnClose()) {
      boolean close = WarnOnCloseDialog.open(terminalWidget.getShell());
      if (!close) {
        return CANCEL;
      }
    }
    return NO;
  }

  @Override public void doSave(IProgressMonitor monitor) {}

  @Override public void doSaveAs() {}

  @Override public boolean isSaveAsAllowed() {
    return false;
  }

  private class NewTerminalAction extends Action {
    NewTerminalAction() {
      setImageDescriptor(imageDescriptor(NEW_TERMINAL));
      setText(newLocalTerminal);
    }

    @Override public void run() {
      openNewTerminalView(workingDirectory);
    }
  }

  private class ScrollLockAction extends Action {
    ScrollLockAction() {
      super(scrollLock, AS_RADIO_BUTTON);
      setChecked(false);
      setImageDescriptor(imageDescriptor(SCROLL_LOCK));
    }

    @Override public void run() {
      boolean newValue = !terminalWidget.isScrollLockEnabled();
      enableScrollLockAndUpdateAction(newValue);
    }
  }

  private class ChangeViewNameAction extends Action {
    ChangeViewNameAction() {
      setImageDescriptor(imageDescriptor(CHANGE_TITLE));
      setText(changeTerminalTitle);
    }

    @Override public void run() {
      Shell shell = getViewSite().getShell();
      final String currentTitle = getPartName();
      InputDialog input = new InputDialog(shell, enterTerminalTitleDialogTitle, enterTerminalTitlePrompt, currentTitle,
          new IInputValidator() {
            @Override public String isValid(String newText) {
              if (newText == null || newText.isEmpty() || currentTitle.equals(newText)) {
                return "";
              }
              return null;
            }
          });
      input.setBlockOnOpen(true);
      if (input.open() == OK) {
        setPartName(input.getValue());
      }
    }
  }
}
