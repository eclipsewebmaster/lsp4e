/*******************************************************************************
 * Copyright (c) 2016, 2024 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Michał Niewrzał (Rogue Wave Software Inc.)
 *  Lucas Bullen (Red Hat Inc.) - Get IDocument from IEditorInput
 *  Angelo Zerr <angelo.zerr@gmail.com> - Bug 525400 - [rename] improve rename support with ltk UI
 *  Remy Suen <remy.suen@gmail.com> - Bug 520052 - Rename assumes that workspace edits are in reverse order
 *  Martin Lippert (Pivotal Inc.) - bug 531452, bug 532305
 *  Alex Boyko (Pivotal Inc.) - bug 543435 (WorkspaceEdit apply handling)
 *  Markus Ofterdinger (SAP SE) - Bug 552140 - NullPointerException in LSP4E
 *  Rubén Porras Campo (Avaloq) - Bug 576425 - Support Remote Files
 *  Pierre-Yves Bigourdan <pyvesdev@gmail.com> - Issue 29
 *  Bastian Doetsch (Snyk Ltd)
 *******************************************************************************/
package org.eclipse.lsp4e;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.filesystem.IFileSystem;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceStatus;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.content.IContentTypeManager;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.RewriteSessionEditProcessor;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.lsp4e.internal.ArrayUtil;
import org.eclipse.lsp4e.internal.DocumentInputStream;
import org.eclipse.lsp4e.refactoring.CreateFileChange;
import org.eclipse.lsp4e.refactoring.DeleteExternalFile;
import org.eclipse.lsp4e.refactoring.LSPTextChange;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4e.ui.UI;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.Color;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.CreateFile;
import org.eclipse.lsp4j.DeclarationParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DeleteFile;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.ImplementationParams;
import org.eclipse.lsp4j.LinkedEditingRangeParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameFile;
import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.TypeDefinitionParams;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.DocumentChange;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.DeleteResourceChange;
import org.eclipse.ltk.core.refactoring.resource.MoveRenameResourceChange;
import org.eclipse.ltk.core.refactoring.resource.RenameResourceChange;
import org.eclipse.ltk.ui.refactoring.RefactoringWizard;
import org.eclipse.ltk.ui.refactoring.RefactoringWizardOpenOperation;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.RGBA;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.undo.DocumentUndoManagerRegistry;
import org.eclipse.text.undo.IDocumentUndoManager;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.IPathEditorInput;
import org.eclipse.ui.IURIEditorInput;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.intro.config.IIntroURL;
import org.eclipse.ui.intro.config.IntroURLFactory;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.common.primitives.Chars;

/**
 * Some utility methods to convert between Eclipse and LS-API types
 */
public final class LSPEclipseUtils {

	private static final String DEFAULT_LABEL = "LSP Workspace Edit"; //$NON-NLS-1$
	private static final String HTTP = "http"; //$NON-NLS-1$
	public static final String INTRO_URL = "http://org.eclipse.ui.intro"; //$NON-NLS-1$
	public static final String FILE_URI = "file://"; //$NON-NLS-1$

	private static final String FILE_SCHEME = "file"; //$NON-NLS-1$
	private static final String FILE_SLASH = "file:/"; //$NON-NLS-1$
	private static final String HTML = "html"; //$NON-NLS-1$
	private static final String MARKDOWN = "markdown"; //$NON-NLS-1$
	private static final String MD = "md"; //$NON-NLS-1$
	private static final int MAX_BROWSER_NAME_LENGTH = 30;

	private LSPEclipseUtils() {
		// this class shouldn't be instantiated
	}

	public static Position toPosition(int offset, IDocument document) throws BadLocationException {
		final var res = new Position();
		res.setLine(document.getLineOfOffset(offset));
		res.setCharacter(offset - document.getLineInformationOfOffset(offset).getOffset());
		return res;
	}

	public static int toOffset(Position position, IDocument document) throws BadLocationException {
		var line = position.getLine();
		final int character;

		/*
		 * The LSP spec allow for positions to specify the next line if a line should be
		 * included completely, specifying the first character of the following line. If
		 * this is at the end of the document, we therefore take the last document line
		 * and set the character to line length - 1 (to remove delimiter)
		 */
		var zeroBasedDocumentLines = Math.max(0, document.getNumberOfLines() - 1);
		if (zeroBasedDocumentLines < position.getLine()) {
			line = zeroBasedDocumentLines;
			character = getLineLength(document, line);
		} else {
			// We just take the line length to be more forgiving and adhere to the LSP spec.
			character = Math.min(getLineLength(document, line), position.getCharacter());
		}

		return document.getLineOffset(line) + character;
	}

	private static int getLineLength(IDocument document, int line) throws BadLocationException {
		return Math.max(0, document.getLineLength(line));
	}

	public static boolean isOffsetInRange(int offset, Range range, IDocument document) {
		try {
			return offset != -1 && offset >= toOffset(range.getStart(), document)
					&& offset <= toOffset(range.getEnd(), document);
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return false;
		}
	}

	public static CompletionParams toCompletionParams(URI fileUri, int offset, IDocument document, char[] completionTriggerChars)
			throws BadLocationException {
		Position start = toPosition(offset, document);
		final var param = new CompletionParams();
		if (document.getLength() > 0) {
			try {
				int positionCharacterOffset = offset > 0 ? offset-1 : offset;
				String positionCharacter = document.get(positionCharacterOffset, 1);
				if (Chars.contains(completionTriggerChars, positionCharacter.charAt(0))) {
					param.setContext(new CompletionContext(CompletionTriggerKind.TriggerCharacter, positionCharacter));
				} else {
					// According to LSP 3.17 specification: the triggerCharacter in CompletionContext is undefined if
					// triggerKind != CompletionTriggerKind.TriggerCharacter
					param.setContext(new CompletionContext(CompletionTriggerKind.Invoked));
				}
			} catch (BadLocationException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		param.setPosition(start);
		param.setTextDocument(toTextDocumentIdentifier(fileUri));
		return param;
	}

	public static @Nullable ISelection toSelection(Range range, IDocument document) {
		try {
			int offset = toOffset(range.getStart(), document);
			int endOffset = toOffset(range.getEnd(), document);
			return new TextSelection(offset, endOffset > offset ? endOffset - offset : 0);
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return null;
		}
	}

	/**
	 * @param fileUri
	 * @param offset
	 * @param document
	 * @throws BadLocationException
	 * @deprecated Use {@link #toTextDocumentPosistionParams(int, IDocument)}
	 *             instead
	 */
	@Deprecated
	public static TextDocumentPositionParams toTextDocumentPosistionParams(URI fileUri, int offset, IDocument document)
			throws BadLocationException {
		Position start = toPosition(offset, document);
		final var param = new TextDocumentPositionParams();
		param.setPosition(start);
		param.setTextDocument(toTextDocumentIdentifier(fileUri));
		return param;
	}

	private static <T extends TextDocumentPositionParams> T toTextDocumentPositionParamsCommon(@NonNull T param,  int offset, IDocument document)
			throws BadLocationException {
		URI uri = toUri(document);
		Position start = toPosition(offset, document);
		param.setPosition(start);
		final var id = new TextDocumentIdentifier();
		if (uri != null) {
			id.setUri(uri.toASCIIString());
		}
		param.setTextDocument(id);
		return param;
	}

	public static HoverParams toHoverParams(int offset, IDocument document) throws BadLocationException {
		return toTextDocumentPositionParamsCommon(new HoverParams(), offset, document);
	}

	public static SignatureHelpParams toSignatureHelpParams(int offset, IDocument document)
			throws BadLocationException {
		return toTextDocumentPositionParamsCommon(new SignatureHelpParams(), offset, document);
	}

	public static TextDocumentPositionParams toTextDocumentPosistionParams(int offset, IDocument document)
			throws BadLocationException {
		return toTextDocumentPositionParamsCommon(new TextDocumentPositionParams(), offset, document);
	}

	public static DefinitionParams toDefinitionParams(TextDocumentPositionParams params) {
		return toTextDocumentPositionParamsCommon(new DefinitionParams(), params);
	}

	public static DeclarationParams toDeclarationParams(TextDocumentPositionParams params) {
		return toTextDocumentPositionParamsCommon(new DeclarationParams(), params);
	}

	public static TypeDefinitionParams toTypeDefinitionParams(TextDocumentPositionParams params) {
		return toTextDocumentPositionParamsCommon(new TypeDefinitionParams(), params);
	}

	public static ImplementationParams toImplementationParams(TextDocumentPositionParams params) {
		return toTextDocumentPositionParamsCommon(new ImplementationParams(), params);
	}

	public static LinkedEditingRangeParams toLinkedEditingRangeParams(TextDocumentPositionParams params) {
		return toTextDocumentPositionParamsCommon(new LinkedEditingRangeParams(), params);
	}

	/**
	 * Convert generic TextDocumentPositionParams to type specific version. Should
	 * only be used for T where T adds no new fields.
	 */
	private static <T extends TextDocumentPositionParams> T toTextDocumentPositionParamsCommon(
			T specificParams, TextDocumentPositionParams genericParams) {
		if (genericParams.getPosition() != null) {
			specificParams.setPosition(genericParams.getPosition());
		}
		if (genericParams.getTextDocument() != null) {
			specificParams.setTextDocument(genericParams.getTextDocument());
		}
		return specificParams;
	}

	public static @Nullable TextDocumentIdentifier toTextDocumentIdentifier(final IDocument document) {
		final var uri = toUri(document);
		return uri == null ? null : toTextDocumentIdentifier(uri);
	}

	public static @Nullable TextDocumentIdentifier toTextDocumentIdentifier(final IResource res) {
		final var uri = toUri(res);
		return uri == null ? null : toTextDocumentIdentifier(uri);
	}

	public static TextDocumentIdentifier toTextDocumentIdentifier(final URI uri) {
		return new TextDocumentIdentifier(uri.toASCIIString());
	}

	public static CallHierarchyPrepareParams toCallHierarchyPrepareParams(int offset, final IDocument document) throws BadLocationException {
		Position position = LSPEclipseUtils.toPosition(offset, document);
		TextDocumentIdentifier documentIdentifier = castNonNull(LSPEclipseUtils.toTextDocumentIdentifier(document));
		return new CallHierarchyPrepareParams(documentIdentifier, position);
	}

	public static @Nullable ITextFileBuffer toBuffer(@Nullable IDocument document) {
		if(document == null) {
			return null;
		}
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		if (bufferManager == null)
			return null;
		return bufferManager.getTextFileBuffer(document);
	}

	public static @Nullable URI toUri(@Nullable IDocument document) {
		if(document == null) {
			return null;
		}
		ITextFileBuffer buffer = toBuffer(document);
		IPath path = toPath(buffer);
		IFile file = getFile(path);
		if (file != null) {
			return toUri(file);
		} else if(path != null) {
			return toUri(path.toFile());
		} else if (buffer != null && buffer.getFileStore() != null) {
			return buffer.getFileStore().toURI();
		}
		return null;
	}

	private static @Nullable IPath toPath(@Nullable IFileBuffer buffer) {
		if (buffer != null) {
			return buffer.getLocation();
		}
		return null;
	}

	public static @Nullable IPath toPath(@Nullable IDocument document) {
		return toPath(toBuffer(document));
	}

	public static int toEclipseMarkerSeverity(@Nullable DiagnosticSeverity lspSeverity) {
		if (lspSeverity == null) {
			// if severity is empty it is up to the client to interpret diagnostics
			return IMarker.SEVERITY_ERROR;
		}
		return switch (lspSeverity) {
		case Error -> IMarker.SEVERITY_ERROR;
		case Warning -> IMarker.SEVERITY_WARNING;
		default -> IMarker.SEVERITY_INFO;
		};
	}

	@Nullable
	public static IFile getFileHandle(@Nullable URI uri) {
		if (uri == null) {
			return null;
		}
		if (FILE_SCHEME.equals(uri.getScheme())) {
			IWorkspaceRoot wsRoot = ResourcesPlugin.getWorkspace().getRoot();
			return ArrayUtil.findFirst(wsRoot.findFilesForLocationURI(uri));
		} else {
			return Adapters.adapt(uri.toString(), IFile.class, true);
		}
	}

	@Nullable
	public static IFile getFileHandle(@Nullable String uri) {
		if (uri == null || uri.isEmpty()) {
			return null;
		}
		if (uri.startsWith(FILE_SLASH)) {
			URI uriObj = URI.create(uri);
			return getFileHandle(uriObj);
		} else {
			return Adapters.adapt(uri, IFile.class, true);
		}
	}

	@Nullable
	public static IResource findResourceFor(@Nullable String uri) {
		if (uri == null || uri.isEmpty()) {
			return null;
		}
		if (uri.startsWith(FILE_SLASH)) {
			return findResourceFor(URI.create(uri));
		} else {
			return Adapters.adapt(uri, IResource.class, true);
		}
	}

	@Nullable
	public static IResource findResourceFor(@Nullable URI uri) {
		if (uri == null) {
			return null;
		}
		if (FILE_SCHEME.equals(uri.getScheme())) {
			IWorkspaceRoot wsRoot = ResourcesPlugin.getWorkspace().getRoot();

			IFile[] files = wsRoot.findFilesForLocationURI(uri);
			if (files.length > 0) {
				IFile file = findMostNested(files);
				if(file!=null) {
					return file;
				}
			}

			return ArrayUtil.findFirst(wsRoot.findContainersForLocationURI(uri));
		} else {
			return Adapters.adapt(uri, IResource.class, true);
		}
	}

	public static @Nullable IFile findMostNested(IFile[] files) {
		int shortestLen = Integer.MAX_VALUE;
		IFile shortest = null;
		for (IFile file : files) {
			/*
			 * IWorkspaceRoot#findFilesForLocationURI returns IFile objects for folders instead of null.
			 * IWorkspaceRoot#findContainersForLocationURI returns IFolder objects for regular files instead of null.
			 * Thus we have to manually check the file system entry to determine the correct type to return.
			 */
			if(!file.isVirtual() && !file.getLocation().toFile().isDirectory()) {
				IPath path = file.getFullPath();
				if (path.segmentCount() < shortestLen) {
					shortest = file;
					shortestLen = path.segmentCount();
				}
			}
		}
		return shortest;
	}

	public static void applyEdit(TextEdit textEdit, IDocument document) throws BadLocationException {
		document.replace(
				toOffset(textEdit.getRange().getStart(), document),
				toOffset(textEdit.getRange().getEnd(), document) - toOffset(textEdit.getRange().getStart(), document),
				textEdit.getNewText());
	}

	/**
	 * Method will apply all edits to document as single modification. Needs to
	 * be executed in UI thread.
	 *
	 * @param document
	 *            document to modify
	 * @param edits
	 *            list of LSP TextEdits
	 * @throws BadLocationException
	 */
	public static void applyEdits(@Nullable IDocument document, @Nullable List<? extends TextEdit> edits) throws BadLocationException {
		if (document == null || edits == null || edits.isEmpty()) {
			return;
		}

		final var edit = new MultiTextEdit();
		for (final TextEdit textEdit : edits) {
			int offset = toOffset(textEdit.getRange().getStart(), document);
			int length = toOffset(textEdit.getRange().getEnd(), document) - offset;
			if (length < 0) {
				// Must be a bad location: we bail out to avoid corrupting the document.
				throw new BadLocationException("Invalid location information found applying edits"); //$NON-NLS-1$
			}
			// check if that edit would actually change the document
			var newText = textEdit.getNewText();
			if (!document.get(offset, length).equals(newText)) {
				if (newText.length() > 0) {
					var zeroBasedDocumentLines = Math.max(0, document.getNumberOfLines() - 1);
					var endLine = textEdit.getRange().getEnd().getLine();
					endLine = endLine > zeroBasedDocumentLines ? zeroBasedDocumentLines : endLine;
					// Do not split "\r\n" line ending:
					if ("\r\n".equals(document.getLineDelimiter(endLine))) { //$NON-NLS-1$;
						// if last char in the newText is a carriage return:
						if ('\r' == newText.charAt(newText.length()-1) && offset + length < document.getLength()) {
							// replace the whole line:
							newText = newText + '\n';
							length++;
						}
					}
				}
				edit.addChild(new ReplaceEdit(offset, length, newText));
			}
		}

		if(!edit.hasChildren())
			return;

		IDocumentUndoManager manager = DocumentUndoManagerRegistry.getDocumentUndoManager(document);
		if (manager != null) {
			manager.beginCompoundChange();
		}
		try {
			final var editProcessor = new RewriteSessionEditProcessor(document, edit,
					org.eclipse.text.edits.TextEdit.NONE);
			editProcessor.performEdits();
		} catch (MalformedTreeException | BadLocationException e) {
			LanguageServerPlugin.logError(e);
		}
		if (manager != null) {
			manager.endCompoundChange();
		}
	}

	@Nullable
	public static IDocument getDocument(@Nullable IResource resource) {
		if (resource == null) {
			return null;
		}

		IDocument document = getExistingDocument(resource);

		if (document == null && resource.getType() == IResource.FILE) {
			ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
			if (bufferManager == null)
				return document;
			try {
				bufferManager.connect(resource.getFullPath(), LocationKind.IFILE, new NullProgressMonitor());
			} catch (CoreException e) {
				LanguageServerPlugin.logError(e);
				return document;
			}

			ITextFileBuffer buffer = bufferManager.getTextFileBuffer(resource.getFullPath(), LocationKind.IFILE);
			if (buffer != null) {
				document = buffer.getDocument();
			}
		}

		return document;
	}

	public static @Nullable IDocument getExistingDocument(@Nullable IResource resource) {
		if (resource == null) {
			return null;
		}
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		if (bufferManager == null)
			return null;
		ITextFileBuffer buffer = bufferManager.getTextFileBuffer(resource.getFullPath(), LocationKind.IFILE);
		if (buffer != null) {
			return buffer.getDocument();
		}
		else {
			return null;
		}
	}

	public static @Nullable IDocument getDocument(@Nullable URI uri) {
		if (uri == null) {
			return null;
		}
		IResource resource = findResourceFor(uri);
		if (resource != null) {
			return getDocument(resource);
		}

		IDocument document = null;
		IFileStore store = null;
		try {
			store = EFS.getStore(uri);
		} catch (CoreException e) {
			LanguageServerPlugin.logError(e);
			return null;
		}
		ITextFileBufferManager bufferManager = FileBuffers.getTextFileBufferManager();
		if (bufferManager == null)
			return null;
		ITextFileBuffer buffer = bufferManager.getFileStoreTextFileBuffer(store);
		if (buffer != null) {
			document = buffer.getDocument();
		} else {
			try {
				bufferManager.connectFileStore(store, new NullProgressMonitor());
			} catch (CoreException e) {
				LanguageServerPlugin.logError(e);
				return document;
			}
			buffer = bufferManager.getFileStoreTextFileBuffer(store);
			if (buffer != null) {
				document = buffer.getDocument();
			}
		}
		return document;
	}

	public static void openInEditor(Location location) {
		openInEditor(location, castNonNull(UI.getActivePage()));
	}

	public static void openInEditor(Location location, IWorkbenchPage page) {
		open(location.getUri(), page, location.getRange());
	}

	public static void openInEditor(LocationLink link) {
		openInEditor(link, castNonNull(UI.getActivePage()));
	}

	public static void openInEditor(LocationLink link, IWorkbenchPage page) {
		open(link.getTargetUri(), page, link.getTargetSelectionRange());
	}

	public static void open(String uri, @Nullable Range optionalRange) {
		open(uri, castNonNull(UI.getActivePage()), optionalRange);
	}

	public static void open(String uri, IWorkbenchPage page, @Nullable Range optionalRange) {
		open(uri, page, optionalRange, false);
	}

	public static void open(String uri, @Nullable Range optionalRange, boolean createFile) {
		open(uri, castNonNull(UI.getActivePage()), optionalRange, createFile);
	}

	public static void open(String uri, IWorkbenchPage page, @Nullable Range optionalRange, boolean createFile) {
		if (uri.startsWith(HTTP)) {
			if (uri.startsWith(INTRO_URL)) {
				openIntroURL(uri);
			} else {
				openHttpLocationInBrowser(uri, page);
			}
		} else {
			if (optionalRange == null){
				optionalRange = parseRange(uri);
			}
			openFileLocationInEditor(uri, page, optionalRange, createFile);
		}
	}

	private static final Pattern rangeFromUriExtractionPattern = Pattern
			.compile("^L?(\\d+)(?:,(\\d+))?(-L?(\\d+)(?:,(\\d+))?)?"); //$NON-NLS-1$

	/**
	 * Extracts the range information from a URI e.g.
	 * file://path/to/file#L34,1-L35,3<br/>
	 * file://path/to/file#34,1-35,3<br/>
	 * file://path/to/file#L34,1-35,3<br/>
	 * file://path/to/file#L34<br/>
	 * file://path/to/file#L34,1<br/>
	 *
	 * @param location
	 *            the uri to parse
	 * @return a range object containing the information
	 */
	public static @Nullable Range parseRange(String location) {
		try {
			if (!location.startsWith(LSPEclipseUtils.FILE_URI))
				return null;
			var uri = URI.create(location).normalize();
			var fragment = uri.getFragment();
			if (fragment == null || fragment.isBlank()) return null;
			Matcher matcher = rangeFromUriExtractionPattern.matcher(fragment);
			if (!matcher.matches())
				return null;

			int startLine = matcher.group(1) != null ? Integer.parseInt(matcher.group(1)) : 1;
			int startChar = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 1;
			int endLine = matcher.group(4) != null ? Integer.parseInt(matcher.group(4)) : startLine;
			int endChar = matcher.group(5) != null ? Integer.parseInt(matcher.group(5)) : startChar;

			// Positions are zero-based
			final var start = new Position(startLine - 1, startChar - 1);
			final var end = new Position(endLine - 1, endChar - 1);
			return new Range(start, end);
		} catch (IllegalArgumentException e) {
			// not a valid URI, no range
			return null;
		}
	}

	protected static void openIntroURL(final String uri) {
		IIntroURL introUrl = IntroURLFactory.createIntroURL(uri);
		if (introUrl != null) {
			try {
				if (!introUrl.execute()) {
					LanguageServerPlugin.logWarning("Failed to execute IntroURL: " + uri, null); //$NON-NLS-1$
				}
			} catch (Exception t) {
				LanguageServerPlugin.logWarning("Error executing IntroURL: " + uri, t); //$NON-NLS-1$
			}
		}
	}

	protected static void openHttpLocationInBrowser(final String uri, IWorkbenchPage page) {
		page.getWorkbenchWindow().getShell().getDisplay().asyncExec(() -> {
			try {
				final var url = new URL(uri);

				IWorkbenchBrowserSupport browserSupport = page.getWorkbenchWindow().getWorkbench()
						.getBrowserSupport();

				String browserName = uri;
				if (browserName.length() > MAX_BROWSER_NAME_LENGTH) {
					browserName = uri.substring(0, MAX_BROWSER_NAME_LENGTH - 1) + '\u2026';
				}

				browserSupport
						.createBrowser(IWorkbenchBrowserSupport.AS_EDITOR | IWorkbenchBrowserSupport.LOCATION_BAR
								| IWorkbenchBrowserSupport.NAVIGATION_BAR, "lsp4e-symbols", browserName, uri) //$NON-NLS-1$
						.openURL(url);

			} catch (Exception e) {
				LanguageServerPlugin.logError(e);
			}
		});
	}

	protected static void openFileLocationInEditor(String uri, IWorkbenchPage page, @Nullable Range optionalRange,
			boolean createFile) {
		IEditorPart part = openEditor(uri, page, createFile);

		IDocument targetDocument = null;
		// Update selection (if needed) from the given range
		if (optionalRange != null && part != null && part.getEditorSite() != null
				&& part.getEditorSite().getSelectionProvider() != null) {
			ITextEditor textEditor = Adapters.adapt(part, ITextEditor.class);
			if (textEditor != null) {
				targetDocument = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
			}

			if (targetDocument != null) {
				ISelectionProvider selectionProvider = part.getEditorSite().getSelectionProvider();
				if (selectionProvider != null) {
					ISelection selection = toSelection(optionalRange, targetDocument);
					if (selection != null) {
						selectionProvider.setSelection(selection);
					}
				}
			}
		}
	}

	private static @Nullable IEditorPart openEditor(String uri, @Nullable IWorkbenchPage page, boolean createFile) {
		if (page == null) {
			return null;
		}
		// Open file uri in an editor
		IResource targetResource = findResourceFor(uri);
		if (targetResource != null && targetResource.getType() == IResource.FILE) {
			if (!targetResource.exists() && createFile) {
				// The file to open is not found, open a confirm dialog to ask if the file must
				// be created.
				if (MessageDialog.openQuestion(UI.getActiveShell(), Messages.CreateFile_confirm_title,
						Messages.bind(Messages.CreateFile_confirm_message, uri))) {
					try (final ByteArrayInputStream input = new ByteArrayInputStream("".getBytes())) //$NON-NLS-1$
					{
						((IFile) targetResource).create(input, IResource.KEEP_HISTORY, null);
					} catch (Exception e) {
						LanguageServerPlugin.logError(e);
					}
				} else {
					return null;
				}
			}
			try {
				return IDE.openEditor(page, (IFile) targetResource);
			} catch (PartInitException e) {
				LanguageServerPlugin.logError(e);
			}
		} else {
			URI fileUri = URI.create(uri).normalize();
			try {
				IFileSystem fileSystem = EFS.getFileSystem(fileUri.getScheme());
				IFileStore fileStore = fileSystem.getStore(fileUri);
				IFileInfo fetchInfo = fileStore.fetchInfo();
				if (!fetchInfo.isDirectory()) {
					if (!fetchInfo.exists() && createFile) {
						// The file to open is not found, open a confirm dialog to ask if the file must
						// be created.
						if (MessageDialog.openQuestion(UI.getActiveShell(), Messages.CreateFile_confirm_title,
								Messages.bind(Messages.CreateFile_confirm_message, uri))) {
							try {
								fileStore.getParent().mkdir(EFS.NONE, null);
								try (final OutputStream out = fileStore.openOutputStream(EFS.NONE, null)) {
									out.write("".getBytes()); //$NON-NLS-1$
								}
							} catch (Exception e) {
								LanguageServerPlugin.logError(e);
							}
						} else {
							return null;
						}
					}
					return IDE.openEditorOnFileStore(page, fileStore);
				}
			} catch (CoreException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		return null;
	}

	public static @Nullable IDocument getDocument(@Nullable ITextEditor editor) {
		if (editor == null)
			return null;
		final IEditorInput editorInput = editor.getEditorInput();
		if (editorInput != null) {
			final IDocumentProvider documentProvider = editor.getDocumentProvider();
			if (documentProvider != null) {
				final IDocument document = documentProvider.getDocument(editorInput);
				if (document != null)
					return document;
			}
			IDocument res = getDocument(editorInput);
			if (res != null) {
				return res;
			}
		}
		if (editor instanceof AbstractTextEditor) {
			try {
				Method getSourceViewerMethod= AbstractTextEditor.class.getDeclaredMethod("getSourceViewer"); //$NON-NLS-1$
				getSourceViewerMethod.setAccessible(true);
				final var viewer = (ITextViewer) getSourceViewerMethod.invoke(editor);
				return (viewer == null) ? null : viewer.getDocument();
			} catch (Exception ex) {
				LanguageServerPlugin.logError(ex);
			}
		}
		return null;
	}

	public static @Nullable IDocument getDocument(IEditorInput editorInput) {
		if (!editorInput.exists()) {
			// Shouldn't happen too often, but happens rather a lot in testing when
			// teardown runs when there are document setup actions still pending
			return null;
		}
		if(editorInput instanceof IFileEditorInput fileEditorInput) {
			return getDocument(fileEditorInput.getFile());
		}else if(editorInput instanceof IPathEditorInput pathEditorInput) {
			return getDocument(getFile(pathEditorInput.getPath()));
		}else if(editorInput instanceof IURIEditorInput uriEditorInput) {
			IResource resource = findResourceFor(uriEditorInput.getURI());
			if (resource != null) {
				return getDocument(resource);
			} else {
				return getDocument(uriEditorInput.getURI());
			}
		}
		return null;
	}

	/**
	 * Applies a workspace edit. It does simply change the underlying documents.
	 *
	 * @param wsEdit
	 */
	public static void applyWorkspaceEdit(@Nullable WorkspaceEdit wsEdit) {
		applyWorkspaceEdit(wsEdit, null);
	}

	/**
	 * Applies a workspace edit. It does simply change the underlying documents if all are currently
	 * open in an editor, otherwise, it performs a refactoring that will result on filesystem changes.
	 *
	 * @param wsEdit
	 * @param label
	 */
	public static void applyWorkspaceEdit(@Nullable WorkspaceEdit wsEdit, @Nullable String label) {
		if (wsEdit == null) {
			return;
		}

		String name = label == null ? DEFAULT_LABEL : label;

		if (wsEdit.getChangeAnnotations() != null && wsEdit.getChangeAnnotations().values().stream().anyMatch(ca -> ca.getNeedsConfirmation() != null && ca.getNeedsConfirmation())) {
			runRefactorWizardOperation(toCompositeChange(wsEdit, name));
		} else {

			if (applyWorkspaceEditIfSingleOpenFile(wsEdit)) {
				return;
			}

			// multiple documents or some ResourceChanges => create a refactoring
			final var changedURIs = new HashMap<URI, Range>();
			CompositeChange change = toCompositeChange(wsEdit, name, changedURIs);

			final var changeOperation = new PerformChangeOperation(change);
			changeOperation.setUndoManager(RefactoringCore.getUndoManager(), name);
			try {
				ResourcesPlugin.getWorkspace().run(changeOperation, new NullProgressMonitor());

				// Open the resource in editor if there is the only one URI
				if (changedURIs.size() == 1) {
					changedURIs.entrySet().stream().findFirst().ifPresent(e -> {
						// Select the only start position of the range or the document start
						final var uri = e.getKey();
						final var range = e.getValue();
						Position start = range.getStart() != null ? range.getStart() : new Position(0, 0);
						UI.runOnUIThread(() -> open(uri.toString(), new Range(start, start)));
					});
				}
			} catch (CoreException e) {
				LanguageServerPlugin.logError(e);
			}
		}
	}

	private static void runRefactorWizardOperation(Change change) {
		final var refactoring = new Refactoring() {

			@Override
			public String getName() {
				return change.getName();
			}

			@Override
			public RefactoringStatus checkInitialConditions(IProgressMonitor pm)
					throws CoreException, OperationCanceledException {
				return RefactoringStatus.create(Status.OK_STATUS);
			}

			@Override
			public RefactoringStatus checkFinalConditions(IProgressMonitor pm)
					throws CoreException, OperationCanceledException {
				return RefactoringStatus.create(Status.OK_STATUS);
			}

			@Override
			public Change createChange(IProgressMonitor pm) throws CoreException, OperationCanceledException {
				return change;
			}

		};
		final var wizard = new RefactoringWizard(refactoring,
				RefactoringWizard.DIALOG_BASED_USER_INTERFACE |
				RefactoringWizard.NO_BACK_BUTTON_ON_STATUS_DIALOG
		) {
			@Override
			protected void addUserInputPages() {
				//no inputs required
			}
		};
		UI.runOnUIThread(() -> {
			try {
				new RefactoringWizardOpenOperation(wizard).run(UI.getActiveShell(), change.getName());
			} catch (InterruptedException e) {
				LanguageServerPlugin.logError(e);
			}
		});
	}

	/**
	 * Applies the workspace edit on an open editor if the given edit affects only 1 open file
	 * @param wsEdit a workspace edit
	 * @return <code>true<code> if the wsEdit matches a single open file and was performed on editor,
	 *         <code>false</code> otherwise, thus the wsEdit needs to be performed differently.
	 */
	private static boolean applyWorkspaceEditIfSingleOpenFile(WorkspaceEdit wsEdit) {
		final var documentUris = new HashSet<URI>();
		final var firstDocumentEdits = new ArrayList<TextEdit>(); // collect edits
		if (wsEdit.getChanges() != null && !wsEdit.getChanges().isEmpty()) {
			wsEdit.getChanges().entrySet().stream()
				.map(Entry::getKey)
				.map(LSPEclipseUtils::toUri)
				.forEach(documentUris::add);
			firstDocumentEdits.addAll(wsEdit.getChanges().entrySet().iterator().next().getValue());
		}
		if (wsEdit.getDocumentChanges() != null && !wsEdit.getDocumentChanges().isEmpty()) {
			if (wsEdit.getDocumentChanges().stream().anyMatch(Either::isRight)) {
				documentUris.clear(); // do not process this as a single doc edit
			} else {
				wsEdit.getDocumentChanges().stream()
					.map(Either::getLeft)
					.map(TextDocumentEdit::getTextDocument)
					.map(TextDocumentIdentifier::getUri)
					.map(LSPEclipseUtils::toUri)
					.forEach(documentUris::add);
				firstDocumentEdits.addAll(wsEdit.getDocumentChanges().get(0).getLeft().getEdits());
			}
		}
		if (documentUris.size() != 1 || firstDocumentEdits.isEmpty()) {
			return false;
		}
		URI singleDocumentUri = documentUris.iterator().next();
		Set<IEditorReference> editors = findOpenEditorsFor(singleDocumentUri);
		if (editors.isEmpty()) {
			return false;
		}
		Optional<IDocument> doc = editors.stream().map(editor -> {
			try {
				return editor.getEditorInput();
			} catch (PartInitException ex) {
				return null;
			}}).filter(Objects::nonNull)
			.map(LSPEclipseUtils::getDocument)
			.filter(Objects::nonNull)
			.findFirst();

		doc.ifPresent(document -> UI.getDisplay().syncExec(() -> {
			try {
				LSPEclipseUtils.applyEdits(document, firstDocumentEdits);
			} catch (BadLocationException ex) {
				LanguageServerPlugin.logError(ex);
			}
		}));
		return doc.isPresent();
	}

	/**
	 * Returns a ltk {@link CompositeChange} from a lsp {@link WorkspaceEdit}.
	 *
	 * @param wsEdit
	 * @param name
	 * @return a ltk {@link CompositeChange} from a lsp {@link WorkspaceEdit}.
	 */
	public static CompositeChange toCompositeChange(WorkspaceEdit wsEdit, String name) {
		return toCompositeChange(wsEdit, name, null);
	}

	/**
	 * Returns a ltk {@link CompositeChange} from a lsp {@link WorkspaceEdit}.
	 *
	 * @param wsEdit
	 * @param name
	 * @param collector A map of URI to Range entries collected from WorkspaceEdit
	 * @return a ltk {@link CompositeChange} from a lsp {@link WorkspaceEdit}.
	 */
	private static CompositeChange toCompositeChange(WorkspaceEdit wsEdit, String name, @Nullable Map<URI, Range> collector) {
		final var change = new CompositeChange(name);
		List<Either<TextDocumentEdit, ResourceOperation>> documentChanges = wsEdit.getDocumentChanges();
		if (documentChanges != null) {
			// documentChanges are present, the latter are preferred over changes
			// see specification at
			// https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#workspaceEdit
			documentChanges.forEach(action -> {
				if (action.isLeft()) {
					TextDocumentEdit edit = action.getLeft();
					VersionedTextDocumentIdentifier id = edit.getTextDocument();
					URI uri = URI.create(id.getUri());
					List<TextEdit> textEdits = edit.getEdits();
					change.add(toChanges(uri, textEdits));
					collectChangedURI(uri, textEdits, collector);
				} else if (action.isRight()) {
					ResourceOperation resourceOperation = action.getRight();
					if (resourceOperation instanceof final CreateFile createOperation) {
						URI targetURI = URI.create(createOperation.getUri());
						File targetFile = fromUri(targetURI);
						if (targetFile.exists() && createOperation.getOptions() != null) {
							if (!createOperation.getOptions().getIgnoreIfExists()) {
								if (createOperation.getOptions().getOverwrite()) {
									change.add(new LSPTextChange("Overwrite", targetURI, "")); //$NON-NLS-1$ //$NON-NLS-2$
								} else {
									// TODO? Log, warn user...?
								}
							}
						} else {
							final var operation = new CreateFileChange(targetURI, "", null); //$NON-NLS-1$
							change.add(operation);
						}
					} else if (resourceOperation instanceof DeleteFile delete) {
						IResource resource = findResourceFor(delete.getUri());
						if (resource != null) {
							final var deleteChange = new DeleteResourceChange(resource.getFullPath(), true);
							change.add(deleteChange);
						} else {
							LanguageServerPlugin.logWarning(
									"Changes outside of visible projects are not supported at the moment.", null); //$NON-NLS-1$
						}
					} else if (resourceOperation instanceof RenameFile rename) {
						URI oldURI = URI.create(rename.getOldUri());
						URI newURI = URI.create(rename.getNewUri());
						IFile oldFile = getFileHandle(oldURI);
						IFile newFile = getFileHandle(newURI);

						// If both files are within Eclipse workspace utilize Eclipse ltk MoveRenameResourceChange and RenameResourceChange
						if (oldFile != null && oldFile.exists() && newFile != null) {
							final var oldFileParent = oldFile.getParent();
							final var newFileParent = newFile.getParent();
							if (oldFileParent != null && newFileParent != null && newFileParent.exists()) {
   							if (!newFile.exists() || rename.getOptions().getOverwrite()) {
   								if (Objects.equals(oldFile.getParent(), newFile.getParent())) {
   									change.add(new RenameResourceChange(oldFile.getFullPath(), newFile.getName()));
   								} else {
   									change.add(new MoveRenameResourceChange(oldFile, newFile.getParent(), newFile.getName()));
   								}
   								return;
   							}
							}
						}

						DeleteResourceChange removeNewFile = null;
						if (newFile != null && newFile.exists()) {
							if (rename.getOptions().getOverwrite()) {
								removeNewFile = new DeleteResourceChange(newFile.getFullPath(), true);
							} else if (rename.getOptions().getIgnoreIfExists()) {
								return;
							}
						}
						String content = ""; //$NON-NLS-1$
						String encoding = null;
						if (oldFile != null && oldFile.exists()) {
							try (var stream = new ByteArrayOutputStream((int) oldFile.getLocation().toFile().length());
									InputStream inputStream = oldFile.getContents();) {
								inputStream.transferTo(stream);
								content = new String(stream.toByteArray());
								encoding = oldFile.getCharset();
							} catch (IOException | CoreException e) {
								LanguageServerPlugin.logError(e);
							}
						}
						final var createFileChange = new CreateFileChange(newURI, content, encoding);
						change.add(createFileChange);
						if (removeNewFile != null) {
							change.add(removeNewFile);
						}
						if (oldFile != null) {
							final var removeOldFile = new DeleteResourceChange(oldFile.getFullPath(), true);
							change.add(removeOldFile);
						} else {
							change.add(new DeleteExternalFile(new File(oldURI)));
						}
					}
				}
			});
		} else {
			Map<String, List<TextEdit>> changes = wsEdit.getChanges();
			if (changes != null) {
				for (java.util.Map.Entry<String, List<TextEdit>> edit : changes.entrySet()) {
					URI uri = URI.create(edit.getKey());
					List<TextEdit> textEdits = edit.getValue();
					change.add(toChanges(uri, textEdits));
					collectChangedURI(uri, textEdits, collector);
				}
			}
		}
		return change;
	}

	private static final Range DEFAULT_RANGE = new Range(new Position(0, 0), new Position(0, 0));

	/**
	 * Reports the URI and the start range of the given text edit, if exists.
	 *
	 * @param textEdits A list of textEdits sorted in reversed order
	 */
	private static void collectChangedURI(URI uri, @Nullable List<TextEdit> textEdits, @Nullable Map<URI, Range> collector) {
		if (collector == null) {
			return;
		}

		Range start = textEdits != null && !textEdits.isEmpty()
				? textEdits.get(textEdits.size() - 1).getRange() : DEFAULT_RANGE;

		Range range  = collector.get(uri);
		if (range == null) {
			collector.put(uri, start);
		} else  if(rangeStartIsLessThan(start, range)) {
			collector.put(uri, range);
		}
	}

	private static boolean rangeStartIsLessThan(@Nullable Range range, @Nullable Range toCompare) {
		if (range == null) {
			return true;
		}
		if (toCompare == null) {
			return false;
		}

		Position start = range.getStart();
		Position compareStart = toCompare.getStart();
		if(start.getLine() < compareStart.getLine()) {
			return true;
		} else if (start.getLine() == compareStart.getLine()) {
			return start.getCharacter() < compareStart.getCharacter();
		}
		return false;
	}


	/**
	 * Transform LSP {@link TextEdit} list into ltk {@link DocumentChange} and add
	 * it in the given ltk {@link CompositeChange}.
	 *
	 * @param uri
	 *            document URI to update
	 * @param textEdits
	 *            CompositeChange with LSP text edits
	 */
	private static Change toChanges(URI uri, List<TextEdit> textEdits) {
		LSPTextChange[] changes = textEdits.stream()
				.sorted(Comparator.comparing((TextEdit edit) -> edit.getRange().getStart(),
						Comparator.comparingInt(Position::getLine).thenComparingInt(Position::getCharacter).reversed()))
				.map(te -> new LSPTextChange("Line: %d".formatted(te.getRange().getStart().getLine() + 1), uri, te)) //$NON-NLS-1$
				.toArray(LSPTextChange[]::new);
		return new CompositeChange(uri.toString(), changes);
	}

	public static URI toUri(IPath absolutePath) {
		return toUri(absolutePath.toFile());
	}

	public static @Nullable URI toUri(IResource resource) {
		URI adaptedURI = Adapters.adapt(resource, URI.class, true);
		if (adaptedURI != null) {
			return adaptedURI;
		}
		IPath location = resource.getLocation();
		if (location != null) {
			return toUri(location);
		}
		return resource.getLocationURI();
	}

	public static @Nullable URI toUri(IFileBuffer buffer) {
		IPath bufferLocation = buffer.getLocation();
		IFile res = bufferLocation != null && bufferLocation.segmentCount() > 1 ? getFile(buffer.getLocation()) : null;
		if (res != null) {
			URI uri = toUri(res);
			if (uri != null) {
				return uri;
			}
		}
		if (buffer.getFileStore() != null) {
			return buffer.getFileStore().toURI();
		}
		return null;
	}

	public static URI toUri(File file) {
		// URI scheme specified by language server protocol and LSP
		try {
			return new URI("file", "", file.getAbsoluteFile().toURI().getPath(), null); //$NON-NLS-1$ //$NON-NLS-2$
		} catch (URISyntaxException e) {
			LanguageServerPlugin.logError(e);
			return file.getAbsoluteFile().toURI();
		}
	}

	public static @Nullable IFile getFile(@Nullable IDocument document) {
		IPath path = toPath(document);
		return getFile(path);
	}

	/**
	 * @return null if no file exists at the given path or the given path points to a file outside the workspace
	 */
	public static @Nullable IFile getFile(@Nullable IPath path) {
		if(path == null) {
			return null;
		}

		final IFile res = path.segmentCount() == 1 //
				? ResourcesPlugin.getWorkspace().getRoot().getFileForLocation(path)
				: ResourcesPlugin.getWorkspace().getRoot().getFile(path);
		if (res != null && res.exists()) {
			return res;
		}
		return null;
	}

	/**
	 * @return a list of folder objects for all open projects of the current workspace
	 */
	public static List<WorkspaceFolder> getWorkspaceFolders() {
		return Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
		.filter(IProject::isAccessible) //
		.map(LSPEclipseUtils::toWorkspaceFolder) //
		.toList();
	}

	public static WorkspaceFolder toWorkspaceFolder(IProject project) {
		final var folder = new WorkspaceFolder();
		URI folderUri = toUri(project);
		folder.setUri(folderUri != null ? folderUri.toASCIIString() : ""); //$NON-NLS-1$
		folder.setName(project.getName());
		return folder;
	}

	public static List<IContentType> getFileContentTypes(IFile file) {
		IContentTypeManager contentTypeManager = Platform.getContentTypeManager();
		final var contentTypes = new ArrayList<IContentType>();
		if (file.exists()) {
			try (InputStream contents = file.getContents()) {
				// TODO consider using document as inputstream
				Collections.addAll(contentTypes, contentTypeManager.findContentTypesFor(contents, file.getName()));
			} catch (CoreException | IOException e) {
				LanguageServerPlugin.logError(e);
			}
		} else {
			Collections.addAll(contentTypes, contentTypeManager.findContentTypesFor(file.getName()));
		}
		return contentTypes;
	}

	@Nullable
	private static String getFileName(@Nullable ITextFileBuffer buffer) {
		IPath path = toPath(buffer);
		IFile file = getFile(path);
		if (file != null) {
			return file.getName();
		}
		if(path != null) {
			return path.lastSegment();
		}
		if (buffer != null && buffer.getFileStore() != null) {
			return buffer.getFileStore().getName();
		}
		return null;
	}

	public static List<IContentType> getDocumentContentTypes(IDocument document) {
		final var contentTypes = new ArrayList<IContentType>();

		ITextFileBuffer buffer = toBuffer(document);
		if (buffer != null) {
			try {
				// may be a more specific content-type, relying on some content-type factory and actual content (not just name)
				IContentType contentType = buffer.getContentType();
				if (contentType != null) {
					contentTypes.add(contentType);
				}
			} catch (CoreException e) {
				if (!(e.getCause() instanceof java.io.FileNotFoundException) && e.getStatus().getCode() != IResourceStatus.RESOURCE_NOT_FOUND) {
					//the content type may be based on path or file name pattern or another subsystem via the ContentTypeManager
					// so that is not an error condition
					//otherwise, account for some other unknown CoreException
					LanguageServerPlugin.logError("Exception occurred while fetching the content type from the buffer", e); //$NON-NLS-1$;
				}
			}
		}

		String fileName = getFileName(buffer);
		if (fileName != null) {
			try (var contents = new DocumentInputStream(document)) {
				contentTypes.addAll(List.of(Platform.getContentTypeManager().findContentTypesFor(contents, fileName)));
			} catch (IOException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		return contentTypes;
	}

	/**
	 * Deprecated because any code that calls this probably needs to be changed
	 * somehow to be properly aware of markdown content. This method simply returns
	 * the doc string as a string, regardless of whether it is markdown or
	 * plaintext.
	 *
	 * @deprecated
	 */
	@Deprecated
	public static @Nullable String getDocString(@Nullable Either<String, MarkupContent> documentation) {
		if (documentation != null) {
			if (documentation.isLeft()) {
				return documentation.getLeft();
			} else {
				return documentation.getRight().getValue();
			}
		}
		return null;
	}

	public static @Nullable String getHtmlDocString(Either<@Nullable String, MarkupContent> documentation) {
		return documentation.map(text -> {
			if (text != null && !text.isEmpty()) {
				return htmlParagraph(text);
			}
			return null;
		}, markupContent -> {
			String text = markupContent.getValue();
			if (text != null && !text.isEmpty()) {
				String kind = markupContent.getKind();
				if (MARKDOWN.equalsIgnoreCase(kind) || MD.equalsIgnoreCase(kind)) {
					try {
						Parser parser = Parser.builder().build();
						Node document = parser.parse(text);
						HtmlRenderer renderer = HtmlRenderer.builder().build();
						return renderer.render(document);
					} catch (Exception e) {
						LanguageServerPlugin.logError(e);
						return htmlParagraph(text);
					}
				} else if (HTML.equalsIgnoreCase(kind)) {
					return text;
				} else {
					return htmlParagraph(text);
				}
			}
			return null;
		});
	}

	public static @Nullable ITextViewer getTextViewer(@Nullable final IEditorPart editorPart) {
		final @Nullable ITextViewer textViewer = Adapters.adapt(editorPart, ITextViewer.class);
		if (textViewer != null) {
			return textViewer;
		}

		if (Adapters.adapt(editorPart, ITextOperationTarget.class) instanceof ITextViewer viewer) {
			return viewer;
		}
		return null;
	}

	private static String htmlParagraph(String text) {
		final var sb = new StringBuilder();
		sb.append("<p>"); //$NON-NLS-1$
		sb.append(text);
		sb.append("</p>"); //$NON-NLS-1$
		return sb.toString();
	}

	/**
	 * Convert the given Eclipse <code>rgb</code> instance to a LSP {@link Color}
	 * instance.
	 *
	 * @param rgb
	 *            the rgb instance to convert
	 * @return the given Eclipse <code>rgb</code> instance to a LSP {@link Color}
	 *         instance.
	 */
	public static Color toColor(RGB rgb) {
		return new Color(rgb.red / 255d, rgb.green / 255d, rgb.blue / 255d, 1);
	}

	/**
	 * Convert the given LSP <code>color</code> instance to a Eclipse {@link RGBA}
	 * instance.
	 *
	 * @param color
	 *            the color instance to convert
	 * @return the given LSP <code>color</code> instance to a Eclipse {@link RGBA}
	 *         instance.
	 */
	public static RGBA toRGBA(Color color) {
		return new RGBA((int) (color.getRed() * 255), (int) (color.getGreen() * 255), (int) (color.getBlue() * 255),
				(int) color.getAlpha());
	}

	public static Set<IEditorReference> findOpenEditorsFor(@Nullable URI uri) {
		if (uri == null) {
			return Collections.emptySet();
		}
		return Arrays.stream(PlatformUI.getWorkbench().getWorkbenchWindows())
			.map(IWorkbenchWindow::getPages)
			.flatMap(Arrays::stream)
			.map(IWorkbenchPage::getEditorReferences)
			.flatMap(Arrays::stream)
			.filter(ref -> {
				try {
					return uri.equals(toUri(ref.getEditorInput()));
				} catch (PartInitException e) {
					LanguageServerPlugin.logError(e);
					return false;
				}
			})
			.collect(Collectors.toSet());
	}

	public static @Nullable URI toUri(IEditorInput editorInput) {
		if (editorInput instanceof FileEditorInput fileEditorInput) {
			return toUri(fileEditorInput.getFile());
		}
		if (editorInput instanceof IURIEditorInput uriEditorInput) {
			URI uri = uriEditorInput.getURI();
			return uri.getPath() != null ? toUri(Path.fromPortableString(uri.getPath())) : uri;
		}
		return null;
	}

	public static URI toUri(String uri) {
		URI initialUri = URI.create(uri);
		return FILE_SCHEME.equals(initialUri.getScheme()) ? toUri(Path.fromPortableString(initialUri.getPath())) : initialUri;
	}

	/**
	 * Use nio Paths to convert a file URI to a File in order to avoid problems with UNC paths on Windows.
	 * Java has historically generated 'unhealthy' UNC URIs so \\myserver\a\b becomes file:////myserver/a/b
	 * The favoured representation is to encode the server as the URI authority as file://myserver/a/b
	 * Java (and LSP4e) has kept the older representation using File.toURI() and new File(URI uri) for
	 * backward compatibility, but supported the newer representation in nio.Path
	 * Trying to construct a File directly with a new-style UNC URI causes it to throw with an 'authority is not null'
	 * complaint. Going via nio.Path allows us to accept either syntax. LSP4e does not use URIs directly e.g. as
	 * keys in lookup dictionaries so we don't have to worry about canonicalisation problems
	 *
	 * See https://bugs.openjdk.org/browse/JDK-4723726
	 * https://docs.microsoft.com/en-us/archive/blogs/ie/file-uris-in-windows
	 *
	 * @param uri A file URI, possibly for a UNC path in the newer syntax with the server encoded in the authority
	 * @return A file
	 */
	public static File fromUri(URI uri) {
		return Paths.get(uri).toFile();
	}

	public static boolean hasCapability(final @Nullable Either<Boolean, ?> eitherCapability) {
		if(eitherCapability == null) {
			return false;
		}
		return eitherCapability.isRight() || eitherCapability.getLeft();
	}

	public static boolean isReadOnly(final URI uri) {
		IResource resource = findResourceFor(uri);
		return resource != null && isReadOnly(resource);
	}

	public static boolean isReadOnly(final IDocument document) {
		IFile file = getFile(document);
		return file != null && isReadOnly(file);
	}

	public static boolean isReadOnly(final IResource resource) {
		ResourceAttributes attributes = resource.getResourceAttributes();
		return attributes != null && attributes.isReadOnly();
	}
}
