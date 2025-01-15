/*******************************************************************************
 * Copyright (c) 2023 Contributors to the Eclipse Foundation.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   See git history
 *******************************************************************************/
package org.eclipse.lsp4e.format;

import java.util.ArrayList;

import org.eclipse.compare.internal.DocLineComparator;
import org.eclipse.compare.rangedifferencer.RangeDifference;
import org.eclipse.compare.rangedifferencer.RangeDifferencer;
import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.Region;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;

/**
 * Can be implemented by clients as an OSGi service
 * to provide editor-specific formatting regions for the format-on-save feature.
 * The OSGi component service must implement the {@code serverDefinitionId} property.
 * The value must be the {@code server id} of the corresponding {@code languageServer} extension point.
 * This service will then be used for documents connected to this language server.
 *
 * <p>Example:</p>
 * <pre><code>
 * @Component(property={"serverDefinitionId:String=org.eclipse.cdt.lsp.server"})
 * public class FormatOnSave implements IFormatRegionsProvider {
 *   @Reference
 *   private EditorConfiguration configuration;
 *
 *   IRegion[] getFormattingRegions(IDocument document) {
 *     // Formats the whole document:
 *     if (configuration.formatOnSaveEnabled()) {
 *       if (configuration.formatEditedLines()) {
 *         return IFormatRegionsProvider.calculateEditedLineRegions(document);
 *       } else {
 *         return IFormatRegionsProvider.allLines(document);
 *       }
 *     }
 *     return null;
 *   }
 * }
 * </code></pre>
 */
public interface IFormatRegionsProvider {

	/**
	 * Get the formatting regions
	 * @param document
	 * @return region to be formatted or <code>null</code> if the document should not be formatted on save.
	 */
	IRegion @Nullable [] getFormattingRegions(IDocument document);

	/**
	 * Implementation for 'Format all lines'
	 * @param document
	 * @return region containing the whole document
	 */
	static IRegion[] allLines(IDocument document) {
		return new IRegion[] { new Region(0, document.getLength()) };
	}

	/**
	 * Implementation for 'Format edited lines'
	 *
	 * Return the regions of all lines which have changed in the given buffer since the
	 * last save occurred. Each region in the result spans over the size of at least one line.
	 * If successive lines have changed a region spans over the size of all successive lines.
	 * The regions include line delimiters.
	 *
	 * @param monitor to report progress to
	 * @return the regions of the changed lines
	 *
	 */
	static IRegion @Nullable [] calculateEditedLineRegions(final IDocument document, final IProgressMonitor monitor) {
		final var result = new IRegion[1] @Nullable [];

		SafeRunner.run(new ISafeRunnable() {
			@Override
			public void handleException(Throwable exception) {
				LanguageServerPlugin.logError(exception.getLocalizedMessage(), exception);
				result[0] = null;
			}

			@Override
			public void run() throws Exception {
				var buffer = LSPEclipseUtils.toBuffer(document);
				if (buffer == null) {
					result[0] = null;
					return;
				}
				SubMonitor progress = SubMonitor.convert(monitor, "Calculating changed regions", 4); //$NON-NLS-1$
				IFileStore fileStore = buffer.getFileStore();

				ITextFileBufferManager fileBufferManager = FileBuffers.createTextFileBufferManager();
				fileBufferManager.connectFileStore(fileStore, progress.split(3));
				try {
					IDocument currentDocument = buffer.getDocument();
					IDocument oldDocument = ((ITextFileBuffer) fileBufferManager.getFileStoreFileBuffer(fileStore))
							.getDocument();

					result[0] = getChangedLineRegions(oldDocument, currentDocument);
				} finally {
					fileBufferManager.disconnectFileStore(fileStore, progress.split(1));
				}
			}

			/**
			 * Return regions of all lines which differ comparing {@code oldDocument}s content
			 * with {@code currentDocument}s content. Successive lines are merged into one region.
			 *
			 * @param oldDocument a document containing the old content
			 * @param currentDocument a document containing the current content
			 * @return the changed regions
			 * @throws BadLocationException
			 */
			private IRegion[] getChangedLineRegions(IDocument oldDocument, IDocument currentDocument) {
				/*
				 * Do not change the type of those local variables. We use Object
				 * here in order to prevent loading of the Compare plug-in at load
				 * time of this class.
				 */
				final var leftSide = new DocLineComparator(oldDocument, null, false);
				final var rightSide = new DocLineComparator(currentDocument, null, false);

				RangeDifference[] differences = RangeDifferencer.findDifferences(leftSide,
						rightSide);

				// It holds that:
				// 1. Ranges are sorted:
				//     forAll r1,r2 element differences: indexOf(r1) < indexOf(r2) -> r1.rightStart() < r2.rightStart();
				// 2. Successive changed lines are merged into on RangeDifference
				//     forAll r1,r2 element differences: r1.rightStart() < r2.rightStart() -> r1.rightEnd() < r2.rightStart

				final var regions = new ArrayList<IRegion>();
				final int numberOfLines = currentDocument.getNumberOfLines();
				for (RangeDifference curr : differences) {
					if (curr.kind() == RangeDifference.CHANGE) {
						int startLine = Math.min(curr.rightStart(), numberOfLines - 1);
						int endLine = curr.rightEnd() - 1;

						IRegion startLineRegion;
						try {
							startLineRegion = currentDocument.getLineInformation(startLine);
							if (startLine >= endLine) {
								// startLine > endLine indicates a deletion of one or more lines.
								// Deletions are ignored except at the end of the document.
								if (startLine == endLine || startLineRegion.getOffset()
										+ startLineRegion.getLength() == currentDocument.getLength()) {
									regions.add(startLineRegion);
								}
							} else {
								IRegion endLineRegion = currentDocument.getLineInformation(endLine);
								int startOffset = startLineRegion.getOffset();
								int endOffset = endLineRegion.getOffset() + endLineRegion.getLength();
								regions.add(new Region(startOffset, endOffset - startOffset));
							}
						} catch (BadLocationException e) {
							LanguageServerPlugin.logError(e);
						}
					}
				}

				return regions.toArray(IRegion[]::new);
			}
		});
		return result[0];
	}

}
