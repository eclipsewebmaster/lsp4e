/*******************************************************************************
 * Copyright (c) 2018 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Lucas Bullen (Red Hat Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e;

import java.io.File;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.IConsoleManager;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.console.MessageConsoleStream;

public class LoggingStreamConnectionProviderProxy implements StreamConnectionProvider, IAdaptable {

	public static @Nullable File getLogDirectory() {
		IPath root = ResourcesPlugin.getWorkspace().getRoot().getLocation();
		if (root == null) {
			return null;
		}
		final var logFolder = new File(root.addTrailingSeparator().toPortableString(), "languageServers-log"); //$NON-NLS-1$
		if (!(logFolder.exists() || logFolder.mkdirs()) || !logFolder.isDirectory() || !logFolder.canWrite()) {
			return null;
		}
		return logFolder;
	}

	private static final String FILE_KEY = "file.logging.enabled"; //$NON-NLS-1$
	private static final String STDERR_KEY = "stderr.logging.enabled"; //$NON-NLS-1$

	private final StreamConnectionProvider provider;
	private @Nullable InputStream inputStream;
	private @Nullable OutputStream outputStream;
	private @Nullable InputStream errorStream;
	private final String id;
	private final @Nullable File logFile;
	private boolean logToFile;
	private boolean logToConsole;

	/**
	 * Converts a language server ID to the preference ID for logging communications
	 * to file from the language server
	 *
	 * @return language server's preference ID for file logging
	 */
	public static String lsToFileLoggingId(String serverId) {
		return serverId + "." + FILE_KEY;//$NON-NLS-1$
	}

	/**
	 * Converts a language server ID to the preference ID for logging communications
	 * to console from the language server
	 *
	 * @return language server's preference ID for console logging
	 */
	public static String lsToConsoleLoggingId(String serverId) {
		return serverId + "." + STDERR_KEY;//$NON-NLS-1$
	}

	/**
	 * Returns whether currently created connections should be logged to file or the
	 * standard error stream.
	 *
	 * @return If connections should be logged
	 */
	public static boolean shouldLog(String serverId) {
		IPreferenceStore store = LanguageServerPlugin.getDefault().getPreferenceStore();
		return store.getBoolean(lsToFileLoggingId(serverId)) || store.getBoolean(lsToConsoleLoggingId(serverId));
	}

	public LoggingStreamConnectionProviderProxy(StreamConnectionProvider provider, String serverId) {
		this.id = serverId;
		this.provider = provider;

		IPreferenceStore store = LanguageServerPlugin.getDefault().getPreferenceStore();
		logToFile = store.getBoolean(lsToFileLoggingId(serverId));
		logToConsole = store.getBoolean(lsToConsoleLoggingId(serverId));
		store.addPropertyChangeListener(event -> {
			if (event.getProperty().equals(FILE_KEY) && event.getNewValue() instanceof Boolean newValue) {
				logToFile = newValue;
			} else if (event.getProperty().equals(STDERR_KEY) && event.getNewValue() instanceof Boolean newValue) {
				logToConsole = newValue;
			}
		});
		this.logFile = getLogFile();
	}

	private enum Direction { LANGUAGE_SERVER_TO_LSP4E, LSP4E_TO_LANGUAGE_SERVER, ERROR_FROM_LANGUAGE_SERVER }

	private String message(Direction direction, byte[] payload) {
		String now = OffsetDateTime.now().toString();
		final var builder = new StringBuilder(payload.length + id.length() + direction.toString().length() + now.length() + 10);
		builder.append("\n["); //$NON-NLS-1$
		builder.append(now);
		builder.append("] "); //$NON-NLS-1$
		builder.append(direction);
		builder.append(' ');
		builder.append(id);
		builder.append(":\n"); //$NON-NLS-1$
		builder.append(new String(payload, StandardCharsets.UTF_8));
		return builder.toString();
	}

	private String errorMessage(byte[] payload) {
		return message(Direction.ERROR_FROM_LANGUAGE_SERVER, payload);
	}

	@Override
	public @Nullable InputStream getInputStream() {
		if (inputStream != null) {
			return inputStream;
		}
		if (provider.getInputStream() != null) {
			inputStream = new FilterInputStream(provider.getInputStream()) {
				@Override
				public int read(byte[] b, int off, int len) throws IOException {
					int bytes = super.read(b, off, len);
					final var payload = new byte[bytes];
					System.arraycopy(b, off, payload, 0, bytes);
					if (logToConsole || logToFile) {
						String s = message(Direction.LANGUAGE_SERVER_TO_LSP4E, payload);
						if (logToConsole) {
							logToConsole(s);
						}
						if (logToFile) {
							logToFile(s);
						}
					}
					return bytes;
				}
			};
		}
		return inputStream;
	}

	@Override
	public <T> @Nullable T getAdapter(@Nullable Class<T> adapter) {
		if(adapter == ProcessHandle.class) {
			return Adapters.adapt(provider, adapter);
		}
		return null;
	}

	@Override
	public @Nullable InputStream getErrorStream() {
		if (errorStream != null) {
			return errorStream;
		}
		if (provider.getErrorStream() != null) {
			errorStream = new FilterInputStream(provider.getErrorStream()) {
				@Override
				public int read(byte[] b, int off, int len) throws IOException {
					int bytes = super.read(b, off, len);
					final var payload = new byte[bytes];
					System.arraycopy(b, off, payload, 0, bytes);
					if (logToConsole || logToFile) {
						String s = errorMessage(payload);
						if (logToConsole) {
							logToConsole(s);
						}
						if (logToFile) {
							logToFile(s);
						}
					}
					return bytes;
				}
			};
		}
		return errorStream;
	}

	@Override
	public @Nullable OutputStream getOutputStream() {
		if (outputStream != null) {
			return outputStream;
		}
		if (provider.getOutputStream() != null) {
			outputStream = new FilterOutputStream(provider.getOutputStream()) {
				@Override
				public void write(byte[] b) throws IOException {
					if (logToConsole || logToFile) {
						String s = message(Direction.LSP4E_TO_LANGUAGE_SERVER, b);
						if (logToConsole) {
							logToConsole(s);
						}
						if (logToFile) {
							logToFile(s);
						}
					}
					super.write(b);
				}
			};
		}
		return outputStream;
	}

	@Override
	public void start() throws IOException {
		provider.start();
	}

	@Override
	public @Nullable InputStream forwardCopyTo(@Nullable InputStream input, @Nullable OutputStream output) {
		return provider.forwardCopyTo(input, output);
	}

	@Override
	public @Nullable Object getInitializationOptions(@Nullable URI rootUri) {
		return provider.getInitializationOptions(rootUri);
	}

	@Override
	public String getTrace(@Nullable URI rootUri) {
		return provider.getTrace(rootUri);
	}

	@Override
	public void handleMessage(Message message, LanguageServer languageServer, @Nullable URI rootURI) {
		provider.handleMessage(message, languageServer, rootURI);
	}

	@Override
	public void stop() {
		provider.stop();
		try {
			if (outputStream != null) {
				outputStream.close();
				outputStream = null;
			}
			if (inputStream != null) {
				inputStream.close();
				inputStream = null;
			}
			if (errorStream != null) {
				errorStream.close();
				errorStream = null;
			}
		} catch (IOException e) {
			LanguageServerPlugin.logError(e);
		}
	}

	private void logToConsole(String string) {
		var consoleStream = this.consoleStream;
		if (consoleStream == null || consoleStream.isClosed()) {
			consoleStream = this.consoleStream = findConsole().newMessageStream();
		}
		consoleStream.println(string);
	}

	private @Nullable MessageConsoleStream consoleStream;
	private MessageConsole findConsole() {
		ConsolePlugin plugin = ConsolePlugin.getDefault();
		IConsoleManager conMan = plugin.getConsoleManager();
		for (IConsole existing : conMan.getConsoles()) {
			if (Messages.LSConsoleName.equals(existing.getName()))
				return (MessageConsole) existing;
		}
		// no console found, so create a new one
		final var myConsole = new MessageConsole(Messages.LSConsoleName, null);
		conMan.addConsoles(new IConsole[] { myConsole });
		return myConsole;
	}

	private void logToFile(String string) {
		final var logFile = this.logFile;
		if (logFile == null) {
			return;
		}
		if (!logFile.exists()) {
			try {
				if (!logFile.createNewFile()) {
					throw new IOException(String.format("Failed to create file %s", logFile)); //$NON-NLS-1$
				}
			} catch (IOException e) {
				LanguageServerPlugin.logError(e);
			}
		}
		try {
			Files.write(logFile.toPath(), string.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
		} catch (IOException e) {
			LanguageServerPlugin.logError(e);
		}
	}

	private @Nullable File getLogFile() {
		if (logFile != null) {
			return logFile;
		}
		File logFolder = getLogDirectory();
		if (logFolder == null) {
			return null;
		}
		final var file = new File(logFolder, id + ".log"); //$NON-NLS-1$
		if (file.exists() && !(file.isFile() && file.canWrite())) {
			return null;
		}
		return file;
	}

}
