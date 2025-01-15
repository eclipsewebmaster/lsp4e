/*******************************************************************************
 * Copyright (c) 2016, 2017 Rogue Wave Software Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Michał Niewrzał (Rogue Wave Software Inc.) - initial implementation
 *  Joao Dinis Ferreira (Avaloq Group AG) - Create splitActiveEditor
 *******************************************************************************/
package org.eclipse.lsp4e.test.utils;

import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.workbench.IPresentationEngine;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.ContentTypeToLanguageServerDefinition;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServersRegistry;
import org.eclipse.lsp4e.internal.ArrayUtil;
import org.eclipse.lsp4e.tests.mock.MockLanguageServer;
import org.eclipse.lsp4e.ui.UI;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.Widget;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.tests.harness.util.DisplayHelper;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;

public class TestUtils {

	@FunctionalInterface
	public interface Condition {
		boolean isMet() throws Exception;
	}

	private static final Set<File> tempFiles = new HashSet<>();

	private TestUtils() {
		// this class shouldn't be instantiated
	}

	public static ITextViewer openTextViewer(IFile file) throws PartInitException {
		IEditorPart editor = openEditor(file);
		waitForAndAssertCondition(5_000, () -> LSPEclipseUtils.getTextViewer(editor) != null);
		return LSPEclipseUtils.getTextViewer(editor);
	}

	public static IEditorPart openEditor(IFile file) throws PartInitException {
		IWorkbenchWindow workbenchWindow = UI.getActiveWindow();
		IWorkbenchPage page = workbenchWindow.getActivePage();
		final var input = new FileEditorInput(file);

		IEditorPart part = page.openEditor(input, "org.eclipse.ui.genericeditor.GenericEditor", false);
		if (part != null) {
			part.setFocus();
		}
		return part;
	}

	public static List<IEditorReference> splitActiveEditor() {
		IWorkbenchWindow workbenchWindow = UI.getActiveWindow();
		IWorkbenchPage page = workbenchWindow.getActivePage();
		IEditorPart part = page.getActiveEditor();

		MPart editorPart = part.getSite().getService(MPart.class);
		if (editorPart != null) {
			editorPart.getTags().add(IPresentationEngine.SPLIT_HORIZONTAL);
		}

		return List.of(page.getEditorReferences());
	}

	public static IEditorPart openExternalFileInEditor(File file) throws PartInitException {
		IWorkbenchWindow workbenchWindow = UI.getActiveWindow();
		IWorkbenchPage page = workbenchWindow.getActivePage();
		IEditorPart part = IDE.openEditor(page, file.toURI(), "org.eclipse.ui.genericeditor.GenericEditor", false);
		if (part != null) {
			part.setFocus();
		}
		return part;
	}

	public static IEditorPart getEditor(IFile file) {
		IWorkbenchWindow workbenchWindow = UI.getActiveWindow();
		IWorkbenchPage page = workbenchWindow.getActivePage();
		final var input = new FileEditorInput(file);

		return List.of(page.getEditorReferences()).stream()
			.filter(r -> {
				try {
					return r.getEditorInput().equals(input);
				} catch (PartInitException e) {
					return false;
				}
			})
			.map(r -> r.getEditor(false))
			.findAny().orElse(null);
	}

	public static IEditorPart getActiveEditor() {
		IWorkbenchWindow workbenchWindow = UI.getActiveWindow();
		IWorkbenchPage page = workbenchWindow.getActivePage();
		return page.getActiveEditor();
	}

	public static boolean closeEditor(IEditorPart editor, boolean save) {
		IWorkbenchWindow workbenchWindow = UI.getActiveWindow();
		IWorkbenchPage page = workbenchWindow.getActivePage();
		return page.closeEditor(editor, save);
	}

	public static IProject createProject(String projectName) throws CoreException {
		IWorkspace ws = ResourcesPlugin.getWorkspace();
		IProject project = ws.getRoot().getProject(projectName);
		if (project.exists() && project.isOpen()) {
			return project;
		}

		// avoids java.lang.IllegalArgumentException: Attempted to beginRule:
		// P/WorkspaceFoldersTest_testPojectCreate_1726575959224, does not match outer scope rule: P/
		ws.run(monitor -> {
			if (!project.exists()) {
				project.create(null);
			}
			project.open(null);
		}, ws.getRoot(), IWorkspace.AVOID_UPDATE, null); // Ensure proper scheduling

		return project;
	}

	public static IProject createNestedProject(IProject parent, String projectName) throws CoreException {
		IFolder nestedFolder = parent.getFolder(projectName);
		nestedFolder.create(true, true, null);

		IWorkspace ws = ResourcesPlugin.getWorkspace();
		IProject project = ws.getRoot().getProject(projectName);
		if (project.exists() && project.isOpen()) {
			return project;
		}

		// avoids java.lang.IllegalArgumentException: Attempted to beginRule:
		// P/WorkspaceFoldersTest_testPojectCreate_1726575959224, does not match outer scope rule: P/
		ws.run(monitor -> {
			if (!project.exists()) {
				IProjectDescription desc = ws.newProjectDescription(projectName);
				desc.setLocation(nestedFolder.getLocation());
				project.create(desc, null);
			}
			project.open(null);
		}, ws.getRoot(), IWorkspace.AVOID_UPDATE, null); // Ensure proper scheduling

		return project;
	}

	public static IFile createUniqueTestFile(IProject p, String content) throws CoreException {
		return createUniqueTestFile(p, "lspt", content);
	}

	public static IFile createUniqueTestFileMultiLS(IProject p, String content) throws CoreException {
		return createUniqueTestFile(p, "lsptmultils", content);
	}

	public static IFile createUniqueTestFileOfUnknownType(IProject p, String content) throws CoreException {
		return createUniqueTestFile(p, "lsptunknown", content);
	}

	public static synchronized IFile createUniqueTestFile(IProject p, String extension, String content)
			throws CoreException {
		long fileNameSalt = System.currentTimeMillis();
		if (p == null) {
			p = ResourcesPlugin.getWorkspace().getRoot().getProject(Long.toString(fileNameSalt));
			p.create(null);
			p.open(null);
		}
		while (p.getFile("test" + fileNameSalt + '.' + extension).exists()) {
			fileNameSalt++;
		}
		return createFile(p, "test" + fileNameSalt + '.' + extension, content);
	}

	public static IFile createFile(IProject p, String name, String content) throws CoreException {
		IFile testFile = p.getFile(name);
		testFile.create(new ByteArrayInputStream(content.getBytes()), true, null);
		return testFile;
	}

	public static void delete(IProject project) throws CoreException {
		if (project != null) {
			project.delete(true, null);
		}
	}

	public static void delete(IProject... projects) throws CoreException {
		ArrayUtil.forEach(projects, TestUtils::delete);
	}

	public static void delete(Path path) throws IOException {
		if (path != null && Files.exists(path)) {
			MoreFiles.deleteRecursively(path, RecursiveDeleteOption.ALLOW_INSECURE);
		}
	}

	public static void delete(Path... paths) throws IOException {
		ArrayUtil.forEach(paths, TestUtils::delete);
	}

	public static File createTempFile(String prefix, String suffix) throws IOException {
		File tmp = File.createTempFile(prefix, suffix);
		tempFiles.add(tmp);
		return tmp;
	}

	public static void addManagedTempFile(File file) {
		tempFiles.add(file);
	}

	public static void tearDown() {
		tempFiles.forEach(file -> {
			try {
				Files.deleteIfExists(file.toPath());
			} catch (IOException e) {
				// Trying to have the tests run quieter but I suppose if there's an actual
				// problem we'd better find out about it
				e.printStackTrace();
			}
		});
		tempFiles.clear();
	}

	public static ContentTypeToLanguageServerDefinition getDisabledLS() {
		return LanguageServersRegistry.getInstance().getContentTypeToLSPExtensions().stream()
				.filter(definition -> "org.eclipse.lsp4e.test.server.disable".equals(definition.getValue().id)
						&& "org.eclipse.lsp4e.test.content-type-disabled".equals(definition.getKey().toString()))
				.findFirst().get();
	}

	public static Shell findNewShell(Set<Shell> beforeShells, Display display) {
		Shell[] afterShells = ArrayUtil.filter(display.getShells(),
				shell -> shell.isVisible() && !beforeShells.contains(shell));
		return afterShells.length > 0 ? afterShells[0] : null;
	}

	public static Table findCompletionSelectionControl(Widget control) {
		if (control instanceof Table table) {
			return table;
		} else if (control instanceof Composite composite) {
			for (Widget child : composite.getChildren()) {
				Table res = findCompletionSelectionControl(child);
				if (res != null) {
					return res;
				}
			}
		}
		return null;
	}

	public static IEditorReference[] getEditors() {
		IWorkbenchWindow wWindow = UI.getActiveWindow();
		if (wWindow != null) {
			IWorkbenchPage wPage = wWindow.getActivePage();
			if (wPage != null) {
				return wPage.getEditorReferences();
			}
		}
		return null;
	}

	public static void waitForAndAssertCondition(int timeout_ms, Runnable condition) {
		waitForAndAssertCondition("Condition not met within expected time.", timeout_ms, condition);
	}

	public static void waitForAndAssertCondition(int timeout_ms, Display display, Runnable condition) {
		waitForAndAssertCondition("Condition not met within expected time.", timeout_ms, display, () -> {
			condition.run();
			return true;
		});
	}

	public static void waitForAndAssertCondition(String errorMessage, int timeout_ms, Runnable condition) {
		waitForAndAssertCondition(errorMessage, timeout_ms, UI.getDisplay(), () -> {
			condition.run();
			return true;
		});
	}

	public static void waitForAndAssertCondition(int timeout_ms, Condition condition) {
		waitForAndAssertCondition("Condition not met within expected time.", timeout_ms, condition);
	}

	public static void waitForAndAssertCondition(int timeout_ms, Display display, Condition condition) {
		waitForAndAssertCondition("Condition not met within expected time.", timeout_ms, display, condition);
	}

	public static void waitForAndAssertCondition(String errorMessage, int timeout_ms, Condition condition) {
		waitForAndAssertCondition(errorMessage, timeout_ms, UI.getDisplay(), condition);
	}

	public static void waitForAndAssertCondition(String errorMessage, int timeout_ms, Display display,
			Condition condition) {
		var ex = new Throwable[1];
		var isConditionMet = new DisplayHelper() {
			@Override
			protected boolean condition() {
				try {
					var isMet = condition.isMet();
					ex[0] = null;
					return isMet;
				} catch (AssertionError | Exception e) {
					ex[0] = e;
					return false;
				}
			}
		}.waitForCondition(display, timeout_ms, 50);
		if (ex[0] != null) {
			// if the condition was not met because of an exception throw it
			if (ex[0] instanceof AssertionError ae) {
				throw ae;
			}
			if (ex[0] instanceof RuntimeException re) {
				throw re;
			}
			throw new AssertionError(errorMessage, ex[0]);
		}
		assertTrue(errorMessage, isConditionMet);
	}

	public static boolean waitForCondition(int timeout_ms, Condition condition) {
		return waitForCondition(timeout_ms, UI.getDisplay(), condition);
	}

	public static boolean waitForCondition(int timeout_ms, Display display, Condition condition) {
		var ex = new Throwable[1];
		var isConditionMet = new DisplayHelper() {
			@Override
			protected boolean condition() {
				try {
					var isMet = condition.isMet();
					ex[0] = null;
					return isMet;
				} catch (AssertionError | Exception e) {
					ex[0] = e;
					return false;
				}
			}
		}.waitForCondition(display, timeout_ms, 50);
		if (ex[0] != null) {
			// if the condition was not met because of an exception log it
			ex[0].printStackTrace();
		}
		return isConditionMet;
	}

	public void waitForLanguageServerNotRunning(MockLanguageServer server) {
		assertTrue(new DisplayHelper() {
			@Override
			protected boolean condition() {
				return !server.isRunning();
			}
		}.waitForCondition(UI.getDisplay(), 1000));
	}

	public static class JobSynchronizer extends NullProgressMonitor {
		private final CountDownLatch latch = new CountDownLatch(1);

		@Override
		public void done() {
			latch.countDown();
		}

		@Override
		public void setCanceled(boolean cancelled) {
			super.setCanceled(cancelled);
			if (cancelled) {
				latch.countDown();
			}
		}

		public void await() throws InterruptedException {
			latch.await();
		}

		@Override
		public void worked(int work) {
			latch.countDown();
		}
	}

	public static Condition numberOfChangesIs(int changes) {
		return () -> MockLanguageServer.INSTANCE.getDidChangeEvents().size() == changes;
	}
}
