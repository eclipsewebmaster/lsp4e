/*******************************************************************************
 * Copyright (c) 2019 Rogue Wave Software Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Michał Niewrzał (Rogue Wave Software Inc.) - initial implementation
 *  Pierre-Yves B. <pyvesdev@gmail.com> - Bug 545950 - Specifying the directory in ProcessStreamConnectionProvider should not be mandatory
 *  Pierre-Yves B. <pyvesdev@gmail.com> - Bug 508812 - Improve error and logging handling
 *******************************************************************************/
package org.eclipse.lsp4e.server;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;

import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jdt.annotation.Nullable;

/**
 *
 * @since 0.1.0
 */
public abstract class ProcessStreamConnectionProvider implements StreamConnectionProvider, IAdaptable {

	private @Nullable Process process;
	private @Nullable List<String> commands;
	private @Nullable String workingDir;

	protected ProcessStreamConnectionProvider() {
	}

	protected ProcessStreamConnectionProvider(List<String> commands) {
		this.commands = commands;
	}

	protected ProcessStreamConnectionProvider(List<String> commands, String workingDir) {
		this.commands = commands;
		this.workingDir = workingDir;
	}

	@Override
	public void start() throws IOException {
		final var commands = this.commands;
		if (commands == null || commands.isEmpty() || commands.stream().anyMatch(Objects::isNull)) {
			throw new IOException("Unable to start language server: " + this); //$NON-NLS-1$
		}

		ProcessBuilder builder = createProcessBuilder();
		Process p = builder.start();
		this.process = p;
		if (!p.isAlive()) {
			throw new IOException("Unable to start language server: " + this); //$NON-NLS-1$
		}
	}

	protected ProcessBuilder createProcessBuilder() {
		final var builder = new ProcessBuilder(castNonNull(getCommands()));
		final var workDir = getWorkingDirectory();
		if (workDir != null) {
			builder.directory(new File(workDir));
		}
		builder.redirectError(ProcessBuilder.Redirect.INHERIT);
		return builder;
	}

	@Override
	public @Nullable InputStream getInputStream() {
		Process p = process;
		return p == null ? null : p.getInputStream();
	}

	@Override
	public @Nullable InputStream getErrorStream() {
		Process p = process;
		return p == null ? null : p.getErrorStream();
	}

	@Override
	public @Nullable OutputStream getOutputStream() {
		Process p = process;
		return p == null ? null : p.getOutputStream();
	}

	@Override
	public void stop() {
		Process p = process;
		if (p != null) {
			p.destroy();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public <T> @Nullable T getAdapter(@Nullable Class<T> adapter) {
		final var process = this.process;
		if(adapter == ProcessHandle.class) {
			try {
				return process == null ? null : (T) process.toHandle();
			} catch(UnsupportedOperationException ex) {
				// ignore
			}
		}
		return null;
	}

	protected @Nullable Process getProcess() {
		return process;
	}

	protected @Nullable List<String> getCommands() {
		return commands;
	}

	public void setCommands(List<String> commands) {
		this.commands = commands;
	}

	protected @Nullable String getWorkingDirectory() {
		return workingDir;
	}

	public void setWorkingDirectory(String workingDir) {
		this.workingDir = workingDir;
	}

	@Override
	public boolean equals(final @Nullable Object obj) {
		if (obj == null) {
			return false;
		}
		return obj instanceof ProcessStreamConnectionProvider other
				&& Objects.equals(this.getCommands(), other.getCommands())
				&& Objects.equals(this.getWorkingDirectory(), other.getWorkingDirectory());
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.getCommands(), this.getWorkingDirectory());
	}

	@Override
	public String toString() {
		return "ProcessStreamConnectionProvider [commands=" + this.getCommands() + ", workingDir=" //$NON-NLS-1$//$NON-NLS-2$
				+ this.getWorkingDirectory() + "]"; //$NON-NLS-1$
	}
}
