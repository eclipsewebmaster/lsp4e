/**
 *  Copyright (c) 2018 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - Add support for 'textDocument/foldingRange' - Bug 537706
 *  Sebastian Thomschke (Vegard IT GmbH) - Add comments/region default folding and folding prefs listener
 */
package org.eclipse.lsp4e.operations.folding;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerLifecycle;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.reconciler.DirtyRegion;
import org.eclipse.jface.text.reconciler.IReconcilingStrategy;
import org.eclipse.jface.text.reconciler.IReconcilingStrategyExtension;
import org.eclipse.jface.text.source.Annotation;
import org.eclipse.jface.text.source.projection.IProjectionListener;
import org.eclipse.jface.text.source.projection.ProjectionAnnotation;
import org.eclipse.jface.text.source.projection.ProjectionAnnotationModel;
import org.eclipse.jface.text.source.projection.ProjectionViewer;
import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.jface.util.PropertyChangeEvent;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4e.internal.DocumentUtil;
import org.eclipse.lsp4e.ui.FoldingPreferencePage;
import org.eclipse.lsp4j.FoldingRange;
import org.eclipse.lsp4j.FoldingRangeKind;
import org.eclipse.lsp4j.FoldingRangeRequestParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.swt.graphics.FontMetrics;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;

/**
 * LSP folding reconcilinig strategy which consumes the `textDocument/foldingRange` command.
 */
public class LSPFoldingReconcilingStrategy
		implements IReconcilingStrategy, IReconcilingStrategyExtension, IProjectionListener, ITextViewerLifecycle {

	private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

	private @Nullable IDocument document;
	private @Nullable ProjectionAnnotationModel projectionAnnotationModel;
	private @Nullable ProjectionViewer viewer;
	private List<CompletableFuture<@Nullable List<FoldingRange>>> requests = List.of();
	private volatile long timestamp = 0;

	private final IPreferenceStore prefStore = LanguageServerPlugin.getDefault().getPreferenceStore();
	private boolean isFoldingEnabled = prefStore.getBoolean(FoldingPreferencePage.PREF_FOLDING_ENABLED);
	private boolean collapseComments = prefStore.getBoolean(FoldingPreferencePage.PREF_AUTOFOLD_COMMENTS);
	private boolean collapseFoldingRegions = prefStore.getBoolean(FoldingPreferencePage.PREF_AUTOFOLD_REGIONS);
	private boolean collapseImports = prefStore.getBoolean(FoldingPreferencePage.PREF_AUTOFOLD_IMPORT_STATEMENTS);
	private final IPropertyChangeListener foldingPrefsListener = (final PropertyChangeEvent event) -> {
		final var newValue = event.getNewValue();
		if (newValue != null) {
			switch (event.getProperty()) {
			case FoldingPreferencePage.PREF_FOLDING_ENABLED:
				isFoldingEnabled = Boolean.parseBoolean(newValue.toString());
				if(isFoldingEnabled) {
					reconcile(null); // requests folding markers from LS
				} else {
					applyFolding(null); // removes all existing folding markers
				}
				break;
			case FoldingPreferencePage.PREF_AUTOFOLD_COMMENTS:
				collapseComments = Boolean.parseBoolean(newValue.toString());
				break;
			case FoldingPreferencePage.PREF_AUTOFOLD_REGIONS:
				collapseFoldingRegions = Boolean.parseBoolean(newValue.toString());
				break;
			case FoldingPreferencePage.PREF_AUTOFOLD_IMPORT_STATEMENTS:
				collapseImports = Boolean.parseBoolean(newValue.toString());
				break;
			}
		}
	};

	/**
	 * A FoldingAnnotation is a {@link ProjectionAnnotation} it is folding and
	 * overriding the paint method (in a hacky type way) to prevent one line folding
	 * annotations to be drawn.
	 */
	protected class FoldingAnnotation extends ProjectionAnnotation {
		private boolean visible; /* workaround for BUG85874 */

		/**
		 * Creates a new FoldingAnnotation.
		 *
		 * @param isCollapsed
		 *            true if this annotation should be collapsed, false otherwise
		 */
		public FoldingAnnotation(boolean isCollapsed) {
			super(isCollapsed);
			visible = false;
		}

		/**
		 * Does not paint hidden annotations. Annotations are hidden when they only span
		 * one line.
		 *
		 * @see ProjectionAnnotation#paint(org.eclipse.swt.graphics.GC,
		 *      org.eclipse.swt.widgets.Canvas, org.eclipse.swt.graphics.Rectangle)
		 */
		@Override
		public void paint(GC gc, Canvas canvas, Rectangle rectangle) {
			/* workaround for BUG85874 */
			/*
			 * only need to check annotations that are expanded because hidden annotations
			 * should never have been given the chance to collapse.
			 */
			if (!isCollapsed()) {
				// working with rectangle, so line height
				FontMetrics metrics = gc.getFontMetrics();
				if ((rectangle.height / metrics.getHeight()) <= 1) {
					// do not draw annotations that only span one line and
					// mark them as not visible
					visible = false;
					return;
				}
			}
			visible = true;
			super.paint(gc, canvas, rectangle);
		}

		@Override
		public void markCollapsed() {
			/* workaround for BUG85874 */
			// do not mark collapsed if annotation is not visible
			if (visible)
				super.markCollapsed();
		}
	}

	@Override
	public void reconcile(@Nullable IRegion subRegion) {
		final var document = this.document;
		if (!isFoldingEnabled || projectionAnnotationModel == null || document == null) {
			return;
		}

		URI uri = LSPEclipseUtils.toUri(document);
		if (uri == null) {
			return;
		}
		final var identifier = LSPEclipseUtils.toTextDocumentIdentifier(uri);
		final var params = new FoldingRangeRequestParams(identifier);
		// cancel previous requests
		requests.forEach(request -> request.cancel(true));
		requests = LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getFoldingRangeProvider)
				.computeAll(server -> server.getTextDocumentService().foldingRange(params));
		requests.forEach(ranges -> ranges.thenAccept(this::applyFolding));
	}

	private void applyFolding(@Nullable List<FoldingRange> ranges) {
		// these are what are passed off to the annotation model to
		// actually create and maintain the annotations
		final var deletions = new ArrayList<FoldingAnnotation>();
		final var existing = new HashMap<Position, FoldingAnnotation>();
		final var additions = new HashMap<Annotation, Position>();

		// find and mark all folding annotations with length 0 for deletion
		markInvalidAnnotationsForDeletion(deletions, existing);

		if (ranges != null) {
			ranges.stream() //
					.sorted(Comparator.comparing(FoldingRange::getEndLine)) //
					.forEach(foldingRange -> {
						try {
							final var collapsByDefault = foldingRange.getKind() != null
									&& switch (foldingRange.getKind()) {
									case FoldingRangeKind.Comment -> collapseComments;
									case FoldingRangeKind.Imports -> collapseImports;
									case FoldingRangeKind.Region -> collapseFoldingRegions;
									default -> false;
									};
							updateAnnotation(deletions, existing, additions, foldingRange.getStartLine(),
									foldingRange.getEndLine(), collapsByDefault);
						} catch (BadLocationException e) {
							// should never occur
						}
					});
		}

		// be sure projection has not been disabled
		var theProjectionAnnotationModel = projectionAnnotationModel; //use local variable to prevent possible NPE
		if (theProjectionAnnotationModel != null) {
			if (!existing.isEmpty()) {
				deletions.addAll(existing.values());
			}
			// send the calculated updates to the annotations to the
			// annotation model
			theProjectionAnnotationModel.modifyAnnotations(deletions.toArray(Annotation[]::new), additions,
					NO_ANNOTATIONS);
		}
	}

	@Override
	public void install(ITextViewer viewer) {
		if (this.viewer != null) {
			this.viewer.removeProjectionListener(this);
		}
		if (viewer instanceof ProjectionViewer projViewer) {
			this.viewer = projViewer;
			projViewer.addProjectionListener(this);
			this.projectionAnnotationModel = projViewer.getProjectionAnnotationModel();
			prefStore.addPropertyChangeListener(foldingPrefsListener);
		}
	}

	@Override
	public void uninstall() {
		setDocument(null);
		if (viewer != null) {
			viewer.removeProjectionListener(this);
			viewer = null;
			prefStore.removePropertyChangeListener(foldingPrefsListener);
		}
		projectionDisabled();
	}

	@Override
	public void setDocument(@Nullable IDocument document) {
		this.document = document;
	}

	@Override
	public void projectionDisabled() {
		projectionAnnotationModel = null;
	}

	@Override
	public void projectionEnabled() {
		//prevent NPE on concurrent access on viewer:
		var theViewer = viewer;
		if (theViewer != null) {
			projectionAnnotationModel = theViewer.getProjectionAnnotationModel();
		}
	}

	/**
	 * Update annotations.
	 *
	 * @param deletions
	 *            the folding annotations to delete.
	 * @param existing
	 *            the existing folding annotations.
	 * @param additions
	 *            annotation to add
	 * @param line
	 *            the line index
	 * @param endLineNumber
	 *            the end line number
	 * @throws BadLocationException
	 */
	private void updateAnnotation(List<FoldingAnnotation> deletions, Map<Position, FoldingAnnotation> existing,
			Map<Annotation, Position> additions, int line, Integer endLineNumber, boolean collapsedByDefault)
			throws BadLocationException {
		final var document = castNonNull(this.document);
		int startOffset = document.getLineOffset(line);
		int endOffset = document.getLineOffset(endLineNumber) + document.getLineLength(endLineNumber);
		final var newPos = new Position(startOffset, endOffset - startOffset);
		FoldingAnnotation existingAnnotation = existing.remove(newPos);
		if (existingAnnotation == null) {
			additions.put(new FoldingAnnotation(collapsedByDefault), newPos);
		}
	}


	/**
	 * <p>
	 * Searches the given {@link DirtyRegion} for annotations that now have a length
	 * of 0. This is caused when something that was being folded has been deleted.
	 * These {@link FoldingAnnotation}s are then added to the {@link List} of
	 * {@link FoldingAnnotation}s to be deleted
	 * </p>
	 *
	 * @param deletions
	 *            the current list of {@link FoldingAnnotation}s marked for deletion
	 *            that the newly found invalid {@link FoldingAnnotation}s will be
	 *            added to
	 */
	private void markInvalidAnnotationsForDeletion(List<FoldingAnnotation> deletions,
			Map<Position, FoldingAnnotation> existing) {
		final var projectionAnnotationModel = this.projectionAnnotationModel;
		if (projectionAnnotationModel == null)
			return;
		Iterator<Annotation> iter = projectionAnnotationModel.getAnnotationIterator();
		if (iter != null) {
			while (iter.hasNext()) {
				if (iter.next() instanceof FoldingAnnotation foldingAnno) {
					Position pos = projectionAnnotationModel.getPosition(foldingAnno);
					if (pos.length == 0) {
						deletions.add(foldingAnno);
					} else {
						var duplicate = existing.put(pos, foldingAnno);
						if (duplicate != null) {
							deletions.add(duplicate);
						}
					}
				}
			}
		}
	}

	@Override
	public void reconcile(DirtyRegion dirtyRegion, IRegion partition) {
		// Because a reconcile will be performed always on the whole document (this is specified by the LSP),
		// prevent consecutive reconciling on every dirty region if the document has not changed.
		var ts = DocumentUtil.getDocumentModificationStamp(document);
		if (ts != timestamp) {
			reconcile(dirtyRegion);
			timestamp = ts;
		}
	}

	@Override
	public void setProgressMonitor(@Nullable IProgressMonitor monitor) {
		// Do nothing
	}

	@Override
	public void initialReconcile() {
		reconcile(null);
	}
}
