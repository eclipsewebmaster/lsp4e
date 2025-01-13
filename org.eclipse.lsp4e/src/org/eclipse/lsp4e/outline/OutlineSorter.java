/*******************************************************************************
 * Copyright (c) 2021 Vegard IT GmbH and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Sebastian Thomschke (Vegard IT GmbH) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.outline;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.internal.HumanFriendlyComparator;
import org.eclipse.lsp4e.outline.SymbolsModel.DocumentSymbolWithURI;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class OutlineSorter extends ViewerComparator {

	protected final IEclipsePreferences prefs = InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID);

	@Override
	public int compare(final @Nullable Viewer viewer, final @Nullable Object o1, final @Nullable Object o2) {
		if (!isSortingEnabled())
			return 0;

		final String name1 = getName(o1);
		final String name2 = getName(o2);

		if (name1 == null)
			return name2 == null ? 0 : -1;

		if (name2 == null)
			return 1;

		return HumanFriendlyComparator.DEFAULT.compare(name1, name2);
	}

	private @Nullable String getName(@Nullable Object element) {
		if (element == null)
			return null;

		if (element instanceof Either<?, ?> either) {
			element = either.get();
		}
		if (element instanceof DocumentSymbolWithURI symbolWithURI) {
			return symbolWithURI.symbol.getName();
		}
		if (element instanceof DocumentSymbol documentSymbol) {
			return documentSymbol.getName();
		}
		if (element instanceof SymbolInformation symbolInformation) {
			return symbolInformation.getName();
		}
		return null;
	}

	@Override
	public boolean isSorterProperty(final @Nullable Object element, final @Nullable String property) {
		return "name".equals(property); //$NON-NLS-1$
	}

	public boolean isSortingEnabled() {
		return prefs.getBoolean(CNFOutlinePage.SORT_OUTLINE_PREFERENCE, false);
	}
}
