/*******************************************************************************
 * Copyright (c) 2016, 2022 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Michał Niewrzał (Rogue Wave Software Inc.)
 *  Rubén Porras Campo (Avaloq Evolution AG) - documentAboutToBeSaved implementation
 *******************************************************************************/
package org.eclipse.lsp4e;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.io.File;
import java.net.URI;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.MultiTextSelection;
import org.eclipse.lsp4e.format.IFormatRegionsProvider;
import org.eclipse.lsp4e.internal.DocumentUtil;
import org.eclipse.lsp4e.operations.format.LSPFormatter;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentRangeFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextDocumentSaveReason;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WillSaveTextDocumentParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.osgi.util.NLS;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

final class DocumentContentSynchronizer implements IDocumentListener {

	private final LanguageServerWrapper languageServerWrapper;
	private final IDocument document;
	private final URI fileUri;
	private final TextDocumentSyncKind syncKind;

	private int version = 0;
	private @Nullable DidChangeTextDocumentParams changeParams;
	private long openSaveStamp;
	private IPreferenceStore store;
	private @Nullable IFormatRegionsProvider formatRegionsProvider;

	public DocumentContentSynchronizer(LanguageServerWrapper languageServerWrapper, LanguageServer languageServer,
			IDocument document, @Nullable TextDocumentSyncKind syncKind) {
		this.languageServerWrapper = languageServerWrapper;
		URI uri = LSPEclipseUtils.toUri(document);
		if (uri == null) {
			throw new NullPointerException();
		}
		this.fileUri = uri;
		try {
			IFileStore store = EFS.getStore(fileUri);
			this.openSaveStamp = store.fetchInfo().getLastModified();
		} catch (CoreException e) {
			try {
				this.openSaveStamp = new File(fileUri).lastModified();
			} catch (IllegalArgumentException iae) {
				this.openSaveStamp = 0L;
			}
		}
		this.syncKind = syncKind != null ? syncKind : TextDocumentSyncKind.Full;

		this.document = document;
		this.store = LanguageServerPlugin.getDefault().getPreferenceStore();

		// add a document buffer
		final var textDocument = new TextDocumentItem();
		textDocument.setUri(fileUri.toASCIIString());
		textDocument.setText(document.get());

		List<IContentType> contentTypes = LSPEclipseUtils.getDocumentContentTypes(this.document);

		String languageId = languageServerWrapper.getLanguageId(contentTypes.toArray(IContentType[]::new));

		if (languageId == null && this.fileUri.getPath() != null) {
			IPath path = Path.fromPortableString(this.fileUri.getPath());
			languageId = path.getFileExtension();
			if (languageId == null) {
				languageId = path.lastSegment();
			}
		}
		if (languageId == null && !this.fileUri.getSchemeSpecificPart().isEmpty()) {
			String part = this.fileUri.getSchemeSpecificPart();
			int lastSeparatorIndex = Math.max(part.lastIndexOf('.'), part.lastIndexOf('/'));
			languageId = part.substring(lastSeparatorIndex + 1);
		}
		if (languageId == null) {
			String uriString = uri.toString();
			int lastSeparatorIndex = Math.max(uriString.lastIndexOf('.'), uriString.lastIndexOf('/'));
			languageId = uriString.substring(lastSeparatorIndex + 1);
		}

		textDocument.setLanguageId(languageId);
		textDocument.setVersion(++version);
		languageServer.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(textDocument));
	}


	@Override
	public void documentChanged(DocumentEvent event) {
		checkEvent(event);
		if (syncKind == TextDocumentSyncKind.Full) {
			createChangeEvent(event);
		}

		if (changeParams != null) {
			final DidChangeTextDocumentParams changeParamsToSend = changeParams;
			changeParams = null;

			changeParamsToSend.getTextDocument().setVersion(++version);
			languageServerWrapper.sendNotification(ls -> ls.getTextDocumentService().didChange(changeParamsToSend));
		}
	}

	@Override
	public void documentAboutToBeChanged(DocumentEvent event) {
		checkEvent(event);
		if (syncKind == TextDocumentSyncKind.Incremental) {
			// this really needs to happen before event gets actually
			// applied, to properly compute positions
			createChangeEvent(event);
		}
	}

	/**
	 * Convert Eclipse {@link DocumentEvent} to LS according
	 * {@link TextDocumentSyncKind}. {@link TextDocumentContentChangeEventImpl}.
	 *
	 * @param event
	 *            Eclipse {@link DocumentEvent}
	 * @return true if change event is ready to be sent
	 */
	private boolean createChangeEvent(DocumentEvent event) {
		Assert.isTrue(changeParams == null);
		final var changeParams = this.changeParams = new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(),
				List.of(new TextDocumentContentChangeEvent()));
		changeParams.getTextDocument().setUri(fileUri.toASCIIString());

		IDocument document = event.getDocument();
		TextDocumentContentChangeEvent changeEvent = null;
		TextDocumentSyncKind syncKind = getTextDocumentSyncKind();
		switch (syncKind) {
		case None:
			return false;
		case Full:
			changeParams.getContentChanges().get(0).setText(event.getDocument().get());
			break;
		case Incremental:
			changeEvent = changeParams.getContentChanges().get(0);
			String newText = event.getText();
			int offset = event.getOffset();
			int length = event.getLength();
			try {
				// try to convert the Eclipse start/end offset to LS range.
				final var range = new Range(LSPEclipseUtils.toPosition(offset, document),
						LSPEclipseUtils.toPosition(offset + length, document));
				changeEvent.setRange(range);
				changeEvent.setText(newText);
				changeEvent.setRangeLength(length);
			} catch (BadLocationException e) {
				// error while conversion (should never occur)
				// set the full document text as changes.
				changeEvent.setText(document.get());
			}
			break;
		}
		return true;
	}

	private boolean serverSupportsWillSaveWaitUntil() {
		ServerCapabilities serverCapabilities = languageServerWrapper.getServerCapabilities();
		if(serverCapabilities != null ) {
			Either<TextDocumentSyncKind, TextDocumentSyncOptions> textDocumentSync = serverCapabilities.getTextDocumentSync();
			if(textDocumentSync.isRight()) {
				return Boolean.TRUE.equals(textDocumentSync.getRight().getWillSaveWaitUntil());
			}
		}
		return false;
	}

	private static final String WILL_SAVE_WAIT_UNTIL_TIMEOUT__KEY = "timeout.willSaveWaitUntil"; //$NON-NLS-1$

	private static final int WILL_SAVE_WAIT_UNTIL_COUNT_THRESHOLD = 3;
	private static final Map<String, Integer> WILL_SAVE_WAIT_UNTIL_TIMEOUT_MAP = new ConcurrentHashMap<>();

	/**
	 * Converts a language server ID to the preference ID to define a timeout
	 * for willSaveWaitUntil
	 *
	 * @return language server's preference ID to define a timeout for willSaveWaitUntil
	 */
	private static String lsToWillSaveWaitUntilTimeoutKey(String serverId) {
		return serverId + '.' + WILL_SAVE_WAIT_UNTIL_TIMEOUT__KEY;
	}

	private int lsToWillSaveWaitUntilTimeout() {
		int defaultWillSaveWaitUntilTimeoutInSeconds = 5;
		int willSaveWaitUntilTimeout = store.getInt(lsToWillSaveWaitUntilTimeoutKey(languageServerWrapper.serverDefinition.id));
		return willSaveWaitUntilTimeout != 0 ? willSaveWaitUntilTimeout : defaultWillSaveWaitUntilTimeoutInSeconds;
	}

	public void documentAboutToBeSaved() {
		if (!serverSupportsWillSaveWaitUntil()) {
			// format document if service has been provided:
			formatDocument();
			return;
		}

		final var identifier = LSPEclipseUtils.toTextDocumentIdentifier(fileUri);
		if (WILL_SAVE_WAIT_UNTIL_TIMEOUT_MAP.getOrDefault(identifier.getUri(), 0) > WILL_SAVE_WAIT_UNTIL_COUNT_THRESHOLD) {
			return;
		}

		// Use @link{TextDocumentSaveReason.Manual} as the platform does not give enough information to be accurate
		final var params = new WillSaveTextDocumentParams(identifier, TextDocumentSaveReason.Manual);
		CompletableFuture<@Nullable List<TextEdit>> edits = languageServerWrapper.executeImpl(ls -> ls.getTextDocumentService().willSaveWaitUntil(params));
		try {
			LSPEclipseUtils.applyEdits(document, edits.get(lsToWillSaveWaitUntilTimeout(), TimeUnit.SECONDS));
		} catch (ExecutionException | BadLocationException e) {
			LanguageServerPlugin.logError(e);
		} catch (TimeoutException e) {
			edits.cancel(true);
			Integer timeoutCount = castNonNull(WILL_SAVE_WAIT_UNTIL_TIMEOUT_MAP.compute(identifier.getUri(),
					(k, v) -> v == null ? 1 : Integer.valueOf(v + 1)));
			String message = timeoutCount > WILL_SAVE_WAIT_UNTIL_COUNT_THRESHOLD ?
					Messages.DocumentContentSynchronizer_TimeoutThresholdMessage:
						Messages.DocumentContentSynchronizer_TimeoutMessage;
			String boundMessage = NLS.bind(message, Integer.toString(lsToWillSaveWaitUntilTimeout()), identifier.getUri());
			ServerMessageHandler.showMessage(Messages.DocumentContentSynchronizer_OnSaveActionTimeout, new MessageParams(MessageType.Error, boundMessage));
		} catch (InterruptedException e) {
			LanguageServerPlugin.logError(e);
			Thread.currentThread().interrupt();
		}
	}

	private void formatDocument() {
		var regions = getFormatRegions();
		if (regions != null) {
			try {
				var textSelection = new MultiTextSelection(document, regions);
				var edits = requestFormatting(document, textSelection).get(lsToWillSaveWaitUntilTimeout(), TimeUnit.SECONDS);
				if (edits != null) {
					try {
						edits.apply();
					} catch (final ConcurrentModificationException ex) {
						ServerMessageHandler.showMessage(Messages.LSPFormatHandler_DiscardedFormat, new MessageParams(MessageType.Error, Messages.LSPFormatHandler_DiscardedFormatResponse));
					}
				}
			} catch (InterruptedException e) {
				LanguageServerPlugin.logError(e);
				Thread.currentThread().interrupt();
			} catch (Exception e) {
				LanguageServerPlugin.logError(e);
			}
		}
	}

	private synchronized IRegion @Nullable [] getFormatRegions() {
		if (formatRegionsProvider != null) {
			return formatRegionsProvider.getFormattingRegions(document);
		}
		var serverId = "(serverDefinitionId=" + languageServerWrapper.serverDefinition.id + ")";  //$NON-NLS-1$ //$NON-NLS-2$
		final var bundle = FrameworkUtil.getBundle(this.getClass());
		if (bundle != null) {
			var bundleContext = bundle.getBundleContext();
			if (bundleContext != null) {
				try {
					ServiceReference<?> reference = null;
					var serviceReferences = bundleContext.getAllServiceReferences(IFormatRegionsProvider.class.getName(), serverId);
					if (serviceReferences != null) {
						reference = serviceReferences[0];
					} else {
						//Use LSP4E default implementation:
						reference = bundleContext.getServiceReference(IFormatRegionsProvider.class.getName());
					}
					if (reference != null) {
						formatRegionsProvider = (IFormatRegionsProvider) bundleContext.getService(reference);
						if (formatRegionsProvider != null) {
							return formatRegionsProvider.getFormattingRegions(document);
						}
					}
				} catch (InvalidSyntaxException e) {
					LanguageServerPlugin.logError(e);
				}
			}
		}
		return null;
	}

	private CompletableFuture<@Nullable VersionedEdits> requestFormatting(IDocument document,
			ITextSelection textSelection) {
		long modificationStamp = DocumentUtil.getDocumentModificationStamp(document);

		return languageServerWrapper.getServerCapabilitiesAsync().thenCompose(capabilities -> {
			FormattingOptions formatOptions = LSPFormatter.getFormatOptions();
			final var docId = new TextDocumentIdentifier(fileUri.toString());
			if (textSelection.getLength() == 0 && LSPFormatter.isDocumentRangeFormattingSupported(capabilities)
					&& !(LSPFormatter.isDocumentFormattingSupported(capabilities))) {
				try {
					DocumentRangeFormattingParams rangeParams = LSPFormatter.getRangeFormattingParams(document,
							textSelection, formatOptions, docId);
					return languageServerWrapper
							.executeImpl(ls -> ls.getTextDocumentService().rangeFormatting(rangeParams)
									.thenApply(edits -> new VersionedEdits(modificationStamp, edits, document)));
				} catch (BadLocationException ex) {
					throw new RuntimeException(ex);
				}
			}
			var params = LSPFormatter.getFullFormatParams(formatOptions, docId);
			return languageServerWrapper.executeImpl(ls -> ls.getTextDocumentService().formatting(params)
					.thenApply(edits -> new VersionedEdits(modificationStamp, edits, document)));
		});
	}

	public void documentSaved(IFileBuffer buffer) {
		if (openSaveStamp >= buffer.getModificationStamp()) {
			return;
		}
		this.openSaveStamp = buffer.getModificationStamp();
		ServerCapabilities serverCapabilities = languageServerWrapper.getServerCapabilities();
		if (serverCapabilities != null) {
			Either<TextDocumentSyncKind, TextDocumentSyncOptions> textDocumentSync = serverCapabilities
					.getTextDocumentSync();
			if (textDocumentSync.isRight() && textDocumentSync.getRight().getSave() == null) {
				return;
			}
		}
		final var identifier = LSPEclipseUtils.toTextDocumentIdentifier(fileUri);
		final var params = new DidSaveTextDocumentParams(identifier, document.get());

		languageServerWrapper.sendNotification(ls -> ls.getTextDocumentService().didSave(params));

	}

	public CompletableFuture<@Nullable Void> documentClosed() {
	   final var identifier = LSPEclipseUtils.toTextDocumentIdentifier(fileUri);
		WILL_SAVE_WAIT_UNTIL_TIMEOUT_MAP.remove(identifier.getUri());
		// When LS is shut down all documents are being disconnected. No need to send
		// "didClose" message to the LS that is being shut down or not yet started
		if (languageServerWrapper.isActive()) {
			final var params = new DidCloseTextDocumentParams(identifier);
			languageServerWrapper.sendNotification(ls -> ls.getTextDocumentService().didClose(params));
		}
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * Returns the text document sync kind capabilities of the server and
	 * {@link TextDocumentSyncKind#Full} otherwise.
	 *
	 * @return the text document sync kind capabilities of the server and
	 *         {@link TextDocumentSyncKind#Full} otherwise.
	 */
	private TextDocumentSyncKind getTextDocumentSyncKind() {
		return syncKind;
	}

	public IDocument getDocument() {
		return this.document;
	}

	int getVersion() {
		return version;
	}

	private void checkEvent(DocumentEvent event) {
		if (this.document != event.getDocument()) {
			throw new IllegalStateException(
					"Synchronizer should apply to only a single document, which is the one it was instantiated for"); //$NON-NLS-1$
		}
	}
}
