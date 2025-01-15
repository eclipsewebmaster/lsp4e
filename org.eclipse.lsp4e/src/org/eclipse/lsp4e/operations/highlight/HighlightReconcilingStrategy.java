/*******************************************************************************
 * Copyright (c) 2017 Rogue Wave Software Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *  Michal Niewrzal (Rogue Wave Software Inc.) - initial implementation
 *  Angelo Zerr <angelo.zerr@gmail.com> - fix Bug 521020
 *  Lucas Bullen (Red Hat Inc.) - fix Bug 522737, 517428, 527426
 *  Joao Dinis Ferreira (Avaloq Group AG) - Remove all annotations when uninstalling
 *******************************************************************************/
package org.eclipse.lsp4e.operations.highlight;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.lateNonNull;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.ICoreRunnable;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ISynchronizable;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerLifecycle;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.IAnnotationModel;
import org.eclipse.jface.text.source.IAnnotationModelExtension;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.jface.viewers.IPostSelectionProvider;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4j.DocumentHighlight;
import org.eclipse.lsp4j.DocumentHighlightKind;
import org.eclipse.lsp4j.DocumentHighlightParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.swt.custom.StyledText;

/**
 * {@link IReconcilingStrategy} implementation to Highlight Symbol (mark
 * occurrences like).
 *
 */
public class HighlightReconcilingStrategy
		implements IReconcilingStrategy, IReconcilingStrategyExtension, IPreferenceChangeListener, ITextViewerLifecycle {

	public static final String TOGGLE_HIGHLIGHT_PREFERENCE = "org.eclipse.ui.genericeditor.togglehighlight"; //$NON-NLS-1$

	public static final String READ_ANNOTATION_TYPE = "org.eclipse.lsp4e.read"; //$NON-NLS-1$
	public static final String WRITE_ANNOTATION_TYPE = "org.eclipse.lsp4e.write"; //$NON-NLS-1$
	public static final String TEXT_ANNOTATION_TYPE = "org.eclipse.lsp4e.text"; //$NON-NLS-1$

	private boolean enabled;
	private @Nullable ISourceViewer sourceViewer;
	private @Nullable IDocument document;
	private @Nullable Job highlightJob;

	/**
	 * Holds the current occurrence annotations.
	 */
	private Annotation @Nullable [] fOccurrenceAnnotations = null;

	class EditorSelectionChangedListener implements ISelectionChangedListener {

		public void install(@Nullable ISelectionProvider selectionProvider) {
			if (selectionProvider == null)
				return;

			if (selectionProvider instanceof IPostSelectionProvider provider) {
				provider.addPostSelectionChangedListener(this);
			} else {
				selectionProvider.addSelectionChangedListener(this);
			}
		}

		public void uninstall(@Nullable ISelectionProvider selectionProvider) {
			if (selectionProvider == null)
				return;

			if (selectionProvider instanceof IPostSelectionProvider provider) {
				provider.removePostSelectionChangedListener(this);
			} else {
				selectionProvider.removeSelectionChangedListener(this);
			}
		}

		@Override
		public void selectionChanged(SelectionChangedEvent event) {
			updateHighlights(event.getSelection());
		}
	}

	private void updateHighlights(ISelection selection) {
		if (selection instanceof ITextSelection textSelection) {
			if (highlightJob != null) {
				highlightJob.cancel();
			}
			highlightJob = Job.createSystem("LSP4E Highlight", //$NON-NLS-1$
					(ICoreRunnable)(monitor -> collectHighlights(textSelection.getOffset(), monitor)));
			highlightJob.schedule();
		}
	}

	private EditorSelectionChangedListener editorSelectionChangedListener = lateNonNull();

	private List<CompletableFuture<@Nullable List<? extends DocumentHighlight>>> requests = List.of();

	@Override
	public void install(ITextViewer viewer) {
		if (viewer instanceof ISourceViewer thisSourceViewer) {
			IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID);
			preferences.addPreferenceChangeListener(this);
			this.enabled = preferences.getBoolean(TOGGLE_HIGHLIGHT_PREFERENCE, true);
			this.sourceViewer = thisSourceViewer;
			editorSelectionChangedListener = new EditorSelectionChangedListener();
			editorSelectionChangedListener.install(thisSourceViewer.getSelectionProvider());
		}
	}

	@Override
	public void uninstall() {
		removeOccurrenceAnnotations();
		if (sourceViewer != null) {
			editorSelectionChangedListener.uninstall(sourceViewer.getSelectionProvider());
		}
		IEclipsePreferences preferences = InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID);
		preferences.removePreferenceChangeListener(this);
		cancel();
	}

	@Override
	public void setProgressMonitor(@Nullable IProgressMonitor monitor) {
	}

	@Override
	public void initialReconcile() {
		final var sourceViewer = this.sourceViewer;
		if (sourceViewer != null) {
			ISelectionProvider selectionProvider = sourceViewer.getSelectionProvider();
			final StyledText textWidget = sourceViewer.getTextWidget();
			if (textWidget != null) {
				textWidget.getDisplay().asyncExec(() -> {
					if (!textWidget.isDisposed()) {
						updateHighlights(selectionProvider.getSelection());
					}
				});
			}
		}
	}

	@Override
	public void setDocument(@Nullable IDocument document) {
		this.document = document;
	}

	/**
	 * Collect list of highlight for the given caret offset by consuming language
	 * server 'documentHighligh't.
	 *
	 * @param caretOffset
	 * @param monitor
	 */
	private void collectHighlights(int caretOffset, @Nullable IProgressMonitor monitor) {
		final var sourceViewer = this.sourceViewer;
		final var document = this.document;
		if (sourceViewer == null || document == null || !enabled || monitor != null && monitor.isCanceled()) {
			return;
		}
		cancel();
		Position position;
		try {
			position = LSPEclipseUtils.toPosition(caretOffset, document);
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return;
		}
		URI uri = LSPEclipseUtils.toUri(document);
		if (uri == null) {
			return;
		}
		final var identifier = LSPEclipseUtils.toTextDocumentIdentifier(uri);
		final var params = new DocumentHighlightParams(identifier, position);
		requests = LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getDocumentHighlightProvider)
				.computeAll(languageServer -> languageServer.getTextDocumentService().documentHighlight(params));
		requests.forEach(request -> request.thenAcceptAsync(highlights -> {
			if (monitor == null || !monitor.isCanceled()) {
				updateAnnotations(highlights, sourceViewer.getAnnotationModel());
			}
		}));
	}

	/**
	 * Cancel the last call of 'documentHighlight'.
	 */
	private void cancel() {
		requests.forEach(request -> request.cancel(true));
	}

	/**
	 * Update the UI annotations with the given list of DocumentHighlight.
	 *
	 * @param highlights
	 *            list of DocumentHighlight
	 * @param annotationModel
	 *            annotation model to update.
	 */
	private void updateAnnotations(@Nullable List<? extends DocumentHighlight> highlights, IAnnotationModel annotationModel) {
		final var document = this.document;
		if (highlights == null || document == null)
			return;

		final var annotationMap = new HashMap<Annotation, org.eclipse.jface.text.Position>(highlights.size());
		for (DocumentHighlight h : highlights) {
			try {
				int start = LSPEclipseUtils.toOffset(h.getRange().getStart(), document);
				int end = LSPEclipseUtils.toOffset(h.getRange().getEnd(), document);
				annotationMap.put(new Annotation(kindToAnnotationType(h.getKind()), false, null),
						new org.eclipse.jface.text.Position(start, end - start));
			} catch (Exception e) {
				LanguageServerPlugin.logError(e);
			}
		}

		synchronized (getLockObject(annotationModel)) {
			if (annotationModel instanceof IAnnotationModelExtension modelExtension) {
				modelExtension.replaceAnnotations(fOccurrenceAnnotations, annotationMap);
			} else {
				removeOccurrenceAnnotations();
				Iterator<Entry<Annotation, org.eclipse.jface.text.Position>> iter = annotationMap.entrySet().iterator();
				while (iter.hasNext()) {
					Entry<Annotation, org.eclipse.jface.text.Position> mapEntry = iter.next();
					annotationModel.addAnnotation(mapEntry.getKey(), mapEntry.getValue());
				}
			}
			fOccurrenceAnnotations = annotationMap.keySet().toArray(Annotation[]::new);
		}
	}

	/**
	 * Returns the lock object for the given annotation model.
	 *
	 * @param annotationModel
	 *            the annotation model
	 * @return the annotation model's lock object
	 */
	private Object getLockObject(IAnnotationModel annotationModel) {
		if (annotationModel instanceof ISynchronizable sync) {
			Object lock = sync.getLockObject();
			if (lock != null)
				return lock;
		}
		return annotationModel;
	}

	void removeOccurrenceAnnotations() {
		final var sourceViewer = this.sourceViewer;
		if(sourceViewer == null)
			return;

		IAnnotationModel annotationModel = sourceViewer.getAnnotationModel();
		final var fOccurrenceAnnotations = this.fOccurrenceAnnotations;
		if (annotationModel == null || fOccurrenceAnnotations == null)
			return;

		synchronized (getLockObject(annotationModel)) {
			if (annotationModel instanceof IAnnotationModelExtension modelExtension) {
				modelExtension.replaceAnnotations(fOccurrenceAnnotations, null);
			} else {
				for (Annotation fOccurrenceAnnotation : fOccurrenceAnnotations)
					annotationModel.removeAnnotation(fOccurrenceAnnotation);
			}
			this.fOccurrenceAnnotations = null;
		}
	}

	private String kindToAnnotationType(@Nullable DocumentHighlightKind kind) {
		if (kind == null)
			return TEXT_ANNOTATION_TYPE;

		return switch (kind) {
		case Read -> READ_ANNOTATION_TYPE;
		case Write -> WRITE_ANNOTATION_TYPE;
		default -> TEXT_ANNOTATION_TYPE;
		};
	}

	@Override
	public void preferenceChange(PreferenceChangeEvent event) {
		if (event.getKey().equals(TOGGLE_HIGHLIGHT_PREFERENCE)) {
			this.enabled = Boolean.parseBoolean(event.getNewValue().toString());
			if (enabled) {
				initialReconcile();
			} else {
				removeOccurrenceAnnotations();
			}
		}
	}

	@Override
	public void reconcile(DirtyRegion dirtyRegion, IRegion subRegion) {
		// Do nothing
	}

	@Override
	public void reconcile(IRegion partition) {
		// Do nothing
	}

}
