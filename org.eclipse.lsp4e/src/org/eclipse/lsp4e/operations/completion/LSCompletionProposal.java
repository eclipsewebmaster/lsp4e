/*******************************************************************************
 * Copyright (c) 2016, 2024 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Lucas Bullen (Red Hat Inc.) - initial implementation
 *   Michał Niewrzał (Rogue Wave Software Inc.)
 *   Lucas Bullen (Red Hat Inc.) - Refactored for incomplete completion lists
 *                               - [Bug 517428] Requests sent before initialization
 *   Max Bureck (Fraunhofer FOKUS) - [Bug 536089] Execute the CompletionItem.command given after applying the completion
 *                                 - [Bug 558928] Variables replacement fix for Windows
 *******************************************************************************/
package org.eclipse.lsp4e.operations.completion;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.internal.text.html.BrowserInformationControl;
import org.eclipse.jface.text.AbstractReusableInformationControlCreator;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.contentassist.BoldStylerProvider;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension2;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension3;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension4;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension5;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension6;
import org.eclipse.jface.text.contentassist.ICompletionProposalExtension7;
import org.eclipse.jface.text.contentassist.IContextInformation;
import org.eclipse.jface.text.link.LinkedModeModel;
import org.eclipse.jface.text.link.LinkedModeUI;
import org.eclipse.jface.text.link.LinkedPosition;
import org.eclipse.jface.text.link.LinkedPositionGroup;
import org.eclipse.jface.text.link.ProposalPosition;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.jface.viewers.StyledString.Styler;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4e.command.CommandExecutor;
import org.eclipse.lsp4e.internal.StyleUtil;
import org.eclipse.lsp4e.operations.hover.FocusableBrowserInformationControl;
import org.eclipse.lsp4e.ui.LSPImages;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemDefaults;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ExecuteCommandParams;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.InsertTextMode;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.TextStyle;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.texteditor.link.EditorLinkedModeUI;

@SuppressWarnings("restriction")
public class LSCompletionProposal
		implements ICompletionProposal, ICompletionProposalExtension, ICompletionProposalExtension2, ICompletionProposalExtension3, ICompletionProposalExtension4,
		ICompletionProposalExtension5, ICompletionProposalExtension6, ICompletionProposalExtension7,
		IContextInformation {

	private static final int RESOLVE_TIMEOUT = 500;
	// Those variables should be defined in LSP4J and reused here whenever done there
	// See https://github.com/eclipse/lsp4j/issues/149
	/** The currently selected text or the empty string */
	private static final String TM_SELECTED_TEXT = "TM_SELECTED_TEXT"; //$NON-NLS-1$
	/** The contents of the current line */
	private static final String TM_CURRENT_LINE = "TM_CURRENT_LINE"; //$NON-NLS-1$
	/** The contents of the word under cursor or the empty string */
	private static final String TM_CURRENT_WORD = "TM_CURRENT_WORD"; //$NON-NLS-1$
	/** The zero-index based line number */
	private static final String TM_LINE_INDEX = "TM_LINE_INDEX"; //$NON-NLS-1$
	/** The one-index based line number */
	private static final String TM_LINE_NUMBER = "TM_LINE_NUMBER"; //$NON-NLS-1$
	/** The filename of the current document */
	private static final String TM_FILENAME = "TM_FILENAME"; //$NON-NLS-1$
	/** The filename of the current document without its extensions */
	private static final String TM_FILENAME_BASE = "TM_FILENAME_BASE"; //$NON-NLS-1$
	/** The directory of the current document */
	private static final String TM_DIRECTORY = "TM_DIRECTORY"; //$NON-NLS-1$
	/** The full file path of the current document */
	private static final String TM_FILEPATH = "TM_FILEPATH"; //$NON-NLS-1$\

	protected final CompletionItem item;
	private final int initialOffset;
	protected int bestOffset = -1;
	protected int currentOffset = -1;
	protected @Nullable ITextViewer viewer;
	private final IDocument document;
	private final boolean isIncomplete;
	private @Nullable IRegion selection;
	private @Nullable LinkedPosition firstPosition;
	// private LSPDocumentInfo info;
	private @Nullable Integer rankCategory;
	private @Nullable Integer rankScore;
	private @Nullable String documentFilter;
	private String documentFilterAddition = ""; //$NON-NLS-1$
	private final LanguageServerWrapper languageServerWrapper;

	public LSCompletionProposal(IDocument document, int offset, CompletionItem item,
			LanguageServerWrapper languageServerWrapper) {
		this(document, offset, item, null, languageServerWrapper, false);
	}

	public LSCompletionProposal(IDocument document, int offset, CompletionItem item,
			@Nullable CompletionItemDefaults defaults, LanguageServerWrapper languageServerWrapper, boolean isIncomplete) {
		this.item = item;
		this.document = document;
		this.languageServerWrapper = languageServerWrapper;
		this.initialOffset = offset;
		this.currentOffset = offset;
		this.bestOffset = getPrefixCompletionStart(document, offset);
		this.isIncomplete = isIncomplete;
		if (defaults != null) {
			if (item.getInsertTextFormat() == null) {
				item.setInsertTextFormat(defaults.getInsertTextFormat());
			}
			if (item.getCommitCharacters() == null) {
				item.setCommitCharacters(defaults.getCommitCharacters());
			}
			if (item.getInsertTextMode() == null) {
				item.setInsertTextMode(defaults.getInsertTextMode());
			}
			String textEditText = item.getTextEditText();
			if (textEditText != null && defaults.getEditRange() != null) {
				item.setTextEdit(defaults.getEditRange().map(
					range -> Either.forLeft(new TextEdit(range, textEditText)),
					insertReplaceRange -> Either.forRight(new InsertReplaceEdit(textEditText, insertReplaceRange.getInsert(), insertReplaceRange.getReplace()))));
			}
		}
	}

	/**
	 * See {@link CompletionProposalTools#getFilterFromDocument} for filter
	 * generation logic
	 *
	 * @return The document filter for the given offset
	 */
	public String getDocumentFilter(int offset) throws BadLocationException {
		if (documentFilter != null) {
			if (offset != currentOffset) {
				currentOffset = offset;
				rankScore = null;
				rankCategory = null;
				documentFilterAddition = offset > initialOffset ? document.get(initialOffset, offset - initialOffset) : ""; //$NON-NLS-1$
			}
			return documentFilter + documentFilterAddition;
		}
		currentOffset = offset;
		return getDocumentFilter();
	}

	/**
	 * See {@link CompletionProposalTools#getFilterFromDocument} for filter
	 * generation logic
	 *
	 * @return The document filter for the last given offset
	 */
	public String getDocumentFilter() throws BadLocationException {
		if (documentFilter != null) {
			return documentFilter + documentFilterAddition;
		}
		final var documentFilter = this.documentFilter = CompletionProposalTools.getFilterFromDocument(document,
				currentOffset, getFilterString(), bestOffset);
		documentFilterAddition = ""; //$NON-NLS-1$
		return documentFilter;
	}

	/**
	 * See {@link CompletionProposalTools#getScoreOfFilterMatch} for ranking logic
	 *
	 * @return The rank of the match between the document's filter and this
	 *         completion's filter.
	 */
	public int getRankScore() {
		if (rankScore != null)
			return rankScore;
		int rankScore;
		try {
			rankScore = CompletionProposalTools.getScoreOfFilterMatch(getDocumentFilter(),
					getFilterString());
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			rankScore = -1;
		}
		this.rankScore = rankScore;
		return rankScore;
	}

	/**
	 * See {@link CompletionProposalTools#getCategoryOfFilterMatch} for category
	 * logic
	 *
	 * @return The category of the match between the document's filter and this
	 *         completion's filter.
	 */
	public int getRankCategory() {
		if (rankCategory != null) {
			return rankCategory;
		}
		int rankCategory;
		try {
			rankCategory = CompletionProposalTools.getCategoryOfFilterMatch(getDocumentFilter(),
					getFilterString());
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			rankCategory = 5;
		}
		this.rankCategory = rankCategory;
		return rankCategory;
	}

	public int getBestOffset() {
		return bestOffset;
	}

	public void updateOffset(int offset) {
		bestOffset = getPrefixCompletionStart(document, offset);
	}

	public CompletionItem getItem() {
		return item;
	}

	private boolean isDeprecated() {
		return item.getDeprecated() != null && item.getDeprecated().booleanValue();
	}

	@Override
	public StyledString getStyledDisplayString(IDocument document, int offset, BoldStylerProvider boldStylerProvider) {
		String rawString = getDisplayString();
		StyledString res = isDeprecated()
				? new StyledString(rawString, StyleUtil.DEPRECATE)
				: new StyledString(rawString);
		if (offset > bestOffset) {
			try {
				String subString = getDocumentFilter(offset).toLowerCase();
				int lastIndex = 0;
				String lowerRawString = rawString.toLowerCase();
				for (Character c : subString.toCharArray()) {
					int index = lowerRawString.indexOf(c, lastIndex);
					if (index < 0) {
						return res;
					} else {
						res.setStyle(index, 1, new Styler() {

							@Override
							public void applyStyles(TextStyle textStyle) {
								if (isDeprecated()) {
									StyleUtil.DEPRECATE.applyStyles(textStyle);
								}
								boldStylerProvider.getBoldStyler().applyStyles(textStyle);
							}

						});
						lastIndex = index + 1;
					}
				}
			} catch (BadLocationException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		return res;
	}

	@Override
	public String getDisplayString() {
		return item.getLabel();
	}

	@Override
	public StyledString getStyledDisplayString() {
		if (Boolean.TRUE.equals(item.getDeprecated())) {
			return new StyledString(getDisplayString(), StyleUtil.DEPRECATE);
		}
		return new StyledString(getDisplayString());
	}

	@Override
	public boolean isAutoInsertable() {
		// TODO consider what's best
		return false;
	}

	@Override
	public @Nullable IInformationControlCreator getInformationControlCreator() {
		return new AbstractReusableInformationControlCreator() {
			@Override
			protected IInformationControl doCreateInformationControl(Shell parent) {
				if (BrowserInformationControl.isAvailable(parent)) {
					return new FocusableBrowserInformationControl(parent);
				} else {
					return new DefaultInformationControl(parent);
				}
			}
		};
	}

	@Override
	public String getAdditionalProposalInfo(IProgressMonitor monitor) {
		if (languageServerWrapper.isActive() && resolvesCompletionItem(languageServerWrapper.getServerCapabilities())) {
			resolveItem();
		}

		final var res = new StringBuilder();
		if (item.getDetail() != null && !item.getDetail().isEmpty()) {
			res.append("<p>" + item.getDetail() + "</p>"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		if (res.length() > 0) {
			res.append("<br/>"); //$NON-NLS-1$
		}
		if (item.getDocumentation() != null) {
			String htmlDocString = LSPEclipseUtils.getHtmlDocString(item.getDocumentation());
			if (htmlDocString != null) {
				res.append(htmlDocString);
			}
		}

		return res.toString();
	}

	private boolean resolvesCompletionItem(final @Nullable ServerCapabilities capabilities) {
		if (capabilities != null) {
			CompletionOptions completionProvider = capabilities.getCompletionProvider();
			if (completionProvider != null) {
				Boolean hasResolveProvider = completionProvider.getResolveProvider();
				return hasResolveProvider != null && hasResolveProvider;
			}
		}
		return false;
	}

	private void resolveItem() {
		try {
			languageServerWrapper.execute(ls -> ls.getTextDocumentService().resolveCompletionItem(item).thenAccept(this::updateCompletionItem))
					.get(RESOLVE_TIMEOUT, TimeUnit.MILLISECONDS);
		} catch (ExecutionException e) {
			LanguageServerPlugin.logError(e);
		} catch (InterruptedException e) {
			LanguageServerPlugin.logError(e);
			Thread.currentThread().interrupt();
		} catch (TimeoutException e) {
			LanguageServerPlugin.logWarning("Could not resolve completion items due to timeout after " + RESOLVE_TIMEOUT + " milliseconds in `completionItem/resolve`", e);  //$NON-NLS-1$//$NON-NLS-2$
		}
	}

	private void updateCompletionItem(@Nullable CompletionItem resolvedItem) {
		if (resolvedItem == null) {
			return;
		}
		if (resolvedItem.getLabel() != null) {
			item.setLabel(resolvedItem.getLabel());
		}
		if (resolvedItem.getKind() != null) {
			item.setKind(resolvedItem.getKind());
		}
		if (resolvedItem.getDetail() != null) {
			item.setDetail(resolvedItem.getDetail());
		}
		if (resolvedItem.getDocumentation() != null) {
			item.setDocumentation(resolvedItem.getDocumentation());
		}
		if (resolvedItem.getInsertText() != null) {
			item.setInsertText(resolvedItem.getInsertText());
		}
		if (resolvedItem.getInsertTextFormat() != null) {
			item.setInsertTextFormat(resolvedItem.getInsertTextFormat());
		}
		if (resolvedItem.getTextEdit() != null) {
			item.setTextEdit(resolvedItem.getTextEdit());
		}
		if (resolvedItem.getAdditionalTextEdits() != null) {
			item.setAdditionalTextEdits(resolvedItem.getAdditionalTextEdits());
		}
	}

	@Override
	public @Nullable CharSequence getPrefixCompletionText(IDocument document, int completionOffset) {
		return item.getInsertText().substring(completionOffset - bestOffset);
	}

	@Override
	public int getPrefixCompletionStart(IDocument document, int completionOffset) {
		Either<TextEdit, InsertReplaceEdit> textEdit = item.getTextEdit();
		if (textEdit != null) {
			try {
				return LSPEclipseUtils.toOffset(getTextEditRange().getStart(), document);
			} catch (BadLocationException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		final String insertText = getInsertText();
		final int insertTextLength = insertText.length();
		try {
			String subDoc = document.get(
					Math.max(0, completionOffset - insertTextLength),
					Math.min(insertTextLength, completionOffset));
			for (int i = 0; i < insertTextLength && i < completionOffset; i++) {
				String tentativeCommonString = subDoc.substring(i);
				if (insertText.startsWith(tentativeCommonString)) {
					return completionOffset - tentativeCommonString.length();
				}
			}
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
		}
		return completionOffset;
	}

	protected void apply(IDocument document, char trigger, int stateMask, int offset) {
		String insertText = null;
		Either<TextEdit, InsertReplaceEdit> eitherTextEdit = item.getTextEdit();
		TextEdit textEdit = null;
		if (eitherTextEdit != null) {
			if(eitherTextEdit.isLeft()) {
				textEdit = eitherTextEdit.getLeft();
			} else {
				// trick to partially support the new InsertReplaceEdit from LSP 3.16. Reuse previously code for TextEdit.
				InsertReplaceEdit insertReplaceEdit = eitherTextEdit.getRight();
				textEdit = new TextEdit(insertReplaceEdit.getInsert(), insertReplaceEdit.getNewText());
			}
		}
		try {
			if (textEdit == null) {
				insertText = getInsertText();
				Position start = LSPEclipseUtils.toPosition(bestOffset, document);
				Position end = LSPEclipseUtils.toPosition(offset, document); // need 2 distinct objects
				textEdit = new TextEdit(new Range(start, end), insertText);
			} else if (offset > initialOffset) {
				// characters were added after completion was activated
				int shift = offset - initialOffset;
				textEdit.getRange().getEnd().setCharacter(textEdit.getRange().getEnd().getCharacter() + shift);
			}
			{ // workaround https://github.com/Microsoft/vscode/issues/17036
				Position start = textEdit.getRange().getStart();
				Position end = textEdit.getRange().getEnd();
				if (start.getLine() > end.getLine() || (start.getLine() == end.getLine() && start.getCharacter() > end.getCharacter())) {
					textEdit.getRange().setEnd(start);
					textEdit.getRange().setStart(end);
				}
			}
			{ // allow completion items to be wrong with a too wide range
				Position documentEnd = LSPEclipseUtils.toPosition(document.getLength(), document);
				Position textEditEnd = textEdit.getRange().getEnd();
				if (documentEnd.getLine() < textEditEnd.getLine()
					|| (documentEnd.getLine() == textEditEnd.getLine() && documentEnd.getCharacter() < textEditEnd.getCharacter())) {
					textEdit.getRange().setEnd(documentEnd);
				}
			}

			if (insertText != null) {
				// try to reuse existing characters after completion location
				int shift = offset - bestOffset;
				int commonSize = 0;
				final int insertTextLength = insertText.length();
				while (commonSize < insertTextLength - shift
					&& document.getLength() > offset + commonSize
					&& document.getChar(bestOffset + shift + commonSize) == insertText.charAt(commonSize + shift)) {
					commonSize++;
				}
				textEdit.getRange().getEnd().setCharacter(textEdit.getRange().getEnd().getCharacter() + commonSize);
			}
			insertText = textEdit.getNewText();
			Map<String, List<LinkedPosition>> regions = Collections.emptyMap();
			int insertionOffset = LSPEclipseUtils.toOffset(textEdit.getRange().getStart(), document);
			if (item.getInsertTextMode() == InsertTextMode.AdjustIndentation) {
				insertText = adjustIndentation(document, insertText, insertionOffset);
			}
			insertionOffset = computeNewOffset(item.getAdditionalTextEdits(), insertionOffset, document);
			if (item.getInsertTextFormat() == InsertTextFormat.Snippet) {
				final var completionSnippetParser = new CompletionSnippetParser(document, insertText, insertionOffset, this::getVariableValue);
				insertText = completionSnippetParser.parse();
				regions = completionSnippetParser.getLinkedPositions();
				if (!regions.isEmpty() && firstPosition == null) {
					firstPosition = regions.values().iterator().next().get(0);
				}
			}
			textEdit.setNewText(insertText); // insertText now has placeholder removed
			List<TextEdit> additionalEdits = item.getAdditionalTextEdits();
			if (additionalEdits != null && !additionalEdits.isEmpty()) {
				Position initialPosition = LSPEclipseUtils.toPosition(initialOffset, document);

				final var allEdits = new ArrayList<TextEdit>();
				allEdits.add(textEdit);
				additionalEdits.forEach(te -> {
					int shift = offset - initialOffset;
					if (shift != 0) {
						try {
							int start = LSPEclipseUtils.toOffset(te.getRange().getStart(), document);
							int end = LSPEclipseUtils.toOffset(te.getRange().getEnd(), document);
							if (start > initialOffset && te.getRange().getStart().getLine() == initialPosition.getLine()) {
								// We need to shift the Range according to the shift (if on the same line)
								te.getRange().setStart(LSPEclipseUtils.toPosition(start + shift, document));
								te.getRange().setEnd(LSPEclipseUtils.toPosition(end + shift, document));
							}
						} catch (BadLocationException e) {
							LanguageServerPlugin.logError(e);
						}
					}
					allEdits.add(te);
				});
				LSPEclipseUtils.applyEdits(document, allEdits);
			} else {
				LSPEclipseUtils.applyEdit(textEdit, document);
			}

			boolean onlyPlaceCaret = regions.size() == 1 && regions.values().iterator().next().size() == 1 && regions.values().iterator().next().stream().noneMatch(ProposalPosition.class::isInstance);
			final var viewer = this.viewer;
			if (viewer != null && !regions.isEmpty() && !onlyPlaceCaret) {
				final var model = new LinkedModeModel();
				for (List<LinkedPosition> positions: regions.values()) {
					final var group = new LinkedPositionGroup();
					for (LinkedPosition position : positions) {
						group.addPosition(position);
					}
					model.addGroup(group);
				}
				model.forceInstall();

				final var ui = new EditorLinkedModeUI(model, viewer);
				// ui.setSimpleMode(true);
				// ui.setExitPolicy(new ExitPolicy(closingCharacter, document));
				// ui.setExitPosition(getTextViewer(), exit, 0, Integer.MAX_VALUE);
				ui.setCyclingMode(LinkedModeUI.CYCLE_NEVER);
				ui.enter();
			} else if (onlyPlaceCaret) {
				org.eclipse.jface.text.Position region = regions.values().iterator().next().get(0);
				selection = new Region(region.getOffset(), region.getLength());
			} else {
				selection = new Region(insertionOffset + textEdit.getNewText().length(), 0);
			}

			if (item.getCommand() != null) {
				Command command = item.getCommand();
				ServerCapabilities serverCapabilities = languageServerWrapper.getServerCapabilities();
				ExecuteCommandOptions provider = serverCapabilities == null ? null : serverCapabilities.getExecuteCommandProvider();
				if (provider != null && provider.getCommands().contains(command.getCommand())) {
					languageServerWrapper.execute(ls -> ls.getWorkspaceService()
							.executeCommand(new ExecuteCommandParams(command.getCommand(), command.getArguments())));
				} else {
					CommandExecutor.executeCommandClientSide(command, document);
				}
			}
		} catch (BadLocationException ex) {
			LanguageServerPlugin.logError(ex);
		}
	}

	private String adjustIndentation(IDocument document, String insertText, int insertionOffset) throws BadLocationException {
		int line = document.getLineOfOffset(insertionOffset);
		int whitespaceOffset = document.getLineOffset(line);
		final var whitespacesBeforeInsertion = new StringBuilder();
		whitespacesBeforeInsertion.append('\n');
		while (whitespaceOffset < insertionOffset && Character.isWhitespace(document.getChar(whitespaceOffset))) {
			whitespacesBeforeInsertion.append(document.getChar(whitespaceOffset));
			whitespaceOffset++;
		}
		return insertText.replace("\n", whitespacesBeforeInsertion); //$NON-NLS-1$
	}

	private int computeNewOffset(@Nullable List<TextEdit> additionalTextEdits, int insertionOffset, IDocument doc) {
		if (additionalTextEdits != null && !additionalTextEdits.isEmpty()) {
			int adjustment = 0;
			for (TextEdit edit : additionalTextEdits) {
				try {
					Range rng = edit.getRange();
					int start = LSPEclipseUtils.toOffset(rng.getStart(), doc);
					if (start <= insertionOffset) {
						int end = LSPEclipseUtils.toOffset(rng.getEnd(), doc);
						int orgLen = end - start;
						int newLeng = edit.getNewText().length();
						int editChange = newLeng - orgLen;
						adjustment += editChange;
					}
				} catch (BadLocationException e) {
					LanguageServerPlugin.logError(e);
				}
			}
			return insertionOffset + adjustment;
		}
		return insertionOffset;
	}

	private String getVariableValue(String variableName) {
		return switch (variableName) {
		case TM_FILENAME_BASE -> {
			IPath path = LSPEclipseUtils.toPath(document);
			String fileName = path == null ? null : path.removeFileExtension().lastSegment();
			yield fileName != null ? fileName : ""; //$NON-NLS-1$
		}
		case TM_FILENAME -> {
			IPath path = LSPEclipseUtils.toPath(document);
			String fileName = path == null ? null : path.lastSegment();
			yield fileName != null ? fileName : ""; //$NON-NLS-1$
		}
		case TM_FILEPATH -> {
			IPath path = LSPEclipseUtils.toPath(document);
			yield path == null ? "" : getAbsoluteLocation(path); //$NON-NLS-1$
		}
		case TM_DIRECTORY -> {
			IPath path = LSPEclipseUtils.toPath(document);
			yield path == null ? "" : getAbsoluteLocation(path.removeLastSegments(1)); //$NON-NLS-1$
		}
		case TM_LINE_INDEX -> {
			try {
				yield Integer.toString(getTextEditRange().getStart().getLine()); // TODO probably wrong, should use viewer state
			} catch (BadLocationException e) {
				LanguageServerPlugin.logWarning(e.getMessage(), e);
				yield ""; //$NON-NLS-1$
			}
		}
		case TM_LINE_NUMBER -> {
			try {
				yield Integer.toString(getTextEditRange().getStart().getLine() + 1); // TODO probably wrong, should use viewer state
			} catch (BadLocationException e) {
				LanguageServerPlugin.logWarning(e.getMessage(), e);
				yield ""; //$NON-NLS-1$
			}
		}
		case TM_CURRENT_LINE -> {  // TODO probably wrong, should use viewer state
			try {
				int currentLineIndex = getTextEditRange().getStart().getLine();
				IRegion lineInformation = document.getLineInformation(currentLineIndex);
				String line = document.get(lineInformation.getOffset(), lineInformation.getLength());
				yield line;
			} catch (BadLocationException e) {
				LanguageServerPlugin.logWarning(e.getMessage(), e);
				yield ""; //$NON-NLS-1$
			}
		}
		case TM_SELECTED_TEXT -> {
			try {
				final var viewer = castNonNull(this.viewer);
				String selectedText = document.get(viewer.getSelectedRange().x, viewer.getSelectedRange().y);
				yield selectedText;
			} catch (BadLocationException e) {
				LanguageServerPlugin.logWarning(e.getMessage(), e);
				yield ""; //$NON-NLS-1$
			}
		}
		case TM_CURRENT_WORD -> {
			try {
				final var viewer = castNonNull(this.viewer);
				String selectedText = document.get(viewer.getSelectedRange().x, viewer.getSelectedRange().y);
				int beforeSelection = viewer.getSelectedRange().x - 1;
				while (beforeSelection >= 0 && Character.isUnicodeIdentifierPart(document.getChar(beforeSelection))) {
					selectedText = beforeSelection + selectedText;
					beforeSelection--;
				}
				int afterSelection = viewer.getSelectedRange().x + viewer.getSelectedRange().y;
				while (afterSelection < document.getLength() && Character.isUnicodeIdentifierPart(afterSelection)) {
					selectedText = selectedText + document.getChar(afterSelection);
					afterSelection++;
				}
				yield selectedText;
			} catch (BadLocationException e) {
				LanguageServerPlugin.logWarning(e.getMessage(), e);
				yield ""; //$NON-NLS-1$
			}
		}
		default -> variableName;
		};
	}

	private Range getTextEditRange() throws BadLocationException {
		Either<TextEdit, InsertReplaceEdit> textEdit = item.getTextEdit();
		if (textEdit != null) {
			return textEdit.map(TextEdit::getRange, InsertReplaceEdit::getInsert);
		} else {
				Position start = LSPEclipseUtils.toPosition(bestOffset, document);
				Position end = LSPEclipseUtils.toPosition(initialOffset, document);
				return new Range(start, end);
		}
	}

	/**
	 * Returns the absolute OS specific path for the given {@code path}.
	 * @param path to be turned into an OS specific path
	 * @return OS specific absolute path representation of argument {@code path}
	 */
	private String getAbsoluteLocation(IPath path) {
		IResource res = ResourcesPlugin.getWorkspace().getRoot().findMember(path);
		if(res != null) {
			// On projects getRawLocation() returns null if it is located
			// in the default location; getLocation(), however, returns the
			// absolute path in the local file system
			IPath location = res.getType() == IResource.PROJECT ? res.getLocation() : res.getRawLocation();
			if(location != null) {
				return location.toOSString();
			}
		}
		return path.toFile().getAbsolutePath();
	}

	protected String getInsertText() {
		String insertText = item.getInsertText();
		Either<TextEdit, InsertReplaceEdit> eitherTextEdit = item.getTextEdit();
		if (eitherTextEdit != null) {
			insertText = eitherTextEdit.map(TextEdit::getNewText, InsertReplaceEdit::getNewText);
		}
		if (insertText == null) {
			insertText = item.getLabel();
		}
		return insertText;
	}

	@Override
	public @Nullable Point getSelection(IDocument document) {
		final var firstPosition = this.firstPosition;
		if (firstPosition != null) {
			return new Point(firstPosition.getOffset(), firstPosition.getLength());
		}
		final var selection = this.selection;
		if (selection == null) {
			return null;
		}
		return new Point(selection.getOffset(), selection.getLength());
	}

	@Override
	public @Nullable String getAdditionalProposalInfo() {
		return getAdditionalProposalInfo(new NullProgressMonitor());
	}

	@Override
	public @Nullable Image getImage() {
		return LSPImages.imageFromCompletionItem(item);
	}

	@Override
	public @Nullable IContextInformation getContextInformation() {
		return this;
	}

	@Override
	public String getContextDisplayString() {
		return Objects.toString(getAdditionalProposalInfo());
	}

	@Override
	public String getInformationDisplayString() {
		return Objects.toString(getAdditionalProposalInfo());
	}

	public String getSortText() {
		if (item.getSortText() != null && !item.getSortText().isEmpty()) {
			return item.getSortText();
		}
		return item.getLabel();
	}

	public String getFilterString() {
		if (item.getFilterText() != null && !item.getFilterText().isEmpty()) {
			return item.getFilterText();
		}
		return item.getLabel();
	}

	@Override
	public boolean isValidFor(IDocument document, int offset) {
		return (!isIncomplete || offset == initialOffset) && validate(document, offset, null);
	}

	@Override
	public void selected(ITextViewer viewer, boolean smartToggle) {
		this.viewer = viewer;
	}

	@Override
	public void unselected(ITextViewer viewer) {
	}

	@Override
	public boolean validate(IDocument document, int offset, @Nullable DocumentEvent event) {
		if (item.getLabel() == null || item.getLabel().isEmpty()) {
			return false;
		}
		if (offset < bestOffset) {
			return false;
		}
		try {
			String documentFilter = getDocumentFilter(offset);
			if (!documentFilter.isEmpty()) {
				return !(isIncomplete && currentOffset != initialOffset) && CompletionProposalTools.isSubstringFoundOrderedInString(documentFilter, getFilterString());
			} else if (item.getTextEdit() != null) {
				return offset == LSPEclipseUtils.toOffset(getTextEditRange().getStart(), document);
			}
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
		}
		return true;
	}

	@Override
	public void apply(ITextViewer viewer, char trigger, int stateMask, int offset) {
		this.viewer = viewer;
		final var doc = viewer.getDocument();
		if (doc != null) {
			apply(doc, trigger, stateMask, offset);
		}
	}

	@Override
	public void apply(IDocument document, char trigger, int offset) {
		apply(document, trigger, 0, offset);
	}

	@Override
	public void apply(IDocument document) {
		apply(document, Character.MIN_VALUE, 0, bestOffset);
	}

	@Override
	public char @Nullable [] getTriggerCharacters() {
		return null;
	}

	@Override
	public int getContextInformationPosition() {
		return SWT.RIGHT;
	}
}
