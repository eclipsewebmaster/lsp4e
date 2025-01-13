/*******************************************************************************
 * Copyright (c) 2024 Advantest GmbH and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Dietrich Travkin (Solunar GmbH) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.operations.symbols;

import java.util.Collections;
import java.util.List;

import org.eclipse.lsp4e.outline.SymbolsModel.DocumentSymbolWithURI;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;
import org.eclipse.lsp4j.WorkspaceSymbol;

public class SymbolsUtil {

	public static SymbolKind getKind(SymbolInformation symbolInformation) {
		return symbolInformation.getKind();
	}

	public static SymbolKind getKind(WorkspaceSymbol workspaceSymbol) {
		return workspaceSymbol.getKind();
	}

	public static SymbolKind getKind(DocumentSymbol documentSymbol) {
		return documentSymbol.getKind();
	}

	public static SymbolKind getKind(DocumentSymbolWithURI documentSymbolWithUri) {
		return getKind(documentSymbolWithUri.symbol);
	}

	public static List<SymbolTag> getSymbolTags(SymbolInformation symbolInformation) {
		if (symbolInformation.getTags() != null) {
			return symbolInformation.getTags();
		}

		return Collections.emptyList();
	}

	public static List<SymbolTag> getSymbolTags(WorkspaceSymbol workspaceSymbol) {
		if (workspaceSymbol.getTags() != null) {
			return workspaceSymbol.getTags();
		}

		return Collections.emptyList();
	}

	public static List<SymbolTag> getSymbolTags(DocumentSymbol documentSymbol) {
		if (documentSymbol.getTags() != null) {
			return documentSymbol.getTags();
		}

		return Collections.emptyList();
	}

	public static List<SymbolTag> getSymbolTags(DocumentSymbolWithURI documentSymbolWithUri) {
		return getSymbolTags(documentSymbolWithUri.symbol);
	}

	public static boolean hasSymbolTag(List<SymbolTag> tagList, SymbolTag tag) {
		return tagList.contains(tag);
	}

	public static boolean hasSymbolTag(SymbolInformation symbolInformation, SymbolTag tag) {
		return getSymbolTags(symbolInformation).contains(tag);
	}

	public static boolean hasSymbolTag(WorkspaceSymbol workspaceSymbol, SymbolTag tag) {
		return getSymbolTags(workspaceSymbol).contains(tag);
	}

	public static boolean hasSymbolTag(DocumentSymbol documentSymbol, SymbolTag tag) {
		return getSymbolTags(documentSymbol).contains(tag);
	}

	public static boolean hasSymbolTag(DocumentSymbolWithURI documentSymbolWithUri, SymbolTag tag) {
		return getSymbolTags(documentSymbolWithUri).contains(tag);
	}

	public static boolean isDeprecated(SymbolInformation symbolInformation) {
		boolean deprecated = isDeprecated(getSymbolTags(symbolInformation));
		return deprecated || (symbolInformation.getDeprecated() == null ? false: symbolInformation.getDeprecated());
	}

	public static boolean isDeprecated(WorkspaceSymbol workspaceSymbol) {
		return isDeprecated(getSymbolTags(workspaceSymbol));
	}

	public static boolean isDeprecated(DocumentSymbol documentSymbol) {
		boolean deprecated = isDeprecated(getSymbolTags(documentSymbol));
		return deprecated || (documentSymbol.getDeprecated() == null ? false: documentSymbol.getDeprecated());
	}

	public static boolean isDeprecated(DocumentSymbolWithURI documentSymbolWithUri) {
		return isDeprecated(documentSymbolWithUri.symbol);
	}

	public static boolean isDeprecated(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Deprecated);
	}

	public static boolean isPrivate(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Private);
	}

	public static boolean isPackage(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Package);
	}

	public static boolean isProtected(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Protected);
	}

	public static boolean isPublic(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Public);
	}

	public static boolean isInternal(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Internal);
	}

	public static boolean isFileVisible(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.File);
	}

	public static boolean isStatic(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Static);
	}

	public static boolean isAbstract(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Abstract);
	}

	public static boolean isFinal(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Final);
	}

	public static boolean isSealed(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Sealed);
	}

	public static boolean isTransient(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Transient);
	}

	public static boolean isVolatile(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Volatile);
	}

	public static boolean isSynchronized(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Synchronized);
	}

	public static boolean isVirtual(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Virtual);
	}

	public static boolean isNullable(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Nullable);
	}

	public static boolean isNonNull(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.NonNull);
	}

	public static boolean isDeclaration(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Declaration);
	}

	public static boolean isDefinition(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.Definition);
	}

	public static boolean isReadOnly(List<SymbolTag> tags) {
		return SymbolsUtil.hasSymbolTag(tags, SymbolTag.ReadOnly);
	}

}
