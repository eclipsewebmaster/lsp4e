/*******************************************************************************
 * Copyright (c) 2023 Red Hat, Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.test.folding;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.test.utils.AbstractTest;
import org.eclipse.lsp4e.test.utils.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockLanguageServer;
import org.eclipse.lsp4e.ui.FoldingPreferencePage;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.tests.harness.util.DisplayHelper;
import org.junit.Test;

public class FoldingTest extends AbstractTest {

	private static final int MAX_WAIT_FOR_FOLDING = 3_000;

	private static final String CONTENT = """
			/**
			 * SPDX-License-Identifier: EPL-2.0
			 */
			import
			import
			import
			/**
			 * Some comment
			 */
			visible
			""";

	@Test
	public void testLicenseHeaderAutoFolding() throws CoreException {
		configureCollapse(FoldingPreferencePage.PREF_AUTOFOLD_LICENSE_HEADERS_COMMENTS, true);
		configureCollapse(FoldingPreferencePage.PREF_AUTOFOLD_IMPORT_STATEMENTS, false);
		IEditorPart editor = createEditor();

		// wait for folding to happen
		TestUtils.waitForAndAssertCondition(MAX_WAIT_FOR_FOLDING, () -> assertEquals("""
			/**
			import
			import
			import
			/**
			 * Some comment
			 */
			visible""", ((StyledText) editor.getAdapter(Control.class)).getText().trim()));
	}

	@Test
	public void testImportsAutoFolding() throws CoreException {
		configureCollapse(FoldingPreferencePage.PREF_AUTOFOLD_LICENSE_HEADERS_COMMENTS, false);
		configureCollapse(FoldingPreferencePage.PREF_AUTOFOLD_IMPORT_STATEMENTS, true);

		IEditorPart editor = createEditor();

		// wait for folding to happen
		TestUtils.waitForAndAssertCondition(MAX_WAIT_FOR_FOLDING, () -> assertEquals("""
			/**
			 * SPDX-License-Identifier: EPL-2.0
			 */
			import
			/**
			 * Some comment
			 */
			visible""", ((StyledText) editor.getAdapter(Control.class)).getText().trim()));
	}

	@Test
	public void testAutoFoldingDisabled() throws CoreException {
		configureCollapse(FoldingPreferencePage.PREF_AUTOFOLD_LICENSE_HEADERS_COMMENTS, false);
		configureCollapse(FoldingPreferencePage.PREF_AUTOFOLD_IMPORT_STATEMENTS, false);
		IEditorPart editor = createEditor();

		// wait a few seconds before testing to ensure no folding happened
		DisplayHelper.sleep(MAX_WAIT_FOR_FOLDING);
		assertEquals(CONTENT, ((StyledText) editor.getAdapter(Control.class)).getText());
	}

	private IEditorPart createEditor() throws CoreException {
		final var foldingRangeLicense = new FoldingRange(0, 2);
		foldingRangeLicense.setKind(FoldingRangeKind.Comment);
		final var foldingRangeImport = new FoldingRange(3, 5);
		foldingRangeImport.setKind(FoldingRangeKind.Imports);
		MockLanguageServer.INSTANCE.setFoldingRanges(List.of(foldingRangeLicense, foldingRangeImport));

		return TestUtils.openEditor(TestUtils.createUniqueTestFile(null, CONTENT));
	}

	private void configureCollapse(String type, boolean collapse) {
		IPreferenceStore store = LanguageServerPlugin.getDefault().getPreferenceStore();
		store.setValue(type, collapse);
		store.setValue(FoldingPreferencePage.PREF_AUTOFOLD_IMPORT_STATEMENTS, collapse); //$NON-NLS-1$
	}
}
