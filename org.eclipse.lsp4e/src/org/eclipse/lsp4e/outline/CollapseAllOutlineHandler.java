/*******************************************************************************
 * Copyright (c) 2025 Vegard IT GmbH and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Sebastian Thomschke (Vegard IT GmbH) - initial implementation.
 *******************************************************************************/
package org.eclipse.lsp4e.outline;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.lsp4e.ui.UI;
import org.eclipse.ui.views.contentoutline.ContentOutline;

public class CollapseAllOutlineHandler extends AbstractHandler {

	@Override
	public @Nullable Object execute(ExecutionEvent event) {
		final var workbenchPage = UI.getActivePage();
		if (workbenchPage != null //
				&& workbenchPage.getActivePart() instanceof ContentOutline outline
				&& outline.getCurrentPage() instanceof CNFOutlinePage page) {
			page.collapseTree();
		}
		return null;
	}
}
