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
package org.eclipse.lsp4e.ui;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

public class FoldingPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public static final String PREF_FOLDING_ENABLED = "foldingReconcilingStrategy.enabled"; //$NON-NLS-1$
	public static final String PREF_AUTOFOLD_COMMENTS = "foldingReconcilingStrategy.collapseComments"; //$NON-NLS-1$
	public static final String PREF_AUTOFOLD_LICENSE_HEADERS_COMMENTS = "foldingReconcilingStrategy.collapseLicenseHeaders"; //$NON-NLS-1$
	public static final String PREF_AUTOFOLD_REGIONS = "foldingReconcilingStrategy.collapseRegions"; //$NON-NLS-1$
	public static final String PREF_AUTOFOLD_IMPORT_STATEMENTS = "foldingReconcilingStrategy.collapseImports"; //$NON-NLS-1$

	public static final class PreferenceInitializer extends AbstractPreferenceInitializer {
		@Override
		public void initializeDefaultPreferences() {
			final var store = LanguageServerPlugin.getDefault().getPreferenceStore();
			store.setDefault(PREF_FOLDING_ENABLED, true);
			store.setDefault(PREF_AUTOFOLD_COMMENTS, false);
			store.setDefault(PREF_AUTOFOLD_LICENSE_HEADERS_COMMENTS, false);
			store.setDefault(PREF_AUTOFOLD_REGIONS, false);
			store.setDefault(PREF_AUTOFOLD_IMPORT_STATEMENTS, false);
		}
	}

	public FoldingPreferencePage() {
		super(GRID);
		setPreferenceStore(LanguageServerPlugin.getDefault().getPreferenceStore());
	}

	@Override
	public void createFieldEditors() {
		final Composite parent = getFieldEditorParent();

		/*
		 * check box to globally enable/disable folding
		 */
		final var foldingEnabled = new BooleanFieldEditor( //
				PREF_FOLDING_ENABLED, //
				"Enable folding", //$NON-NLS-1$
				parent);
		addField(foldingEnabled);

		final var label = new Label(parent, SWT.NONE);
		label.setText("\nInitially fold these elements:"); //$NON-NLS-1$
		label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		/*
		 * check boxes to control auto-folding
		 */
		final var autoFoldComments = new BooleanFieldEditor( //
				PREF_AUTOFOLD_COMMENTS, //
				"Comments", //$NON-NLS-1$
				parent);
		addField(autoFoldComments);

		final var autoFoldLicenseHeaders = new BooleanFieldEditor( //
				PREF_AUTOFOLD_LICENSE_HEADERS_COMMENTS, //
				"License Header Comments", //$NON-NLS-1$
				parent);
		addField(autoFoldLicenseHeaders);

		final var autoFoldRegions = new BooleanFieldEditor( //
				PREF_AUTOFOLD_REGIONS, //
				"Folding Regions", //$NON-NLS-1$
				parent);
		addField(autoFoldRegions);

		final var autoFoldImports = new BooleanFieldEditor( //
				PREF_AUTOFOLD_IMPORT_STATEMENTS, //
				"Import statements", //$NON-NLS-1$
				parent);
		addField(autoFoldImports);

		/*
		 * update editor states
		 */
		final Runnable updateEditorStates = () -> {
			autoFoldComments.setEnabled(foldingEnabled.getBooleanValue(), parent);
			autoFoldLicenseHeaders.setEnabled(foldingEnabled.getBooleanValue() && !autoFoldComments.getBooleanValue(),
					parent);
			autoFoldRegions.setEnabled(foldingEnabled.getBooleanValue(), parent);
			autoFoldImports.setEnabled(foldingEnabled.getBooleanValue(), parent);
		};
		foldingEnabled.getDescriptionControl(parent).addListener(SWT.Selection, event -> updateEditorStates.run());
		autoFoldComments.getDescriptionControl(parent).addListener(SWT.Selection, event -> updateEditorStates.run());
		UI.getDisplay().asyncExec(updateEditorStates);
	}

	@Override
	public void init(IWorkbench workbench) {
		// Initialization logic if needed
	}
}