/*******************************************************************************
 * Copyright (c) 2022-2024 Cocotec Ltd and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Ahmed Hussain (Cocotec Ltd) - initial implementation
 *
 *******************************************************************************/
package org.eclipse.lsp4e.internal;

import java.util.List;

import org.eclipse.lsp4j.CodeActionCapabilities;
import org.eclipse.lsp4j.CodeActionKind;
import org.eclipse.lsp4j.CodeActionKindCapabilities;
import org.eclipse.lsp4j.CodeActionLiteralSupportCapabilities;
import org.eclipse.lsp4j.CodeActionResolveSupportCapabilities;
import org.eclipse.lsp4j.CodeLensCapabilities;
import org.eclipse.lsp4j.CodeLensWorkspaceCapabilities;
import org.eclipse.lsp4j.ColorProviderCapabilities;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionItemInsertTextModeSupportCapabilities;
import org.eclipse.lsp4j.CompletionItemResolveSupportCapabilities;
import org.eclipse.lsp4j.CompletionListCapabilities;
import org.eclipse.lsp4j.DefinitionCapabilities;
import org.eclipse.lsp4j.DocumentHighlightCapabilities;
import org.eclipse.lsp4j.DocumentLinkCapabilities;
import org.eclipse.lsp4j.DocumentSymbolCapabilities;
import org.eclipse.lsp4j.ExecuteCommandCapabilities;
import org.eclipse.lsp4j.FailureHandlingKind;
import org.eclipse.lsp4j.FoldingRangeCapabilities;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeKindSupportCapabilities;
import org.eclipse.lsp4j.FoldingRangeSupportCapabilities;
import org.eclipse.lsp4j.FormattingCapabilities;
import org.eclipse.lsp4j.HoverCapabilities;
import org.eclipse.lsp4j.InlayHintCapabilities;
import org.eclipse.lsp4j.InsertTextMode;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.PublishDiagnosticsCapabilities;
import org.eclipse.lsp4j.RangeFormattingCapabilities;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.ResourceOperationKind;
import org.eclipse.lsp4j.SelectionRangeCapabilities;
import org.eclipse.lsp4j.ShowDocumentCapabilities;
import org.eclipse.lsp4j.SignatureHelpCapabilities;
import org.eclipse.lsp4j.SymbolCapabilities;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolKindCapabilities;
import org.eclipse.lsp4j.SynchronizationCapabilities;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TypeDefinitionCapabilities;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WindowShowMessageRequestCapabilities;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEditCapabilities;
import org.eclipse.lsp4j.WorkspaceEditChangeAnnotationSupportCapabilities;

public class SupportedFeatures {

	public static TextDocumentClientCapabilities getTextDocumentClientCapabilities() {
		final var textDocumentClientCapabilities = new TextDocumentClientCapabilities();
		final var codeAction = new CodeActionCapabilities(
				new CodeActionLiteralSupportCapabilities(new CodeActionKindCapabilities(List.of( //
						CodeActionKind.QuickFix, //
						CodeActionKind.Refactor, //
						CodeActionKind.RefactorExtract, //
						CodeActionKind.RefactorInline, //
						CodeActionKind.RefactorRewrite, //
						CodeActionKind.Source, //
						CodeActionKind.SourceOrganizeImports))),
				true);
		codeAction.setDataSupport(true);
		codeAction.setResolveSupport(new CodeActionResolveSupportCapabilities(List.of("edit"))); //$NON-NLS-1$
		textDocumentClientCapabilities.setCodeAction(codeAction);
		textDocumentClientCapabilities.setCodeLens(new CodeLensCapabilities());
		textDocumentClientCapabilities.setInlayHint(new InlayHintCapabilities());
		textDocumentClientCapabilities.setColorProvider(new ColorProviderCapabilities());
		textDocumentClientCapabilities.setPublishDiagnostics(new PublishDiagnosticsCapabilities());
		final var completionItemCapabilities = new CompletionItemCapabilities(true);
		completionItemCapabilities.setDocumentationFormat(List.of( //
				MarkupKind.MARKDOWN, //
				MarkupKind.PLAINTEXT));
		completionItemCapabilities.setInsertTextModeSupport(new CompletionItemInsertTextModeSupportCapabilities(List.of( //
				InsertTextMode.AsIs, //
				InsertTextMode.AdjustIndentation)));
		completionItemCapabilities.setResolveSupport(new CompletionItemResolveSupportCapabilities(List.of( //
				"documentation", //$NON-NLS-1$
				"detail", //$NON-NLS-1$
				"additionalTextEdits"))); //$NON-NLS-1$
		final var completionCapabilities = new CompletionCapabilities(completionItemCapabilities);
		completionCapabilities.setContextSupport(true);
		completionCapabilities.setCompletionList(new CompletionListCapabilities(List.of( //
				"commitCharacters", //$NON-NLS-1$
				"editRange", //$NON-NLS-1$
				"insertTextFormat", //$NON-NLS-1$
				"insertTextMode"))); //$NON-NLS-1$
		textDocumentClientCapabilities.setCompletion(completionCapabilities);
		final var definitionCapabilities = new DefinitionCapabilities();
		definitionCapabilities.setLinkSupport(true);
		textDocumentClientCapabilities.setDefinition(definitionCapabilities);
		final var typeDefinitionCapabilities = new TypeDefinitionCapabilities();
		typeDefinitionCapabilities.setLinkSupport(true);
		textDocumentClientCapabilities.setTypeDefinition(typeDefinitionCapabilities);
		textDocumentClientCapabilities.setDocumentHighlight(new DocumentHighlightCapabilities());
		textDocumentClientCapabilities.setDocumentLink(new DocumentLinkCapabilities());
		final var documentSymbol = new DocumentSymbolCapabilities();
		documentSymbol.setHierarchicalDocumentSymbolSupport(true);
		documentSymbol.setSymbolKind(new SymbolKindCapabilities(List.of(SymbolKind.values())));
		textDocumentClientCapabilities.setDocumentSymbol(documentSymbol);
		final var foldingRangeCapabilities = new FoldingRangeCapabilities();
		foldingRangeCapabilities.setLineFoldingOnly(true);
		foldingRangeCapabilities.setFoldingRange(new FoldingRangeSupportCapabilities(false));
		foldingRangeCapabilities.setFoldingRangeKind(new FoldingRangeKindSupportCapabilities(List.of( //
				FoldingRangeKind.Comment,
				FoldingRangeKind.Imports,
				FoldingRangeKind.Region
				)));
		textDocumentClientCapabilities.setFoldingRange(foldingRangeCapabilities);
		textDocumentClientCapabilities.setFormatting(new FormattingCapabilities(true));
		final var hoverCapabilities = new HoverCapabilities();
		hoverCapabilities.setContentFormat(List.of( //
				MarkupKind.MARKDOWN, //
				MarkupKind.PLAINTEXT));
		textDocumentClientCapabilities.setHover(hoverCapabilities);
		textDocumentClientCapabilities.setOnTypeFormatting(null); // TODO
		textDocumentClientCapabilities.setRangeFormatting(new RangeFormattingCapabilities());
		textDocumentClientCapabilities.setReferences(new ReferencesCapabilities());
		final var renameCapabilities = new RenameCapabilities();
		renameCapabilities.setPrepareSupport(true);
		textDocumentClientCapabilities.setRename(renameCapabilities);
		textDocumentClientCapabilities.setSignatureHelp(new SignatureHelpCapabilities());
		textDocumentClientCapabilities.setSynchronization(new SynchronizationCapabilities(true, true, true));
		final var selectionRange = new SelectionRangeCapabilities();
		textDocumentClientCapabilities.setSelectionRange(selectionRange);
		return textDocumentClientCapabilities;
	}

	public static WorkspaceClientCapabilities getWorkspaceClientCapabilities() {
		final var workspaceClientCapabilities = new WorkspaceClientCapabilities();
		workspaceClientCapabilities.setApplyEdit(true);
		workspaceClientCapabilities.setConfiguration(true);
		workspaceClientCapabilities.setExecuteCommand(new ExecuteCommandCapabilities(true));
		workspaceClientCapabilities.setSymbol(new SymbolCapabilities(true));
		workspaceClientCapabilities.setWorkspaceFolders(true);
		final var editCapabilities = new WorkspaceEditCapabilities();
		editCapabilities.setDocumentChanges(true);
		editCapabilities.setResourceOperations(List.of( //
				ResourceOperationKind.Create, //
				ResourceOperationKind.Delete, //
				ResourceOperationKind.Rename));
		editCapabilities.setFailureHandling(FailureHandlingKind.Undo);
		editCapabilities.setChangeAnnotationSupport(new WorkspaceEditChangeAnnotationSupportCapabilities(true));
		workspaceClientCapabilities.setWorkspaceEdit(editCapabilities);
		final var codeLensWorkspaceCapabilities = new CodeLensWorkspaceCapabilities(true);
		workspaceClientCapabilities.setCodeLens(codeLensWorkspaceCapabilities);
		return workspaceClientCapabilities;
	}

	public static WindowClientCapabilities getWindowClientCapabilities() {
		final var windowClientCapabilities = new WindowClientCapabilities();
		windowClientCapabilities.setShowDocument(new ShowDocumentCapabilities(true));
		windowClientCapabilities.setWorkDoneProgress(true);
		windowClientCapabilities.setShowMessage(new WindowShowMessageRequestCapabilities());
		return windowClientCapabilities;
	}

	private SupportedFeatures() {
	}
}
