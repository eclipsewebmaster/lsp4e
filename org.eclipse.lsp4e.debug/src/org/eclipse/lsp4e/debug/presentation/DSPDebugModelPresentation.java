/*******************************************************************************
 * Copyright (c) 2017 Kichwa Coders Ltd. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.debug.presentation;

import java.net.URI;

import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IDisconnect;
import org.eclipse.debug.core.model.ILineBreakpoint;
import org.eclipse.debug.core.model.ITerminate;
import org.eclipse.debug.core.model.IValue;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.ui.IDebugModelPresentation;
import org.eclipse.debug.ui.ISourcePresentation;
import org.eclipse.debug.ui.IValueDetailListener;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.viewers.IFontProvider;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.lsp4e.debug.DSPPlugin;
import org.eclipse.lsp4e.debug.debugmodel.DSPDebugElement;
import org.eclipse.lsp4e.debug.debugmodel.DSPThread;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorRegistry;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.ide.FileStoreEditorInput;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.part.FileEditorInput;

public class DSPDebugModelPresentation extends LabelProvider implements IDebugModelPresentation, IFontProvider {

	private @Nullable Font italic;

	@Override
	public String getText(Object element) {
		final var label = new StringBuilder();
		if (element instanceof DSPThread thread) {
			label.append(NLS.bind("Thread #{0} [{1}]", thread.getId(), thread.getName()));

		}

		if (label.length() != 0) {
			if (element instanceof ITerminate terminate) {
				if (terminate.isTerminated()) {
					label.insert(0, "<terminated>");
				}
			} else if (element instanceof IDisconnect disconned && disconned.isDisconnected()) {
				label.insert(0, "<disconnected>");
			}
		} else {
			// Use default TODO should the entire default be copied here?
			label.append(DebugUIPlugin.getDefaultLabelProvider().getText(element));
		}
		if (element instanceof DSPDebugElement debugElement) {
			if (debugElement.getErrorMessage() != null) {
				label.append(" <error:");
				label.append(debugElement.getErrorMessage());
				label.append('>');
			}
		}
		return label.toString();
	}

	@Override
	public @Nullable Font getFont(Object element) {
		if (element instanceof DSPDebugElement debugElement) {
			if (debugElement.getErrorMessage() != null) {
				return italic();
			}
		}
		return null;
	}

	private Font italic() {
		if (this.italic != null) {
			return this.italic;
		}

		Font dialogFont = JFaceResources.getDialogFont();
		FontData[] fontData = dialogFont.getFontData();
		for (final FontData data : fontData) {
			data.setStyle(SWT.ITALIC);
		}
		Display display = getDisplay();
		this.italic = new Font(display, fontData);
		return this.italic;
	}

	@Override
	public void dispose() {
		if (italic != null) {
			italic.dispose();
		}
		super.dispose();
	}

	@Override
	public @Nullable IEditorInput getEditorInput(Object element) {
		if (element instanceof ILineBreakpoint lineBreakpoint) {
			return new FileEditorInput((IFile) lineBreakpoint.getMarker().getResource());
		}
		if (element instanceof IFile file) {
			return new FileEditorInput(file);
		}

		IFileStore fileStore = EFS.getLocalFileSystem().getStore(new Path(element.toString()));
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IFile[] files = root.findFilesForLocationURI(fileStore.toURI());
		if (files != null) {
			for (IFile file : files) {
				if (file.exists()) {
					return new FileEditorInput(file);
				}
			}
		}
		return new FileStoreEditorInput(fileStore);
	}

	@Override
	public @Nullable String getEditorId(IEditorInput input, Object element) {
		String id = null;
		if (input != null) {
			IEditorDescriptor descriptor = null;
			if (input instanceof IFileEditorInput fileEditorInput) {
				IFile file = fileEditorInput.getFile();
				descriptor = IDE.getDefaultEditor(file);
			} else if (input instanceof IURIEditorInput uriEditorInput) {
				URI uri = uriEditorInput.getURI();
				try {
					IFileStore fileStore = EFS.getStore(uri);
					id = IDE.getEditorDescriptorForFileStore(fileStore, false).getId();
				} catch (CoreException e) {
					// fallback to default case
				}
			}
			if (id == null) {
				if (descriptor == null) {
					IEditorRegistry registry = PlatformUI.getWorkbench().getEditorRegistry();
					descriptor = registry.getDefaultEditor(input.getName());
				}

				id = "org.eclipse.ui.genericeditor.GenericEditor";
				if (descriptor != null) {
					id = descriptor.getId();
				}
			}

			if (id == null && element instanceof ILineBreakpoint) {
				// There is no associated editor ID for this breakpoint, see if an alternative
				// can be supplied from an adapter.
				ISourcePresentation sourcePres = Platform.getAdapterManager().getAdapter(element,
						ISourcePresentation.class);
				if (sourcePres != null) {
					String lid = sourcePres.getEditorId(input, element);
					if (lid != null) {
						id = lid;
					}
				}
			}
		}
		return id;
	}

	@Override
	public void setAttribute(String attribute, @Nullable Object value) {
	}

	@Override
	public void computeDetail(IValue value, IValueDetailListener listener) {
		try {
			listener.detailComputed(value, value.getValueString());
		} catch (DebugException e) {
			// Should not happen, because DSPValue does not throw this exception
			DSPPlugin.logError("Failed to compute detail value", e);
		}
	}

	public static Display getDisplay() {
		Display display;
		display = Display.getCurrent();
		if (display == null) {
			display = Display.getDefault();
		}
		return display;
	}

}
