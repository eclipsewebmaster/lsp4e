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
 *  Lucas Bullen (Red Hat Inc.) - [Bug 517428] Requests sent before initialization
 *  Martin Lippert (Pivotal Inc.) - bug 531167, 531670, 536258
 *  Kris De Volder (Pivotal Inc.) - Get language servers by capability predicate.
 *******************************************************************************/
package org.eclipse.lsp4e;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.content.IContentDescription;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.LanguageServersRegistry.LanguageServerDefinition;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.part.FileEditorInput;

/**
 * The entry-point to retrieve a Language Server Wrapper for a given
 * resource/project. Deals with instantiations and caching of underlying
 * {@link LanguageServerWrapper}.
 *
 */
public class LanguageServiceAccessor {

	private LanguageServiceAccessor() {
		// this class shouldn't be instantiated
	}

	private static final Set<LanguageServerWrapper> startedServers = new CopyOnWriteArraySet<>();
	private static final Map<StreamConnectionProvider, LanguageServerDefinition> providersToLSDefinitions = new HashMap<>();

	/**
	 * This is meant for test code to clear state that might have leaked from other
	 * tests. It isn't meant to be used in production code.
	 */
	public static void clearStartedServers() {
		startedServers.removeIf(server -> {
			server.stop();
			server.stopDispatcher();
			return true;
		});
	}

	/**
	 * A bean storing association of a Document/File with a language server wrapper.
	 *
	 * @deprecated use {@link LanguageServers#forDocument(IDocument)} instead.
	 */
	@Deprecated(forRemoval = true)
	public static class LSPDocumentInfo {

		private final URI fileUri;
		private final IDocument document;
		private final LanguageServerWrapper wrapper;

		private LSPDocumentInfo(URI fileUri, IDocument document, LanguageServerWrapper wrapper) {
			this.fileUri = fileUri;
			this.document = document;
			this.wrapper = wrapper;
		}

		public IDocument getDocument() {
			return this.document;
		}

		/**
		 * TODO consider directly returning a {@link TextDocumentIdentifier}
		 *
		 * @return the file URI
		 */
		public URI getFileUri() {
			return this.fileUri;
		}

		public LanguageServerWrapper getLanguageServerWrapper() {
			return wrapper;
		}

		public int getVersion() {
			return wrapper.getTextDocumentVersion(fileUri);
		}

		public @Nullable ServerCapabilities getCapabilites() {
			return this.wrapper.getServerCapabilities();
		}

		public boolean isActive() {
			return this.wrapper.isActive();
		}
	}

	public static void disableLanguageServerContentType(
			ContentTypeToLanguageServerDefinition contentTypeToLSDefinition) {
		Optional<LanguageServerWrapper> result = startedServers.stream()
				.filter(server -> server.serverDefinition.equals(contentTypeToLSDefinition.getValue())).findFirst();
		if (result.isPresent()) {
			IContentType contentType = contentTypeToLSDefinition.getKey();
			result.get().disconnectContentType(contentType);
		}
	}

	public static void enableLanguageServerContentType(
			final ContentTypeToLanguageServerDefinition contentTypeToLSDefinition, final IEditorReference[] editors) {
		final IContentType contentType = contentTypeToLSDefinition.getKey();
		final LanguageServerDefinition lsDefinition = contentTypeToLSDefinition.getValue();
		for (final IEditorReference editor : editors) {
			try {
				if (editor.getEditorInput() instanceof FileEditorInput editorInput) {
					final IFile editorFile = editorInput.getFile();
					final IContentDescription contentDesc = editorFile.getContentDescription();
					if (contentDesc == null)
						continue;
					if (contentType.equals(contentDesc.getContentType())
							&& contentTypeToLSDefinition.isEnabled(editorFile.getLocationURI())) {
						getInitializedLanguageServer(editorFile, lsDefinition, capabilities -> true);
					}
				}
			} catch (Exception e) {
				LanguageServerPlugin.logError(e);
			}
		}
	}

	/**
	 * Get the requested language server instance for the given file. Starts the
	 * language server if not already started.
	 *
	 * @param resource
	 * @param lsDefinition
	 * @param capabilitiesPredicate
	 *            a predicate to check capabilities
	 * @return a LanguageServer for the given file, which is defined with provided
	 *         server ID and conforms to specified request. If
	 *         {@code capabilitesPredicate} does not test positive for the server's
	 *         capabilities, {@code null} is returned.
	 */
	private static CompletableFuture<@Nullable LanguageServer> getInitializedLanguageServer(IResource resource,
			LanguageServerDefinition lsDefinition, @Nullable Predicate<ServerCapabilities> capabilitiesPredicate) {
		LanguageServerWrapper wrapper = getLSWrapper(resource.getProject(), lsDefinition, resource.getFullPath());
		return capabilitiesComplyAsync(wrapper, capabilitiesPredicate)
				.thenCompose(complies -> complies ? wrapper.getInitializedServer() : null);
	}

	/**
	 * <b>IMPORTANT:</b> If the server isn't yet initialized this method will be
	 * blocking for up to 10 seconds!
	 *
	 * Checks if the given {@code wrapper}'s capabilities comply with the given
	 * {@code capabilitiesPredicate}.
	 *
	 * @param wrapper
	 *            the server that's capabilities are tested with
	 *            {@code capabilitiesPredicate}
	 * @param capabilitiesPredicate
	 *            predicate testing the capabilities of {@code wrapper}.
	 * @return The result of applying the capabilities of {@code wrapper} to
	 *         {@code capabilitiesPredicate}, or {@code true} if
	 *         {@code capabilitiesPredicate == null} or
	 *         {@code wrapper.getServerCapabilities() == null}
	 */
	private static boolean capabilitiesComply(LanguageServerWrapper wrapper,
			@Nullable Predicate<ServerCapabilities> capabilitiesPredicate) {
		return capabilitiesPredicate == null
				/*
				 * next null check is workaround for https://github.com/TypeFox/ls-api/issues/47
				 */
				|| wrapper.getServerCapabilities() == null
				|| capabilitiesPredicate.test(castNonNull(wrapper.getServerCapabilities()));
	}

	/**
	 * Asynchronously checks if the given {@code wrapper}'s capabilities comply with the given
	 * {@code capabilitiesPredicate}.
	 *
	 * @param wrapper
	 *            the server that's capabilities are tested with
	 *            {@code capabilitiesPredicate}
	 * @param capabilitiesPredicate
	 *            predicate testing the capabilities of {@code wrapper}.
	 * @return The result of applying the capabilities of {@code wrapper} to
	 *         {@code capabilitiesPredicate}, or {@code true} if
	 *         {@code capabilitiesPredicate == null}
	 */
	private static CompletableFuture<Boolean> capabilitiesComplyAsync(final LanguageServerWrapper wrapper,
			final @Nullable Predicate<ServerCapabilities> capabilitiesPredicate) {
		return capabilitiesPredicate == null //
				? CompletableFuture.completedFuture(true) //
				: wrapper.getServerCapabilitiesAsync().thenApply(capabilitiesPredicate::test);
	}

	/**
	 * TODO we need a similar method for generic IDocument (enabling non-IFiles)
	 *
	 * @param file
	 * @param request
	 * @return the matching LS wrappers
	 * @noreference This method is currently internal and should only be referenced
	 *              for testing
	 */
	public static List<LanguageServerWrapper> getLSWrappers(final IFile file,
			final @Nullable Predicate<ServerCapabilities> request) {
		final var project = file.getProject();
		if (project == null) {
			return Collections.emptyList();
		}

		final var lsRegistry = LanguageServersRegistry.getInstance();
		final var fileURI = file.getLocationURI();
		if (fileURI == null) {
			return Collections.emptyList();
		}

		List<LanguageServerWrapper> wrappers = getStartedWrappers(file.getProject(), request, true);
		wrappers.removeIf(
				wrapper -> !wrapper.isConnectedTo(fileURI) || !lsRegistry.matches(file, wrapper.serverDefinition));

		// look for running language servers via content-type
		final var directContentTypes = LSPEclipseUtils.getFileContentTypes(file);
		final var contentTypesToProcess = new ArrayDeque<IContentType>(directContentTypes);
		final var processedContentTypes = new HashSet<IContentType>(directContentTypes.size());

		while (!contentTypesToProcess.isEmpty()) {
			final var contentType = contentTypesToProcess.poll();
			if (contentType == null || processedContentTypes.contains(contentType)) {
				continue;
			}

			for (final ContentTypeToLanguageServerDefinition mapping : lsRegistry.findProviderFor(contentType)) {
				if (!mapping.isEnabled(fileURI)) {
					continue;
				}
				final LanguageServerDefinition serverDefinition = mapping.getValue();
				final var wrapper = getLSWrapper(project, serverDefinition, file.getFullPath());
				if (!wrappers.contains(wrapper) && capabilitiesComply(wrapper, request)) {
					wrappers.add(wrapper);
				}
			}

			if (contentType.getBaseType() != null) {
				contentTypesToProcess.add(contentType.getBaseType());
			}
			processedContentTypes.add(contentType);
		}
		return wrappers;
	}

	protected static Collection<LanguageServerWrapper> getLSWrappers(final IDocument document) {
		final URI uri = LSPEclipseUtils.toUri(document);
		if (uri == null) {
			return Collections.emptyList();
		}

		final var lsRegistry = LanguageServersRegistry.getInstance();

		// look for already started compatible servers suitable for the given document
		final Predicate<LanguageServerWrapper> selectServersForDocument = wrapper -> {
			try {
				return wrapper.isConnectedTo(uri)
						|| (lsRegistry.matches(document, wrapper.serverDefinition) && wrapper.canOperate(document));
			} catch (Exception ex) {
				LanguageServerPlugin.logError(ex);
				return false;
			}
		};
		@NonNull
		final LinkedHashSet<LanguageServerWrapper> res = startedServers.stream().filter(selectServersForDocument)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		// look for running language servers via content-type
		final var directContentTypes = LSPEclipseUtils.getDocumentContentTypes(document);
		final var contentTypesToProcess = new ArrayDeque<IContentType>(directContentTypes);
		final var processedContentTypes = new HashSet<IContentType>(directContentTypes.size());
		final var file = LSPEclipseUtils.getFile(document);

		while (!contentTypesToProcess.isEmpty()) {
			final var contentType = contentTypesToProcess.poll();
			if (contentType == null || processedContentTypes.contains(contentType)) {
				continue;
			}

			for (final ContentTypeToLanguageServerDefinition mapping : lsRegistry.findProviderFor(contentType)) {
				if (!mapping.isEnabled(uri)) {
					continue;
				}
				final LanguageServerDefinition serverDefinition = mapping.getValue();
				final Predicate<LanguageServerWrapper> selectServersWithEqualDefinition = wrapper -> wrapper.serverDefinition
						.equals(serverDefinition);
				if (res.stream().anyMatch(selectServersWithEqualDefinition)) {
					// we already found a compatible LS with this definition
					continue;
				}

				synchronized (startedServers) {
					// check again while holding the write lock
					startedServers.stream().filter(selectServersForDocument).forEach(res::add);
					if (res.stream().anyMatch(selectServersWithEqualDefinition)) {
						// we already found a compatible LS with this definition
						continue;
					}

					final var fileProject = file != null ? file.getProject() : null;
					final var path = uri.getPath() != null ? new Path(uri.getPath()) : null;
					final var wrapper = fileProject != null //
							? new LanguageServerWrapper(fileProject, serverDefinition)
							: new LanguageServerWrapper(serverDefinition, path);
					startedServers.add(wrapper);
					res.add(wrapper);
				}
			}

			if (contentType.getBaseType() != null) {
				contentTypesToProcess.add(contentType.getBaseType());
			}
			processedContentTypes.add(contentType);
		}
		return res;
	}

	/**
	 * Return existing {@link LanguageServerWrapper} for the given definition. If
	 * not found, create a new one with the given definition.
	 *
	 * @param project
	 * @param serverDefinition
	 * @return a new or existing {@link LanguageServerWrapper} for the given
	 *         definition.
	 */
	public static LanguageServerWrapper getLSWrapper(@Nullable IProject project,
			LanguageServerDefinition serverDefinition) {
		return getLSWrapper(project, serverDefinition, null);
	}

	private static LanguageServerWrapper getLSWrapper(@Nullable IProject project,
			LanguageServerDefinition serverDefinition, @Nullable IPath initialPath) {

		final Predicate<LanguageServerWrapper> serverSelector = wrapper -> wrapper.canOperate(project)
				&& wrapper.serverDefinition.equals(serverDefinition);

		var matchingServer = startedServers.stream().filter(serverSelector).findFirst();
		if (matchingServer.isPresent()) {
			return matchingServer.get();
		}

		synchronized (startedServers) {
			// check again while holding the write lock
			matchingServer = startedServers.stream().filter(serverSelector).findFirst();
			if (matchingServer.isPresent()) {
				return matchingServer.get();
			}

			final var wrapper = project != null //
					? new LanguageServerWrapper(project, serverDefinition)
					: new LanguageServerWrapper(serverDefinition, initialPath);
			wrapper.start();

			startedServers.add(wrapper);
			return wrapper;
		}
	}

	public static LanguageServerWrapper startLanguageServer(LanguageServerDefinition serverDefinition) {
		synchronized (startedServers) {
			LanguageServerWrapper wrapper = startedServers.stream().filter(w -> w.serverDefinition == serverDefinition)
					.findFirst().orElseGet(() -> {
						final var w = new LanguageServerWrapper(serverDefinition, null);
						startedServers.add(w);
						return w;
					});
			if (!wrapper.isActive()) {
				wrapper.start();
			}
			return wrapper;
		}
	}

	/**
	 * <b>IMPORTANT:</b> If the server isn't yet initialized this method will be
	 * blocking for up to 10 seconds per server!
	 */
	public static List<LanguageServerWrapper> getStartedWrappers(@Nullable Predicate<ServerCapabilities> request,
			boolean onlyActiveLS) {
		return getStartedWrappers(w -> true, request, onlyActiveLS);
	}

	/**
	 * <b>IMPORTANT:</b> If the server isn't yet initialized this method will be
	 * blocking for up to 10 seconds per server!
	 */
	public static List<LanguageServerWrapper> getStartedWrappers(@Nullable IProject project,
			@Nullable Predicate<ServerCapabilities> request, boolean onlyActiveLS) {
		return getStartedWrappers(w -> w.canOperate(project), request, onlyActiveLS);
	}

	/**
	 * <b>IMPORTANT:</b> If the server isn't yet initialized this method will be
	 * blocking for up to 10 seconds per server!
	 */
	public static List<LanguageServerWrapper> getStartedWrappers(IDocument document,
			Predicate<ServerCapabilities> request, boolean onlyActiveLS) {
		return getStartedWrappers(w -> w.canOperate(document), request, onlyActiveLS);
	}

	private static List<LanguageServerWrapper> getStartedWrappers(Predicate<LanguageServerWrapper> canOperatePredicate,
			@Nullable Predicate<ServerCapabilities> capabilitiesPredicate, boolean onlyActiveLS) {
		final var result = new ArrayList<LanguageServerWrapper>();
		for (LanguageServerWrapper wrapper : startedServers) {
			if ((!onlyActiveLS || wrapper.isActive()) && canOperatePredicate.test(wrapper)
					&& capabilitiesComply(wrapper, capabilitiesPredicate)) {
				result.add(wrapper);
			}
		}
		return result;
	}

	public static Map<LanguageServerDefinition, CompletableFuture<@Nullable LanguageServerWrapper>> getStartedWrappersAsync(
			final @Nullable Predicate<ServerCapabilities> request, final boolean onlyActiveLS) {
		return getStartedWrappersAsync(w -> true, request, onlyActiveLS);
	}

	public static Map<LanguageServerDefinition, CompletableFuture<@Nullable LanguageServerWrapper>> getStartedWrappersAsync(
			final @Nullable IProject project, final @Nullable Predicate<ServerCapabilities> request,
			final boolean onlyActiveLS) {
		return getStartedWrappersAsync(w -> w.canOperate(project), request, onlyActiveLS);
	}

	public static Map<LanguageServerDefinition, CompletableFuture<@Nullable LanguageServerWrapper>> getStartedWrappersAsync(final IDocument document,
			final Predicate<ServerCapabilities> request, final boolean onlyActiveLS) {
		return getStartedWrappersAsync(w -> w.canOperate(document), request, onlyActiveLS);
	}

	private static Map<LanguageServerDefinition, CompletableFuture<@Nullable LanguageServerWrapper>> getStartedWrappersAsync(
			final Predicate<LanguageServerWrapper> canOperatePredicate,
			final @Nullable Predicate<ServerCapabilities> capabilitiesPredicate, final boolean onlyActiveLS) {
		final Map<LanguageServerDefinition, CompletableFuture<@Nullable LanguageServerWrapper>> result = new LinkedHashMap<>();
		for (final LanguageServerWrapper wrapper : startedServers) {
			if ((!onlyActiveLS || wrapper.isActive()) && canOperatePredicate.test(wrapper)) {
				result.put(wrapper.serverDefinition, capabilitiesComplyAsync(wrapper, capabilitiesPredicate)
						.thenApply(complies -> complies ? wrapper : null));
			}
		}
		return result;
	}

	/**
	 * Returns {@code true} if there are running language servers satisfying a
	 * capability predicate. This does not start any matching language servers.
	 *
	 * @param request
	 * @return {@code true} if there are running language servers satisfying a
	 *         capability predicate
	 */
	public static boolean hasActiveLanguageServers(Predicate<ServerCapabilities> request) {
		return !getLanguageServers(request).isEmpty();
	}

	public static boolean hasActiveLanguageServers(@Nullable IFile file, Predicate<ServerCapabilities> request) {
		final IProject project = file != null ? file.getProject() : null;
		return !getLanguageServers(project, request).isEmpty();
	}

	public static boolean hasActiveLanguageServers(IDocument document, Predicate<ServerCapabilities> request) {
		return !getLanguageServers(document, request).isEmpty();
	}

	/**
	 * Gets list of LS initialized for any project
	 *
	 * @param onlyActiveLS
	 *            true if this method should return only the already running
	 *            language servers, otherwise previously started language servers
	 *            will be re-activated
	 * @return list of Language Servers
	 */
	private static List<LanguageServer> getLanguageServers(Predicate<ServerCapabilities> capabilitiesPredicate) {
		List<LanguageServerWrapper> wrappers = getStartedWrappers(capabilitiesPredicate, true);
		return getLanguageServersFromWrappers(wrappers);
	}

	/**
	 * Gets list of LS initialized for given project
	 *
	 * @param onlyActiveLS
	 *            true if this method should return only the already running
	 *            language servers, otherwise previously started language servers
	 *            will be re-activated
	 * @return list of Language Servers
	 */
	private static List<LanguageServer> getLanguageServers(@Nullable IProject project,
			Predicate<ServerCapabilities> capabilitiesPredicate) {
		List<LanguageServerWrapper> wrappers = getStartedWrappers(project, capabilitiesPredicate, true);
		return getLanguageServersFromWrappers(wrappers);
	}

	/**
	 * Gets list of LS initialized for given document
	 *
	 * @param onlyActiveLS
	 *            true if this method should return only the already running
	 *            language servers, otherwise previously started language servers
	 *            will be re-activated
	 * @return list of Language Servers
	 */
	private static List<LanguageServer> getLanguageServers(IDocument document,
			Predicate<ServerCapabilities> capabilitiesPredicate) {
		List<LanguageServerWrapper> wrappers = getStartedWrappers(document, capabilitiesPredicate, true);
		return getLanguageServersFromWrappers(wrappers);
	}

	private static List<LanguageServer> getLanguageServersFromWrappers(List<LanguageServerWrapper> wrappers) {
		final var servers = new ArrayList<LanguageServer>(wrappers.size());
		for (LanguageServerWrapper wrapper : wrappers) {
			@Nullable
			LanguageServer server = wrapper.getServer();
			if (server != null) {
				servers.add(server);
			}
		}
		return servers;
	}

	protected static @Nullable LanguageServerDefinition getLSDefinition(StreamConnectionProvider provider) {
		return providersToLSDefinitions.get(provider);
	}

	/**
	 * @deprecated use {@link LanguageServers#forDocument(IDocument)} instead.
	 */
	@Deprecated(forRemoval = true)
	public static List<LSPDocumentInfo> getLSPDocumentInfosFor(IDocument document,
			Predicate<ServerCapabilities> capabilityRequest) {
		URI fileUri = LSPEclipseUtils.toUri(document);
		final var res = new ArrayList<LSPDocumentInfo>();
		if (fileUri != null) {
			try {
				getLSWrappers(document).stream() //
						.filter(wrapper -> wrapper.getServerCapabilities() == null
								|| capabilityRequest.test(castNonNull(wrapper.getServerCapabilities())))
						.forEach(wrapper -> {
							wrapper.connectDocument(document);
							res.add(new LSPDocumentInfo(fileUri, document, wrapper));
						});
			} catch (final Exception e) {
				LanguageServerPlugin.logError(e);
			}
		}
		return res;
	}

	static void shutdownAllDispatchers() {
		startedServers.forEach(LanguageServerWrapper::stopDispatcher);
	}
}
