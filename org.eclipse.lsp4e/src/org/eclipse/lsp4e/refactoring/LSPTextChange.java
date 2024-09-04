/*******************************************************************************
 * Copyright (c) 2019 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Alex Boyko (Pivotal Software Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.refactoring;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.*;

import java.net.URI;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.filesystem.EFS;
import org.eclipse.core.filesystem.IFileStore;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.DocumentChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.ltk.internal.core.refactoring.Changes;
import org.eclipse.text.edits.MalformedTreeException;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.UndoEdit;

@SuppressWarnings("restriction")
public class LSPTextChange extends TextChange {

	private final URI fileUri;

	private Either<IFile, IFileStore> file = lateNonNull();
	private int fAcquireCount;
	private @Nullable ITextFileBuffer fBuffer;
	private String newText;
	private @Nullable Range range;

	public LSPTextChange(String name, URI fileUri, TextEdit textEdit) {
		super(name);
		this.fileUri = fileUri;
		this.newText = textEdit.getNewText();
		this.range = textEdit.getRange();
	}

	public LSPTextChange(String name, URI fileUri, String newText) {
		super(name);
		this.fileUri = fileUri;
		this.newText = newText;
		this.range = null;
	}

	@Override
	protected IDocument acquireDocument(IProgressMonitor pm) throws CoreException {
		fAcquireCount++;
		if (fAcquireCount > 1) {
			return castNonNull(this.fBuffer).getDocument();
		}

		IFile iFile = LSPEclipseUtils.getFileHandle(this.fileUri);
		if (iFile != null) {
			this.file = Either.forLeft(iFile);
		} else {
			this.file = Either.forRight(EFS.getStore(this.fileUri));
		}

		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		if (this.file.isLeft()) {
			this.fBuffer = manager.getTextFileBuffer(this.file.getLeft().getFullPath(), LocationKind.IFILE);
		} else {
			this.fBuffer = manager.getFileStoreTextFileBuffer(this.file.getRight());
		}
		if (this.fBuffer != null) {
			fAcquireCount++; // allows to mark open editor dirty instead of saving
		} else {
			if (this.file.isLeft()) {
				manager.connect(this.file.getLeft().getFullPath(), LocationKind.IFILE, pm);
				this.fBuffer = manager.getTextFileBuffer(this.file.getLeft().getFullPath(), LocationKind.IFILE);
			} else {
				manager.connectFileStore(this.file.getRight(), pm);
				this.fBuffer = manager.getFileStoreTextFileBuffer(this.file.getRight());
			}
		}

		// Just to confuse things, the parent eclipse TextChange class can take a TextEdit, but the ltk TextEdit class is
		// unrelated to the lso4j POJO we receive in our constructor. We need to call setEdit() with an Eclipse TextEdit
		// because that's used by the preview logic to compute the changed document. We do it here rather than in the constructor
		// since we need the document to translate line offsets into character offset. Strictly this would not work then
		// if the platform called getEdit() prior to this method being traversed, but it seems to be OK in practice.
		final IDocument document = castNonNull(this.fBuffer).getDocument();
		int offset = 0;
		final var range = this.range;
		if (range != null && getEdit() == null) {
			try {
				offset = LSPEclipseUtils.toOffset(range.getStart(), document);
				int length = LSPEclipseUtils.toOffset(range.getEnd(), document) - offset;
				this.setEdit(new ReplaceEdit(offset, length, newText));
			} catch (BadLocationException e) {
				// Should not happen
				LanguageServerPlugin.logError(e);
			}
		}
		return document;
	}

	@Override
	protected void commit(IDocument document, IProgressMonitor pm) throws CoreException {
		castNonNull(this.fBuffer).commit(pm, true);
	}

	@Override
	protected void releaseDocument(IDocument document, IProgressMonitor pm) throws CoreException {
		Assert.isTrue(fAcquireCount > 0);
		if (fAcquireCount == 1) {
			ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
			castNonNull(this.fBuffer).commit(pm, true);
			if (this.file.isLeft()) {
				manager.disconnect(this.file.getLeft().getFullPath(), LocationKind.IFILE, pm);
			} else {
				manager.disconnectFileStore(this.file.getRight(), pm);
			}
		}
		fAcquireCount--;
	}

	@Override
	protected @Nullable Change createUndoChange(UndoEdit edit) {
		throw new UnsupportedOperationException("Should not be called!"); //$NON-NLS-1$
	}

	@Override
	public void initializeValidationData(IProgressMonitor pm) {
		// nothing to do yet, comment requested by sonar
	}

	@Override
	public RefactoringStatus isValid(IProgressMonitor pm) throws CoreException {
		return RefactoringStatus.create(Status.OK_STATUS);
	}

	@Override
	public @Nullable Object getModifiedElement() {
		IFile file = LSPEclipseUtils.getFileHandle(this.fileUri);
		if (file != null) {
			return file;
		}
		if (this.fBuffer != null) {
			return this.fBuffer.getDocument();
		}
		return null;
	}

	@Override
	public @Nullable Change perform(IProgressMonitor pm) throws CoreException {
		pm.beginTask("", 3); //$NON-NLS-1$
		IDocument document = null;

		try {
			document = acquireDocument(SubMonitor.convert(pm, 1));

			int offset = 0;
			int length = document.getLength();
			final var range = this.range;
			if (range != null) {
				offset = LSPEclipseUtils.toOffset(range.getStart(), document);
				length = LSPEclipseUtils.toOffset(range.getEnd(), document) - offset;
			}

			final TextChange delegate;
			if (this.file.isRight()) {
				delegate = new DocumentChange("Change in document " + fileUri.getPath(), document); //$NON-NLS-1$
			} else {
				delegate = new TextFileChange("Change in file " + this.file.getLeft().getName(), this.file.getLeft()) { //$NON-NLS-1$
					@Override
					protected boolean needsSaving() {
						return fAcquireCount == 1;
					}
				};
			}
			delegate.initializeValidationData(new NullProgressMonitor());
			delegate.setEdit(new ReplaceEdit(offset, length, newText));

			return delegate.perform(pm);

		} catch (BadLocationException e) {
			throw Changes.asCoreException(e);
		} catch (MalformedTreeException e) {
			throw Changes.asCoreException(e);
		} finally {
			if (document != null) {
				releaseDocument(document, SubMonitor.convert(pm, 1));
			}
			pm.done();
		}
	}

}
