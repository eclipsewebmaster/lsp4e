/*******************************************************************************
 * Copyright (c) 2016, 2023 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Miro Spoenemann (TypeFox) - extracted LanguageClientImpl
 *  Jan Koehnlein (TypeFox) - bug 521744
 *  Martin Lippert (Pivotal, Inc.) - bug 531030, 527902, 534637
 *  Kris De Volder (Pivotal, Inc.) - dynamic command registration
 *  Tamas Miklossy (itemis) - bug 571162
 *  Rubén Porras Campo (Avaloq Evolution AG) - documentAboutToBeSaved implementation
 *******************************************************************************/
package org.eclipse.lsp4e;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.IFileBuffer;
import org.eclipse.core.filebuffers.IFileBufferListener;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.resources.WorkspaceJob;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProduct;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4e.internal.ArrayUtil;
import org.eclipse.lsp4e.internal.CancellationUtil;
import org.eclipse.lsp4e.internal.FileBufferListenerAdapter;
import org.eclipse.lsp4e.internal.SupportedFeatures;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.ClientInfo;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams;
import org.eclipse.lsp4j.DocumentFormattingOptions;
import org.eclipse.lsp4j.DocumentRangeFormattingOptions;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SelectionRangeRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.TypeHierarchyRegistrationOptions;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WindowClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent;
import org.eclipse.lsp4j.WorkspaceFoldersOptions;
import org.eclipse.lsp4j.WorkspaceServerCapabilities;
import org.eclipse.lsp4j.WorkspaceSymbolOptions;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode;
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Display;

import com.google.common.base.Functions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class LanguageServerWrapper {

	private final IFileBufferListener fileBufferListener = new FileBufferListenerAdapter() {
		@Override
		public void bufferDisposed(IFileBuffer buffer) {
			final var uri = LSPEclipseUtils.toUri(buffer);
			if (uri != null) {
				disconnect(uri);
			}
		}

		@Override
		public void stateChanging(IFileBuffer buffer) {
			if (buffer.isDirty()) {
				DocumentContentSynchronizer documentListener = connectedDocuments.get(LSPEclipseUtils.toUri(buffer));
				if (documentListener != null) {
					documentListener.documentAboutToBeSaved();
				}
			}
		}

		@Override
		public void dirtyStateChanged(IFileBuffer buffer, boolean isDirty) {
			if (isDirty) {
				return;
			}
			DocumentContentSynchronizer documentListener = connectedDocuments.get(LSPEclipseUtils.toUri(buffer));
			if (documentListener != null) {
				documentListener.documentSaved(buffer);
			}
		}

	};

	private static class LanguageServerContext {
		boolean cancelled = false;

		@Nullable Future<?> launcherFuture;
		@Nullable StreamConnectionProvider lspStreamProvider;
		@Nullable LanguageServer languageServer;

		synchronized void close() {
			if (languageServer != null) {
				CompletableFuture<Object> shutdown = languageServer.shutdown();
				try {
					shutdown.get(5, TimeUnit.SECONDS);
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} catch (Exception ex) {
					LanguageServerPlugin.logError(ex.getClass().getSimpleName() + " occurred during shutdown of " //$NON-NLS-1$
							+ languageServer, ex);
				}
			}

			if (launcherFuture != null) {
				launcherFuture.cancel(true);
			}

			if (languageServer != null) {
				languageServer.exit();
			}

			if (lspStreamProvider != null) {
				lspStreamProvider.stop();
			}

		}
	}

	public final LanguageServerDefinition serverDefinition;
	public final @Nullable IProject initialProject;
	protected Map<URI, DocumentContentSynchronizer> connectedDocuments;
	protected final @Nullable IPath initialPath;
	protected final InitializeParams initParams = new InitializeParams();

	private @Nullable CompletableFuture<@Nullable Void> initializeFuture;
	private final AtomicReference<@Nullable IProgressMonitor> initializeFutureMonitorRef = new AtomicReference<>();
	private final int initializeFutureNumberOfStages = 7;
	private @Nullable LanguageClientImpl languageClient;
	private @Nullable ServerCapabilities serverCapabilities;
	private final Timer timer = new Timer("Stop Language Server Task Processor"); //$NON-NLS-1$
	private @Nullable TimerTask stopTimerTask;

	private final ExecutorService dispatcher;
	private final ExecutorService listener;

	private LanguageServerContext context = new LanguageServerContext();

	/**
	 * Map containing unregistration handlers for dynamic capability registrations.
	 */
	private final Map<String, Runnable> dynamicRegistrations = new HashMap<>();
	private boolean initiallySupportsWorkspaceFolders = false;
	private final IResourceChangeListener workspaceFolderUpdater = new WorkspaceFolderListener();

	/* Backwards compatible constructor */
	public LanguageServerWrapper(IProject project, LanguageServerDefinition serverDefinition) {
		this(project, serverDefinition, null);
	}

	public LanguageServerWrapper(LanguageServerDefinition serverDefinition, @Nullable IPath initialPath) {
		this(null, serverDefinition, initialPath);
	}

	/** Unified private constructor to set sensible defaults in all cases */
	private LanguageServerWrapper(@Nullable IProject project, LanguageServerDefinition serverDefinition,
			@Nullable IPath initialPath) {
		this.initialProject = project;
		this.initialPath = initialPath;
		this.serverDefinition = serverDefinition;
		this.connectedDocuments = new HashMap<>();
		String projectName = (project != null && !serverDefinition.isSingleton) ? ("@" + project.getName()) : "";  //$NON-NLS-1$//$NON-NLS-2$
		final var dispatcherThreadNameFormat = "LS-" + serverDefinition.id + projectName + "#dispatcher"; //$NON-NLS-1$ //$NON-NLS-2$
		this.dispatcher = Executors
				.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat(dispatcherThreadNameFormat).build());

		// Executor service passed through to the LSP4j layer when we attempt to start the LS. It will be used
		// to create a listener that sits on the input stream and processes inbound messages (responses, or server-initiated
		// requests).
		final var listenerThreadNameFormat = "LS-" + serverDefinition.id + projectName + "#listener-%d"; //$NON-NLS-1$ //$NON-NLS-2$
		this.listener = Executors
				.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat(listenerThreadNameFormat).build());
	}

	void stopDispatcher() {
		this.dispatcher.shutdownNow();

		// Only really needed for testing - the listener (an instance of ConcurrentMessageProcessor) should exit
		// as soon as the input stream from the LS is closed, and a cached thread pool will recycle idle
		// threads after a 60 second timeout - or immediately in response to JVM shutdown.
		// If we don't do this then a full test run will generate a lot of threads because we create new
		// instances of this class for each test
		this.listener.shutdownNow();
	}

	/**
	 * @return the workspace folder to be announced to the language server
	 */
	private List<WorkspaceFolder> getRelevantWorkspaceFolders() {
		final var languageClient = this.languageClient;
		List<WorkspaceFolder> folders = null;
		if (languageClient != null) {
			try {
				folders = languageClient.workspaceFolders().get(5, TimeUnit.SECONDS);
			} catch (final ExecutionException | TimeoutException ex) {
				LanguageServerPlugin.logError(ex);
			} catch (final InterruptedException ex) {
				LanguageServerPlugin.logError(ex);
				Thread.currentThread().interrupt();
			}
		}
		if (folders == null) {
			folders = LSPEclipseUtils.getWorkspaceFolders();
		}
		return folders;
	}

	/**
	 * Starts a language server and triggers initialization. If language server has been started
	 * before, does nothing.
	 * If the initialization throws an exception (e.g. because the binary for the LS could not be found),
	 * call {@link #restart()} to force another startup attempt (e.g. because the exception could be handled programmatically).
	 * Use {@link #startupFailed()} to check if an exception has occurred.
	 */
	public synchronized void start() {
		start(false);
	}

	/**
	 * Restarts a language server. If language server is not started, calling this
	 * method is the same as calling {@link #start()}.
	 *
	 * @since 0.18
	 */
	public synchronized void restart() {
		start(true);
	}

	/**
	 * Starts a language server and triggers initialization. If language server is
	 * started and active and restart is not forced, does nothing.
	 * If language server is inactive or restart is forced, restart it.
	 *
	 * @param forceRestart
	 *            whether to restart the language server, even it is not inactive.
	 */
	private synchronized void start(boolean forceRestart) {
		final var filesToReconnect = new HashMap<URI, IDocument>();
		if (this.context.languageServer != null) {
			if (isActive() && !forceRestart) {
				return;
			} else {
				for (Entry<URI, DocumentContentSynchronizer> entry : this.connectedDocuments.entrySet()) {
					filesToReconnect.put(entry.getKey(), entry.getValue().getDocument());
				}
				stop();
			}
		}
		if (this.initializeFuture == null || forceRestart) {
			final URI rootURI = getRootURI();
			final Job job = createInitializeLanguageServerJob();
			final LanguageServerContext workingContext = context;

			this.initializeFuture = CompletableFuture.supplyAsync(() -> {
				synchronized (workingContext) {
					markInitializationProgress(workingContext);
					final StreamConnectionProvider lspStreamProvider;
					if (LoggingStreamConnectionProviderProxy.shouldLog(serverDefinition.id)) {
						lspStreamProvider = workingContext.lspStreamProvider = new LoggingStreamConnectionProviderProxy(
								serverDefinition.createConnectionProvider(), serverDefinition.id);
					} else {
						lspStreamProvider = workingContext.lspStreamProvider = serverDefinition
								.createConnectionProvider();
					}
					initParams.setInitializationOptions(lspStreamProvider.getInitializationOptions(rootURI));
					try {
						lspStreamProvider.start();
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}
				return null;
			}).thenRun(() -> {
				synchronized (workingContext) {
					markInitializationProgress(workingContext);
					final var languageClient = this.languageClient = serverDefinition.createLanguageClient();

					initParams.setProcessId((int) ProcessHandle.current().pid());

					if (rootURI != null) {
						initParams.setRootUri(rootURI.toString());
						initParams.setRootPath(rootURI.getPath());
					}

					UnaryOperator<MessageConsumer> wrapper = consumer -> message -> {
						logMessage(message);
						consumer.consume(message);
						final var lspStreamProvider = workingContext.lspStreamProvider;
						final var languageServer = workingContext.languageServer;
						if (lspStreamProvider != null && isActive() &&  languageServer != null) {
							lspStreamProvider.handleMessage(message, languageServer, rootURI);
						}
					};
					initParams.setWorkspaceFolders(getRelevantWorkspaceFolders());
					final var lspStreamProvider= castNonNull(workingContext.lspStreamProvider);
					Launcher<LanguageServer> launcher = serverDefinition.createLauncherBuilder() //
							.setLocalService(languageClient)//
							.setRemoteInterface(serverDefinition.getServerInterface())//
							.setInput(lspStreamProvider.getInputStream())//
							.setOutput(lspStreamProvider.getOutputStream())//
							.setExecutorService(listener)//
							.wrapMessages(wrapper)//
							.create();
					final var languageServer = workingContext.languageServer = launcher.getRemoteProxy();
					languageClient.connect(languageServer, this);
					workingContext.launcherFuture = launcher.startListening();
				}
			}).thenCompose(unused -> {
					markInitializationProgress(workingContext);
					return initServer(rootURI);
			}).thenAccept(res -> {
				synchronized (workingContext) {
					markInitializationProgress(workingContext);
					serverCapabilities = res.getCapabilities();
					this.initiallySupportsWorkspaceFolders = supportsWorkspaceFolders(serverCapabilities);
				}
			}).thenRun(() -> {
				synchronized (workingContext) {
					markInitializationProgress(workingContext);
					castNonNull(workingContext.languageServer).initialized(new InitializedParams());
				}
			}).thenRun(() -> {
				synchronized (workingContext) {
					markInitializationProgress(workingContext);
					final Map<URI, IDocument> toReconnect = filesToReconnect;
					castNonNull(initializeFuture).thenRunAsync(() -> {
						watchProjects();
						for (Entry<URI, IDocument> fileToReconnect : toReconnect.entrySet()) {
							connect(fileToReconnect.getKey(), fileToReconnect.getValue());
						}
					});
					FileBuffers.getTextFileBufferManager().addFileBufferListener(fileBufferListener);
				}
			}).exceptionally(e -> {
				shutdown(workingContext);
				final Throwable cause = e.getCause();
				if (cause instanceof CancellationException c) {
					throw c;
				} else {
					LanguageServerPlugin.logError(e);
					throw new RuntimeException(e);
				}
			});

			if (!this.initializeFuture.isCompletedExceptionally()) {
				job.schedule();
			}
		}
	}

	private void markInitializationProgress(LanguageServerContext context) {
		if (context.cancelled) {
			throw new CancellationException();
		}
		advanceInitializeFutureMonitor();
	}

	private void advanceInitializeFutureMonitor() {
		final var initializeFutureMonitor = initializeFutureMonitorRef.get();
		if (initializeFutureMonitor != null) {
			if (initializeFutureMonitor.isCanceled()) {
				throw new CancellationException();
			}
			initializeFutureMonitor.worked(1);
		}
	}

	private Job createInitializeLanguageServerJob() {
		return new Job(NLS.bind(Messages.initializeLanguageServer_job, serverDefinition.label)) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				final var initializeFutureMonitor = SubMonitor.convert(monitor, initializeFutureNumberOfStages);
				initializeFutureMonitorRef.set(initializeFutureMonitor);
				CompletableFuture<@Nullable Void> currentInitializeFuture = initializeFuture;
				try {
					if (currentInitializeFuture != null) {
						currentInitializeFuture.join();
					}
				} catch (CancellationException e) {
					return Status.CANCEL_STATUS;
				} catch (Exception e) {
					return new Status(IStatus.ERROR, LanguageServerPlugin.PLUGIN_ID, e.getMessage(), e);
				} finally {
					initializeFutureMonitor.done();
					initializeFutureMonitorRef.compareAndSet(initializeFutureMonitor, null);
				}
				return Status.OK_STATUS;
			}

			@Override
			public boolean belongsTo(@Nullable Object family) {
				return LanguageServerPlugin.FAMILY_INITIALIZE_LANGUAGE_SERVER == family;
			}
		};
	}

	private CompletableFuture<InitializeResult> initServer(final @Nullable URI rootURI) {
		final IProduct product = Platform.getProduct();
		final String name = product != null ? product.getName() : "Eclipse IDE"; //$NON-NLS-1$

		final var workspaceClientCapabilities = SupportedFeatures.getWorkspaceClientCapabilities();
		final var textDocumentClientCapabilities = SupportedFeatures.getTextDocumentClientCapabilities();

		WindowClientCapabilities windowClientCapabilities = SupportedFeatures.getWindowClientCapabilities();
		initParams.setCapabilities(new ClientCapabilities(
				workspaceClientCapabilities,
				textDocumentClientCapabilities,
				windowClientCapabilities,
				castNonNull(context.lspStreamProvider).getExperimentalFeaturesPOJO()));
		initParams.setClientInfo(getClientInfo(name));
		initParams.setTrace(castNonNull(context.lspStreamProvider).getTrace(rootURI));

		// no then...Async future here as we want this chain of operation to be sequential and "atomic"-ish
		return castNonNull(context.languageServer).initialize(initParams);
		//FIXME race: this.context may not be what it is expected to be, should be parameter
	}

	@Nullable
	public ProcessHandle getProcessHandle() {
		return Adapters.adapt(context.lspStreamProvider, ProcessHandle.class);
	}

	private ClientInfo getClientInfo(String name) {
		String pluginVersion = Platform.getBundle(LanguageServerPlugin.PLUGIN_ID).getVersion().toString();
		final var clientInfo = new ClientInfo(name, pluginVersion);
		return clientInfo;
	}

	@Nullable
	private URI getRootURI() {
		final IProject project = this.initialProject;
		if (project != null && project.exists()) {
			return LSPEclipseUtils.toUri(project);
		}

		final IPath path = this.initialPath;
		if (path != null) {
			File projectDirectory = path.toFile();
			if (projectDirectory.isFile()) {
				projectDirectory = castNonNull(projectDirectory.getParentFile());
			}
			return LSPEclipseUtils.toUri(projectDirectory);
		}
		return null;
	}

	private static boolean supportsWorkspaceFolders(@Nullable ServerCapabilities serverCapabilities) {
		return serverCapabilities != null
			&& serverCapabilities.getWorkspace() != null
			&& serverCapabilities.getWorkspace().getWorkspaceFolders() != null
			&& Boolean.TRUE.equals(serverCapabilities.getWorkspace().getWorkspaceFolders().getSupported());
	}

	private void logMessage(Message message) {
		if (message instanceof ResponseMessage responseMessage && responseMessage.getError() != null
				&& Integer.toString(ResponseErrorCode.RequestCancelled.getValue()).equals(responseMessage.getId())) {
			LanguageServerPlugin.logError(new ResponseErrorException(responseMessage.getError()));
		} else if (LanguageServerPlugin.DEBUG) {
			LanguageServerPlugin.logInfo(message.getClass().getSimpleName() + '\n' + message);
		}
	}

	/**
	 * @return whether the underlying connection to language server is still active
	 */
	public synchronized boolean isActive() {
		final var launcherFuture = context.launcherFuture;
		return launcherFuture != null && !launcherFuture.isDone();
	}

	/**
	 * @return whether the last startup attempt has failed
	 */
	public synchronized boolean startupFailed() {
		return this.initializeFuture != null && this.initializeFuture.isCompletedExceptionally();
	}

	private void removeStopTimerTask() {
		synchronized (timer) {
			if (stopTimerTask != null) {
				stopTimerTask.cancel();
				stopTimerTask = null;
			}
		}
	}

	private void startStopTimerTask() {
		synchronized (timer) {
			if (stopTimerTask != null) {
				stopTimerTask.cancel();
			}
			stopTimerTask = new TimerTask() {
				@Override
				public void run() {
					stop();
				}
			};
			timer.schedule(stopTimerTask, TimeUnit.SECONDS.toMillis(this.serverDefinition.lastDocumentDisconnectedTimeout));
		}
	}

	/**
	 * Internal hook so that the unwrapped remote proxy can be matched to the corresponding
	 * wrapper, which tracks things like whether it is still running or not
	 * @param server LanguageServer to match on
	 * @return True if this is the wrapper for the given server
	 */
	boolean isWrapperFor(LanguageServer server) {
		return server == context.languageServer;
	}

	public synchronized void stop() {
		if (initializeFuture != null) {
			initializeFuture.cancel(true);
			initializeFuture= null;
		}

		LanguageServerContext contextToStop = context;
		context = new LanguageServerContext();
		synchronized(contextToStop) {
			contextToStop.cancelled = true;
		}

		shutdown(contextToStop);
	}

	private void shutdown(LanguageServerContext workingContext) {
		removeStopTimerTask();

		if (this.languageClient != null) {
			this.languageClient.dispose();
		}

		this.serverCapabilities = null;
		this.dynamicRegistrations.clear();

		ResourcesPlugin.getWorkspace().removeResourceChangeListener(workspaceFolderUpdater);

		CompletableFuture.runAsync(workingContext::close);

		while (!this.connectedDocuments.isEmpty()) {
			disconnect(this.connectedDocuments.keySet().iterator().next());
		}

		FileBuffers.getTextFileBufferManager().removeFileBufferListener(fileBufferListener);
	}

	public @Nullable CompletableFuture<LanguageServerWrapper> connect(@Nullable IDocument document, IFile file) {
		final URI uri = LSPEclipseUtils.toUri(file);
		if (uri != null) {
			return connect(uri, document);
		}
		return null;
	}

	public @Nullable CompletableFuture<LanguageServerWrapper> connectDocument(IDocument document) {
		IFile file = LSPEclipseUtils.getFile(document);

		if (file != null && file.exists()) {
			return connect(document, file);
		}

		final URI uri = LSPEclipseUtils.toUri(document);
		return uri == null ? null : connect(uri, document);
	}

	private void watchProjects() {
		if (!supportsWorkspaceFolderCapability()) {
			return;
		}
		final LanguageServer currentLS = context.languageServer;
		new WorkspaceJob("Setting watch projects on server " + serverDefinition.label) { //$NON-NLS-1$
			@Override
			public IStatus runInWorkspace(@Nullable IProgressMonitor monitor) throws CoreException {
				final var wsFolderEvent = new WorkspaceFoldersChangeEvent();
				wsFolderEvent.getAdded().addAll(getRelevantWorkspaceFolders());
				if (currentLS != null && currentLS == context.languageServer) {
					currentLS.getWorkspaceService()
							.didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(wsFolderEvent));
				}
				ResourcesPlugin.getWorkspace().addResourceChangeListener(workspaceFolderUpdater,
						IResourceChangeEvent.POST_CHANGE | IResourceChangeEvent.PRE_DELETE);
				return Status.OK_STATUS;
			}
		}.schedule();
	}

	/**
	 * Check whether this LS is suitable for provided project.
	 *
	 * @return whether this language server can operate on the given project
	 * @since 0.5
	 */
	public boolean canOperate(@Nullable IProject project) {
		return Objects.equals(project, this.initialProject)
			|| serverDefinition.isSingleton
			|| supportsWorkspaceFolderCapability();
	}

	public boolean canOperate(IDocument document) {
		URI documentUri = LSPEclipseUtils.toUri(document);
		if (documentUri == null) {
			return false;
		}
		if (this.isConnectedTo(documentUri)) {
			return true;
		}
		if (this.initialProject == null && this.connectedDocuments.isEmpty()) {
			return true;
		}
		IFile file = LSPEclipseUtils.getFile(document);
		if (file != null && file.exists() && canOperate(file.getProject())) {
			return true;
		}
		return serverDefinition.isSingleton || supportsWorkspaceFolderCapability();
	}

	/**
	 * @return true, if the server supports multi-root workspaces via workspace folders
	 * @since 0.6
	 */
	private boolean supportsWorkspaceFolderCapability() {
		if (this.initializeFuture != null) {
			try {
				this.initializeFuture.get(1, TimeUnit.SECONDS);
			} catch (ExecutionException e) {
				LanguageServerPlugin.logError(e);
			} catch (InterruptedException e) {
				LanguageServerPlugin.logError(e);
				Thread.currentThread().interrupt();
			} catch (TimeoutException e) {
				LanguageServerPlugin.logWarning("Could not get if the workspace folder capability is supported due to timeout after 1 second", e); //$NON-NLS-1$
			}
		}
		return initiallySupportsWorkspaceFolders || supportsWorkspaceFolders(serverCapabilities);
	}

	/**
	 * To make public when we support non IFiles
	 *
	 * @return null if not connection has happened, a future that completes when file is initialized otherwise
	 * @noreference internal so far
	 */
	private @Nullable CompletableFuture<LanguageServerWrapper> connect(URI uri, @Nullable IDocument document) {
		removeStopTimerTask();
		if (this.connectedDocuments.containsKey(uri)) {
			return CompletableFuture.completedFuture(this);
		}
		start();
		if (this.initializeFuture == null) {
			return null;
		}
		if (document == null) {
			final var docFile = (IFile) LSPEclipseUtils.findResourceFor(uri);
			document = LSPEclipseUtils.getDocument(docFile);
		}
		if (document == null) {
			return null;
		}
		final IDocument theDocument = document;
		return castNonNull(initializeFuture).thenAcceptAsync(theVoid -> {
			synchronized (connectedDocuments) {
				if (this.connectedDocuments.containsKey(uri)) {
					return;
				}
				TextDocumentSyncKind syncKind = initializeFuture == null ? null
						: castNonNull(serverCapabilities).getTextDocumentSync().map(Functions.identity(), TextDocumentSyncOptions::getChange);
				final var listener = new DocumentContentSynchronizer(this, castNonNull(context.languageServer), theDocument, syncKind);
				theDocument.addPrenotifiedDocumentListener(listener);
				LanguageServerWrapper.this.connectedDocuments.put(uri, listener);
			}
		}).thenApply(theVoid -> this);
	}

	/**
	 * @param uri
	 * @return null if not disconnection has happened, a future tracking the disconnection state otherwise
	 */
	public @Nullable CompletableFuture<@Nullable Void> disconnect(URI uri) {
		DocumentContentSynchronizer documentListener = this.connectedDocuments.remove(uri);
		CompletableFuture<@Nullable Void> documentClosedFuture = null;
		if (documentListener != null) {
			documentListener.getDocument().removePrenotifiedDocumentListener(documentListener);
			documentClosedFuture = documentListener.documentClosed();
		}
		if (this.connectedDocuments.isEmpty()) {
			if (this.serverDefinition.lastDocumentDisconnectedTimeout != 0) {
				startStopTimerTask();
			} else {
				stop();
			}
		}
		return documentClosedFuture;
	}

	public void disconnectContentType(IContentType contentType) {
		final var urisToDisconnect = new ArrayList<URI>();
		for (URI uri : connectedDocuments.keySet()) {
			final var file = ArrayUtil.findFirst(ResourcesPlugin.getWorkspace().getRoot().findFilesForLocationURI(uri));
			if (file != null && LSPEclipseUtils.getFileContentTypes(file).stream().anyMatch(contentType::equals)) {
				urisToDisconnect.add(uri);
			}
		}
		for (URI uri : urisToDisconnect) {
			disconnect(uri);
		}
	}

	/**
	 * checks if the wrapper is already connected to the document at the given uri
	 *
	 * @noreference test only
	 */
	public boolean isConnectedTo(URI uri) {
		return connectedDocuments.containsKey(uri);
	}

	/**
	 * Starts and returns the language server, regardless of if it is initialized.
	 * If not in the UI Thread, will wait to return the initialized server.
	 *
	 */
	@Nullable
	protected LanguageServer getServer() {
		CompletableFuture<LanguageServer> languagServerFuture = getInitializedServer();
		if (Display.getCurrent() != null) { // UI Thread
			return context.languageServer;
		} else {
			return languagServerFuture.join();
		}
	}

	/**
	 * Starts the language server and returns a CompletableFuture waiting for the
	 * server to be initialized and up-to-date (all related pending document changes
	 * notifications are sent).
	 * <p>If done in the UI thread, a job will be created
	 * displaying that the server is being initialized</p>
	 */
	protected CompletableFuture<LanguageServer> getInitializedServer() {
		start();

		final CompletableFuture<@Nullable Void> currentInitializeFuture = initializeFuture;
		if (currentInitializeFuture != null && !currentInitializeFuture.isDone()) {
			return currentInitializeFuture.thenApply(r -> castNonNull(context.languageServer));
		}
		return CompletableFuture.completedFuture(context.languageServer);
	}

	/**
	 * Sends a notification to the wrapped language server
	 *
	 * @param fn
	 *            LS notification to send
	 */
	public void sendNotification(Consumer<LanguageServer> fn) {
		// Enqueues a notification on the dispatch thread associated with the wrapped language server. This
		// ensures the interleaving of document updates and other requests in the UI is mirrored in the
		// order in which they get dispatched to the server
		getInitializedServer().thenAcceptAsync(fn, this.dispatcher);
	}

	/**
	 * Runs a request on the language server
	 *
	 * @param <T> LS response type
	 * @param fn Code block that will be supplied the LS in a state where it is guaranteed to have been initialized.
	 * This should usually be simply invoking a method of LSP4E; more complex work
	 * like result transformation should be avoided here, because
	 * <ul>
	 * <li>LSP4E will cancel those futures when necessary, and the cancellation is expected to
	 * also cancel the LSP request; so this method makes LSP4E support proper cancellation only when
	 * the future here originates from LSP4J API (or transitively cancels the related future from LSP4J
	 * API)
	 * <li>This work will run on the Language Server dispatcher thread; so extraneous work will block the
	 * thread for other work, like other pending LSP requests</li>
	 * </ul>
	 *
	 * @return Async result
	 */
	public <@Nullable T> CompletableFuture<T> execute(Function<LanguageServer, ? extends CompletableFuture<T>> fn) {
		// Send the request on the dispatch thread
		CompletableFuture<T> lsRequest = executeImpl(fn);
		// then additionally make sure the response is delivered on a thread from the default ForkJoinPool.
		// This makes sure the user can't chain on an arbitrary
		// long-running block of code that would tie up the server response listener and prevent any more
		// inbound messages being read
		CompletableFuture<T> future = lsRequest.thenApplyAsync(Function.identity());
		// and ensure cancellation of the returned future cancels the LS request (send cancel event via
		// LSP4J)
		future.exceptionally(t -> {
			if (t instanceof CancellationException) {
				lsRequest.cancel(true);
			}
			return null;
		});
		return future;
	}

	/**
	 * Runs a request on the language server. Internal hook for the LSPexecutor implementations
	 *
	 * @param <T> LS response type
	 * @param fn LSP method to invoke.
	 * This should usually be simply invoking a method of LSP4E; more complex work
	 * like result transformation should be avoided here, because
	 * <ul>
	 * <li>LSP4E will cancel those futures when necessary, and the cancellation is expected to
	 * also cancel the LSP request; so this method makes LSP4E support proper cancellation only when
	 * the future here originates from LSP4J API (or transitively cancels the related future from LSP4J
	 * API)
	 * <li>This work will run on the Language Server dispatcher thread; so extraneous work will block the
	 * thread for other work, like other pending LSP requests</li>
	 * </ul>
	 * @return Async result
	 */
	<@Nullable T> CompletableFuture<T> executeImpl(Function<LanguageServer, ? extends CompletableFuture<T>> fn) {
		// Run the supplied function, ensuring that it is enqueued on the dispatch thread associated with the
		// wrapped language server, and is thus guaranteed to be seen in the correct order with respect
		// to e.g. previous document changes
		//
		// Note this doesn't get the .thenApplyAsync(Function.identity()) chained on additionally, unlike
		// the public-facing version of this method, because we trust the LSPExecutor implementations to
		// make sure the server response thread doesn't get blocked by any further work
		final var request = new AtomicReference<@Nullable CompletableFuture<T>>();
		Function<LanguageServer, CompletableFuture<T>> cancelWrapper = ls -> {
			CompletableFuture<T> res = fn.apply(ls);
			request.set(res);
			return res;
		};
		CompletableFuture<T> res = getInitializedServer().thenComposeAsync(cancelWrapper, this.dispatcher);
		res.exceptionally(e -> {
			if (e instanceof CancellationException) {
				CompletableFuture<T> stage = request.get();
				if (stage != null) {
					stage.cancel(false);
				}
			}
			return null;
		});
		return res;
	}

	/**
	 * <b>IMPORTANT:</b> If the server isn't yet initialized this method will be
	 * blocking for up to 10 seconds!
	 *
	 * @return the server capabilities, or null if initialization job didn't
	 *         complete
	 */
	public @Nullable ServerCapabilities getServerCapabilities() {
		try {
			getInitializedServer().get(10, TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			LanguageServerPlugin.logError("LanguageServer not initialized within 10s", e); //$NON-NLS-1$
		} catch (ExecutionException | CancellationException e) {
			if (!CancellationUtil.isRequestCancelledException(e)) { // do not report error if the server has cancelled the request
				LanguageServerPlugin.logError(e);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			LanguageServerPlugin.logError(e);
		}

		return this.serverCapabilities;
	}

	public CompletableFuture<ServerCapabilities> getServerCapabilitiesAsync() {
		return getInitializedServer().thenApply(ls -> castNonNull(this.serverCapabilities));
	}

	/**
	 * @return The language ID that this wrapper is dealing with if defined in the
	 *         content type mapping for the language server
	 */
	public @Nullable String getLanguageId(IContentType[] contentTypes) {
		for (IContentType contentType : contentTypes) {
			String languageId = serverDefinition.languageIdMappings.get(contentType);
			if (languageId != null) {
				return languageId;
			}
		}
		return null;
	}

	void registerCapability(RegistrationParams params) {
		final var serverCapabilities = this.serverCapabilities;
		Assert.isNotNull(serverCapabilities,
				"Dynamic capability registration failed! Server not yet initialized?"); //$NON-NLS-1$
		params.getRegistrations().forEach(reg -> {
			switch (reg.getMethod()) {
			case "workspace/didChangeWorkspaceFolders":  //$NON-NLS-1$
				if (initiallySupportsWorkspaceFolders) {
					// Can treat this as a NOP since nothing can disable it dynamically if it was
					// enabled on initialization.
				} else if (supportsWorkspaceFolders(serverCapabilities)) {
					LanguageServerPlugin.logWarning(
							"Dynamic registration of 'workspace/didChangeWorkspaceFolders' ignored. It was already enabled before", //$NON-NLS-1$
							null);
				} else {
					addRegistration(reg, () -> setWorkspaceFoldersEnablement(false));
					setWorkspaceFoldersEnablement(true);
				}
				break;
			case "workspace/executeCommand": //$NON-NLS-1$
				final var gson = new Gson(); // TODO? retrieve the GSon used by LS
				ExecuteCommandOptions executeCommandOptions = castNonNull(gson.fromJson((JsonObject) reg.getRegisterOptions(),
						ExecuteCommandOptions.class));
				List<String> newCommands = executeCommandOptions.getCommands();
				if (!newCommands.isEmpty()) {
					addRegistration(reg, () -> unregisterCommands(newCommands));
					registerCommands(newCommands);
				}
				break;
			case "textDocument/formatting": //$NON-NLS-1$
				Either<Boolean, DocumentFormattingOptions> documentFormattingProvider = serverCapabilities
						.getDocumentFormattingProvider();
				if (documentFormattingProvider == null || documentFormattingProvider.isLeft()) {
					serverCapabilities.setDocumentFormattingProvider(Boolean.TRUE);
					addRegistration(reg, () -> serverCapabilities.setDocumentFormattingProvider(documentFormattingProvider));
				} else {
					serverCapabilities.setDocumentFormattingProvider(documentFormattingProvider.getRight());
					addRegistration(reg, () -> serverCapabilities.setDocumentFormattingProvider(documentFormattingProvider));
				}
				break;
			case "textDocument/rangeFormatting": //$NON-NLS-1$
				Either<Boolean, DocumentRangeFormattingOptions> documentRangeFormattingProvider = serverCapabilities
						.getDocumentRangeFormattingProvider();
				if (documentRangeFormattingProvider == null || documentRangeFormattingProvider.isLeft()) {
					serverCapabilities.setDocumentRangeFormattingProvider(Boolean.TRUE);
					addRegistration(reg, () -> serverCapabilities
							.setDocumentRangeFormattingProvider(documentRangeFormattingProvider));
				} else {
					serverCapabilities.setDocumentRangeFormattingProvider(documentRangeFormattingProvider.getRight());
					addRegistration(reg, () -> serverCapabilities
							.setDocumentRangeFormattingProvider(documentRangeFormattingProvider));
				}
				break;
			case "textDocument/codeAction": //$NON-NLS-1$
				final Either<Boolean, CodeActionOptions> beforeRegistration = serverCapabilities.getCodeActionProvider();
				serverCapabilities.setCodeActionProvider(Boolean.TRUE);
				addRegistration(reg, () -> serverCapabilities.setCodeActionProvider(beforeRegistration));
				break;
			case "workspace/symbol": //$NON-NLS-1$
				final Either<Boolean, WorkspaceSymbolOptions> workspaceSymbolBeforeRegistration = serverCapabilities.getWorkspaceSymbolProvider();
				serverCapabilities.setWorkspaceSymbolProvider(Boolean.TRUE);
				addRegistration(reg, () -> serverCapabilities.setWorkspaceSymbolProvider(workspaceSymbolBeforeRegistration));
				break;
			case "textDocument/selectionRange": //$NON-NLS-1$
				Either<Boolean, SelectionRangeRegistrationOptions> selectionRangeProvider = serverCapabilities
						.getSelectionRangeProvider();
				if (selectionRangeProvider == null || selectionRangeProvider.isLeft()) {
					serverCapabilities.setSelectionRangeProvider(Boolean.TRUE);
					addRegistration(reg, () -> serverCapabilities.setSelectionRangeProvider(selectionRangeProvider));
				} else {
					serverCapabilities.setSelectionRangeProvider(selectionRangeProvider.getRight());
					addRegistration(reg, () -> serverCapabilities.setSelectionRangeProvider(selectionRangeProvider));
				}
				break;
			case "textDocument/typeHierarchy": //$NON-NLS-1$
				final Either<Boolean, TypeHierarchyRegistrationOptions> typeHierarchyBeforeRegistration = serverCapabilities.getTypeHierarchyProvider();
				serverCapabilities.setTypeHierarchyProvider(Boolean.TRUE);
				addRegistration(reg, () -> serverCapabilities.setTypeHierarchyProvider(typeHierarchyBeforeRegistration));
				break;
		}});
	}

	private void addRegistration(Registration reg, Runnable unregistrationHandler) {
		String regId = reg.getId();
		synchronized (dynamicRegistrations) {
			if (dynamicRegistrations.containsKey(regId)) {
				ILog.get().warn("A registration with id " + regId + " already exists. Unregistering may not fully work in this case.\n"); //$NON-NLS-1$ //$NON-NLS-2$
			} else {
				dynamicRegistrations.put(regId, unregistrationHandler);
			}
		}
	}

	synchronized void setWorkspaceFoldersEnablement(boolean enable) {
		if (enable == supportsWorkspaceFolderCapability()) {
			return;
		}
		var serverCapabilities = this.serverCapabilities;
		if (serverCapabilities == null) {
			serverCapabilities = this.serverCapabilities = new ServerCapabilities();
		}
		WorkspaceServerCapabilities workspace = serverCapabilities.getWorkspace();
		if (workspace == null) {
			workspace = new WorkspaceServerCapabilities();
			serverCapabilities.setWorkspace(workspace);
		}
		WorkspaceFoldersOptions folders = workspace.getWorkspaceFolders();
		if (folders == null) {
			folders = new WorkspaceFoldersOptions();
			workspace.setWorkspaceFolders(folders);
		}
		folders.setSupported(enable);
		if (enable) {
			watchProjects();
		}
	}

	synchronized void registerCommands(List<String> newCommands) {
		ServerCapabilities caps = this.getServerCapabilities();
		if (caps != null) {
			ExecuteCommandOptions commandProvider = caps.getExecuteCommandProvider();
			if (commandProvider == null) {
				commandProvider = new ExecuteCommandOptions(new ArrayList<>());
				caps.setExecuteCommandProvider(commandProvider);
			}
			List<String> existingCommands = commandProvider.getCommands();
			for (String newCmd : newCommands) {
				Assert.isLegal(!existingCommands.contains(newCmd), "Command already registered '" + newCmd + "'"); //$NON-NLS-1$ //$NON-NLS-2$
				existingCommands.add(newCmd);
			}
		} else {
			throw new IllegalStateException("Dynamic command registration failed! Server not yet initialized?"); //$NON-NLS-1$
		}
	}

	void unregisterCapability(UnregistrationParams params) {
		params.getUnregisterations().forEach(reg -> {
			String id = reg.getId();
			Runnable unregistrator;
			synchronized (dynamicRegistrations) {
				unregistrator = dynamicRegistrations.get(id);
				dynamicRegistrations.remove(id);
			}
			if (unregistrator != null) {
				unregistrator.run();
			}
		});
	}

	void unregisterCommands(List<String> cmds) {
		ServerCapabilities caps = this.getServerCapabilities();
		if (caps != null) {
			ExecuteCommandOptions commandProvider = caps.getExecuteCommandProvider();
			if (commandProvider != null) {
				List<String> existingCommands = commandProvider.getCommands();
				existingCommands.removeAll(cmds);
			}
		}
	}

	/**
	 * return the TextDocument version, suitable to build a TextDocumentIndentifier
	 */
	public int getTextDocumentVersion(URI uri) {
		DocumentContentSynchronizer documentContentSynchronizer = connectedDocuments.get(uri);
		if (documentContentSynchronizer != null) {
			return documentContentSynchronizer.getVersion();
		}
		return -1;
	}

	@Override
	public String toString() {
		final var ph = getProcessHandle();
		return getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(this)) //
				+ " [serverId=" + serverDefinition.id //$NON-NLS-1$
				+ ", initialPath=" + initialPath //$NON-NLS-1$
				+ ", initialProject=" + initialProject //$NON-NLS-1$
				+ ", isActive=" + isActive() //$NON-NLS-1$
				+ ", pid=" + (ph == null ? null : ph.pid()) //$NON-NLS-1$
				+ ']';
	}

	/**
	 * Resource listener that translates Eclipse resource events into LSP workspace folder events
	 * and dispatches them if the language server is still active
	 */
	private final class WorkspaceFolderListener implements IResourceChangeListener {
		@Override
		public void resourceChanged(IResourceChangeEvent event) {
			WorkspaceFoldersChangeEvent workspaceFolderEvent = toWorkspaceFolderEvent(event);
			if (workspaceFolderEvent == null
					|| (workspaceFolderEvent.getAdded().isEmpty() && workspaceFolderEvent.getRemoved().isEmpty())) {
				return;
			}
			// If shutting down, language server will be set to null, so ignore the event
			final LanguageServer currentServer = context.languageServer;
			if (currentServer != null) {
				currentServer.getWorkspaceService()
						.didChangeWorkspaceFolders(new DidChangeWorkspaceFoldersParams(workspaceFolderEvent));
			}
		}

		private @Nullable WorkspaceFoldersChangeEvent toWorkspaceFolderEvent(IResourceChangeEvent e) {
			if (!isPostChangeEvent(e) && !isPreDeletEvent(e)) {
				return null;
			}

			// If a project delete then the delta is null, but we get the project in the top-level resource
			final var wsFolderEvent = new WorkspaceFoldersChangeEvent();
			if (isPreDeletEvent(e)) {
				final IResource resource = e.getResource();
				if (resource instanceof IProject project) {
					wsFolderEvent.getRemoved().add(LSPEclipseUtils.toWorkspaceFolder(project));
					return wsFolderEvent;
				} else {
					return null;
				}
			}

			// Use the visitor implementation to extract the low-level detail from delta
			var relevantFolders = getRelevantWorkspaceFolders();
			try {
				e.getDelta().accept(delta -> {
					if (delta.getResource() instanceof IProject project) {
						final WorkspaceFolder wsFolder = LSPEclipseUtils.toWorkspaceFolder(project);
						if (relevantFolders.contains(wsFolder)
								&& (isAddEvent(delta) || isProjectOpenCloseEvent(delta))
								&& project.isAccessible()
								&& isValid(wsFolder)) {
							wsFolderEvent.getAdded().add(wsFolder);
						} else if ((isRemoveEvent(delta)|| isProjectOpenCloseEvent(delta))
										&& !project.isAccessible()
										&& isValid(wsFolder)) {
							wsFolderEvent.getRemoved().add(wsFolder);
						}
						// TODO: handle renamed/moved (on filesystem)
					}
					return delta.getResource().getType() == IResource.ROOT;
				});
			} catch (CoreException ex) {
				LanguageServerPlugin.logError(ex);
			}
			if (wsFolderEvent.getAdded().isEmpty() && wsFolderEvent.getRemoved().isEmpty()) {
				return null;
			}
			return wsFolderEvent;
		}

		/**
		 *
		 * @return True if this event is being fired after a change (e.g. a project open/close)
		 */
		private boolean isPostChangeEvent(IResourceChangeEvent e) {
			return e.getType() == IResourceChangeEvent.POST_CHANGE;
		}

		/**
		 *
		 * @return True if this event is being fired prior to a project resource being deleted
		 */
		private boolean isPreDeletEvent(IResourceChangeEvent e) {
			return e.getType() == IResourceChangeEvent.PRE_DELETE;
		}

		/**
		 *
		 * @return True if this delta corresponds to a project resource being added
		 */
		private boolean isAddEvent(IResourceDelta delta) {
			return delta.getKind() == IResourceDelta.ADDED;
		}

		/**
		 *
		 * @return True if this delta corresponds to a project resource being removed
		 */
		private boolean isRemoveEvent(IResourceDelta delta) {
			return delta.getKind() == IResourceDelta.REMOVED;
		}

		/**
		 * Decode the bitmask + enum to work out if this is a project open event
		 *
		 * @param delta
		 * @return True if it is a project open event
		 */
		private boolean isProjectOpenCloseEvent(IResourceDelta delta) {
			return delta.getKind() == IResourceDelta.CHANGED
					&& (delta.getFlags() & IResourceDelta.OPEN) == IResourceDelta.OPEN;
		}

		/**
		 *
		 * @return True if this workspace folder is non-null and has non-empty content
		 */
		private boolean isValid(@Nullable WorkspaceFolder wsFolder) {
			return wsFolder != null && wsFolder.getUri() != null && !wsFolder.getUri().isEmpty();
		}

	}

}