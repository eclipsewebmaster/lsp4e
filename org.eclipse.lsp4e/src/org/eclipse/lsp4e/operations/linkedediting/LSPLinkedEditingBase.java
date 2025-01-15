/*******************************************************************************
 * Copyright (c) 2021 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Victor Rubezhny (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.operations.linkedediting;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4j.LinkedEditingRanges;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentPositionParams;

public class LSPLinkedEditingBase implements IPreferenceChangeListener {
	public static final String LINKED_EDITING_PREFERENCE = "org.eclipse.ui.genericeditor.linkedediting"; //$NON-NLS-1$

	private @Nullable CompletableFuture<List<LinkedEditingRanges>> request;
	protected boolean fEnabled;

	protected void install() {
		IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID);
		preferences.addPreferenceChangeListener(this);
		this.fEnabled = preferences.getBoolean(LINKED_EDITING_PREFERENCE, true);
	}

	protected void uninstall() {
		IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID);
		preferences.removePreferenceChangeListener(this);
		cancel();
	}

	protected CompletableFuture<Optional<LinkedEditingRanges>> collectLinkedEditingRanges(@Nullable IDocument document, int offset) {
		cancel();

		if (document == null) {
			return CompletableFuture.completedFuture(null);
		}
		try {
			TextDocumentPositionParams params = LSPEclipseUtils.toTextDocumentPosistionParams(offset, document);
			request = LanguageServers.forDocument(document).withCapability(ServerCapabilities::getLinkedEditingRangeProvider)
					.collectAll(languageServer -> languageServer.getTextDocumentService()
							.linkedEditingRange(LSPEclipseUtils.toLinkedEditingRangeParams(params)));
			return request.thenApply(linkedEditRanges -> linkedEditRanges.stream().filter(Objects::nonNull)
							.filter(linkedEditRange -> rangesContainOffset(linkedEditRange, offset, document)).findFirst());
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return CompletableFuture.completedFuture(null);
		}
	}

	private boolean rangesContainOffset(LinkedEditingRanges ranges, int offset, IDocument document) {
		for (Range range : ranges.getRanges()) {
			if (LSPEclipseUtils.isOffsetInRange(offset, range, document)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Cancel the last call of 'linkedEditing'.
	 */
	private void cancel() {
		if (request != null) {
			request.cancel(true);
			request = null;
		}
	}

	@Override
	public void preferenceChange(PreferenceChangeEvent event) {
		if (event.getKey().equals(LINKED_EDITING_PREFERENCE)) {
			this.fEnabled = Boolean.parseBoolean(event.getNewValue().toString());
		}
	}
}
