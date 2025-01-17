/*******************************************************************************
 * Copyright (c) 2016, 2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Mickael Istria (Red Hat Inc.) - initial implementation
 *  Miro Spoenemann (TypeFox) - added clientImpl and serverInterface attributes
 *  Alexander Fedorov (ArSysOp) - added parent context to evaluation
 *******************************************************************************/
package org.eclipse.lsp4e;

import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.eclipse.core.expressions.ExpressionConverter;
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.content.IContentType;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.IDocument;
import org.eclipse.lsp4e.enablement.EnablementTester;
import org.eclipse.lsp4e.operations.diagnostics.LSPDiagnosticsToMarkers;
import org.eclipse.lsp4e.server.StreamConnectionProvider;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.statushandlers.StatusManager;
import org.osgi.framework.Bundle;

import com.google.gson.GsonBuilder;

/**
 * This registry aims at providing a good language server connection (as {@link StreamConnectionProvider}
 * for a given input.
 * At the moment, registry content are hardcoded but we'll very soon need a way
 * to contribute to it via plugin.xml (for plugin developers) and from Preferences
 * (for end-users to directly register a new server).
 *
 */
public class LanguageServersRegistry {

	private static final String CONTENT_TYPE_TO_LSP_LAUNCH_PREF_KEY = "contentTypeToLSPLauch"; //$NON-NLS-1$

	private static final String EXTENSION_POINT_ID = LanguageServerPlugin.PLUGIN_ID + ".languageServer"; //$NON-NLS-1$

	private static final String LS_ELEMENT = "server"; //$NON-NLS-1$
	private static final String MAPPING_ELEMENT = "contentTypeMapping"; //$NON-NLS-1$

	private static final String ID_ATTRIBUTE = "id"; //$NON-NLS-1$
	private static final String SINGLETON_ATTRIBUTE = "singleton"; //$NON-NLS-1$
	private static final boolean DEFAULT_SINGLETON = false;
	private static final String LAST_DOCUMENT_DISCONNECTED_TIMEOUT = "lastDocumentDisconnectedTimeout"; //$NON-NLS-1$
	private static final int DEFAULT_LAST_DOCUMENTED_DISCONNECTED_TIEMOUT = 5;
	private static final String CONTENT_TYPE_ATTRIBUTE = "contentType"; //$NON-NLS-1$
	private static final String LANGUAGE_ID_ATTRIBUTE = "languageId"; //$NON-NLS-1$
	private static final String CLASS_ATTRIBUTE = "class"; //$NON-NLS-1$
	private static final String CLIENT_IMPL_ATTRIBUTE = "clientImpl"; //$NON-NLS-1$
	private static final String MAKER_TYPE_ELEMENT = "makerType"; //$NON-NLS-1$
	private static final String MARKER_TYPE_ELEMENT = "markerType"; //$NON-NLS-1$
	private static final String MARKER_ATTR_COMPUTER_ELEMENT = "markerAttributeComputer"; //$NON-NLS-1$
	private static final String CONFIGURE_GSON_ATTRIBUTE = "configureGson"; //$NON-NLS-1$
	private static final String SERVER_INTERFACE_ATTRIBUTE = "serverInterface"; //$NON-NLS-1$
	private static final String LAUNCHER_BUILDER_ATTRIBUTE = "launcherBuilder"; //$NON-NLS-1$
	private static final String LABEL_ATTRIBUTE = "label"; //$NON-NLS-1$
	private static final String ENABLED_WHEN_ATTRIBUTE = "enabledWhen"; //$NON-NLS-1$
	private static final String ENABLED_WHEN_DESC = "description"; //$NON-NLS-1$

	public abstract static class LanguageServerDefinition {
		public final String id;
		public final String label;
		public final boolean isSingleton;
		public final int lastDocumentDisconnectedTimeout;
		public final Map<IContentType, String> languageIdMappings;

		LanguageServerDefinition(String id, String label, boolean isSingleton, int lastDocumentDisconnectedTimeout) {
			this.id = id;
			this.label = label;
			this.isSingleton = isSingleton;
			this.lastDocumentDisconnectedTimeout = lastDocumentDisconnectedTimeout;
			this.languageIdMappings = new ConcurrentHashMap<>();
		}

		public void registerAssociation(IContentType contentType, String languageId) {
			this.languageIdMappings.put(contentType, languageId);
		}

		public abstract StreamConnectionProvider createConnectionProvider();

		public LanguageClientImpl createLanguageClient() {
			return new LanguageClientImpl();
		}

		public Class<? extends LanguageServer> getServerInterface() {
			return LanguageServer.class;
		}

		public <S extends LanguageServer> Launcher.Builder<S> createLauncherBuilder() {
			return new Launcher.Builder<>();
		}

		public Consumer<GsonBuilder> getConfigureGson() {
			return builder -> {};
		}

	}

	static class ExtensionLanguageServerDefinition extends LanguageServerDefinition {
		private final IConfigurationElement extension;

		private Consumer<PublishDiagnosticsParams> getDiagnosticHandler() {
			String serverId = extension.getAttribute(ID_ATTRIBUTE);
			String markerType = extension.getAttribute(MARKER_TYPE_ELEMENT);
			if (markerType == null) {
				markerType = extension.getAttribute(MAKER_TYPE_ELEMENT);
				if (markerType != null) {
					LanguageServerPlugin.logWarning("Please use the property " + MARKER_TYPE_ELEMENT+ ". The legacy property "+ MAKER_TYPE_ELEMENT + " will be removed.", null); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
			}
			IMarkerAttributeComputer markerAttributeComputerElement = null;
			try {
				String markerAttributeComputer = extension.getAttribute(MARKER_ATTR_COMPUTER_ELEMENT);
				if (markerAttributeComputer != null && !markerAttributeComputer.isEmpty()) {
					markerAttributeComputerElement = (IMarkerAttributeComputer) extension.createExecutableExtension(MARKER_ATTR_COMPUTER_ELEMENT);
				}
			} catch (CoreException e) {
				LanguageServerPlugin.logError(e);
			}
			return new LSPDiagnosticsToMarkers(serverId, markerType, markerAttributeComputerElement);
		}

		private static boolean getIsSingleton(IConfigurationElement element) {
			return Boolean.parseBoolean(element.getAttribute(SINGLETON_ATTRIBUTE));
		}

		private static int getLastDocumentDisconnectedTimeout(IConfigurationElement element) {
			String lastDocumentisconnectedTiemoutAttribute = element.getAttribute(LAST_DOCUMENT_DISCONNECTED_TIMEOUT);
			return lastDocumentisconnectedTiemoutAttribute == null ? DEFAULT_LAST_DOCUMENTED_DISCONNECTED_TIEMOUT : Integer.parseInt(lastDocumentisconnectedTiemoutAttribute);
		}

		public ExtensionLanguageServerDefinition(IConfigurationElement element) {
			super(element.getAttribute(ID_ATTRIBUTE), element.getAttribute(LABEL_ATTRIBUTE), getIsSingleton(element), getLastDocumentDisconnectedTimeout(element));
			this.extension = element;
		}

		@Override
		public StreamConnectionProvider createConnectionProvider() {
			try {
				return (StreamConnectionProvider) extension.createExecutableExtension(CLASS_ATTRIBUTE);
			} catch (CoreException e) {
				StatusManager.getManager().handle(e, LanguageServerPlugin.PLUGIN_ID);
				throw new RuntimeException(
						"Exception occurred while creating an instance of the stream connection provider", e); //$NON-NLS-1$
			}
		}

		@Override
		public LanguageClientImpl createLanguageClient() {
			LanguageClientImpl languageClient = null;
			String clientImpl = extension.getAttribute(CLIENT_IMPL_ATTRIBUTE);
			if (clientImpl != null && !clientImpl.isEmpty()) {
				try {
					languageClient = (LanguageClientImpl) extension.createExecutableExtension(CLIENT_IMPL_ATTRIBUTE);
				} catch (CoreException e) {
					StatusManager.getManager().handle(e, LanguageServerPlugin.PLUGIN_ID);
				}
			}
			if (languageClient == null) {
				languageClient = super.createLanguageClient();
			}
			languageClient.setDiagnosticsConsumer(getDiagnosticHandler());
			return languageClient;
		}

		@SuppressWarnings("unchecked")
		@Override
		public Class<? extends LanguageServer> getServerInterface() {
			String serverInterface = extension.getAttribute(SERVER_INTERFACE_ATTRIBUTE);
			if (serverInterface != null && !serverInterface.isEmpty()) {
				Bundle bundle = Platform.getBundle(extension.getContributor().getName());
				if (bundle != null) {
					try {
						return (Class<? extends LanguageServer>) bundle.loadClass(serverInterface);
					} catch (ClassNotFoundException exception) {
						StatusManager.getManager().handle(new Status(IStatus.ERROR, LanguageServerPlugin.PLUGIN_ID,
								exception.getMessage(), exception));
					}
				}
			}
			return super.getServerInterface();
		}

		@SuppressWarnings("unchecked")
		@Override
		public Consumer<GsonBuilder> getConfigureGson() {
			String configGson = extension.getAttribute(CONFIGURE_GSON_ATTRIBUTE);
			if (configGson != null && !configGson.isEmpty()) {
				Bundle bundle = Platform.getBundle(extension.getContributor().getName());
				if (bundle != null) {
					try {
						return (Consumer<GsonBuilder>) extension.createExecutableExtension(CONFIGURE_GSON_ATTRIBUTE);
					} catch (ClassNotFoundException exception) {
						StatusManager.getManager().handle(new Status(IStatus.ERROR, LanguageServerPlugin.PLUGIN_ID,
								exception.getMessage(), exception));
					}
				}
			}
			return super.getConfigureGson();
		}

		@SuppressWarnings("unchecked")
		@Override
		public <S extends LanguageServer> Launcher.Builder<S> createLauncherBuilder() {
			String launcherSupplier = extension.getAttribute(LAUNCHER_BUILDER_ATTRIBUTE);
			if (launcherSupplier != null && !launcherSupplier.isEmpty()) {
				try {
					return (Launcher.Builder<S>) extension.createExecutableExtension(LAUNCHER_BUILDER_ATTRIBUTE);
				} catch (CoreException e) {
					StatusManager.getManager().handle(e, LanguageServerPlugin.PLUGIN_ID);
				}
			}
			return super.createLauncherBuilder();
		}

	}

	static class LaunchConfigurationLanguageServerDefinition extends LanguageServerDefinition {
		final ILaunchConfiguration launchConfiguration;
		final Set<String> launchModes;

		public LaunchConfigurationLanguageServerDefinition(ILaunchConfiguration launchConfiguration,
				Set<String> launchModes) {
			super(launchConfiguration.getName(), launchConfiguration.getName(), DEFAULT_SINGLETON, DEFAULT_LAST_DOCUMENTED_DISCONNECTED_TIEMOUT);
			this.launchConfiguration = launchConfiguration;
			this.launchModes = launchModes;
		}

		@Override
		public StreamConnectionProvider createConnectionProvider() {
			return new LaunchConfigurationStreamProvider(this.launchConfiguration, launchModes);
		}

		@Override
		public LanguageClientImpl createLanguageClient() {
			LanguageClientImpl client = super.createLanguageClient();
			client.setDiagnosticsConsumer(new LSPDiagnosticsToMarkers(id, null, null));
			return client;
		}
	}

	private static final class LazyHolder {
		static final LanguageServersRegistry INSTANCE = new LanguageServersRegistry();
	}
	public static LanguageServersRegistry getInstance() {
		return LazyHolder.INSTANCE;
	}

	private final List<ContentTypeToLanguageServerDefinition> connections = new ArrayList<>();
	private final IPreferenceStore preferenceStore;

	private LanguageServersRegistry() {
		this.preferenceStore = LanguageServerPlugin.getDefault().getPreferenceStore();
		initialize();
	}

	private void initialize() {
		String prefs = preferenceStore.getString(CONTENT_TYPE_TO_LSP_LAUNCH_PREF_KEY);
		if (!prefs.isEmpty()) {
			String[] entries = prefs.split(","); //$NON-NLS-1$
			for (String entry : entries) {
				ContentTypeToLSPLaunchConfigEntry mapping = ContentTypeToLSPLaunchConfigEntry.readFromPreference(entry);
				if (mapping != null) {
					connections.add(mapping);
				}
			}
		}

		final var servers = new HashMap<String, LanguageServerDefinition>();
		final var contentTypes = new ArrayList<ContentTypeMapping>();
		for (IConfigurationElement extension : Platform.getExtensionRegistry().getConfigurationElementsFor(EXTENSION_POINT_ID)) {
			String id = extension.getAttribute(ID_ATTRIBUTE);
			if (id != null && !id.isEmpty()) {
				if (extension.getName().equals(LS_ELEMENT)) {
					servers.put(id, new ExtensionLanguageServerDefinition(extension));
				} else if (extension.getName().equals(MAPPING_ELEMENT)) {
					IContentType contentType = Platform.getContentTypeManager().getContentType(extension.getAttribute(CONTENT_TYPE_ATTRIBUTE));
					String languageId = extension.getAttribute(LANGUAGE_ID_ATTRIBUTE);
					EnablementTester expression = null;
					if (extension.getChildren(ENABLED_WHEN_ATTRIBUTE).length > 0) {
						IConfigurationElement[] enabledWhenElements = extension.getChildren(ENABLED_WHEN_ATTRIBUTE);
						if (enabledWhenElements.length == 1) {
							IConfigurationElement enabledWhen = enabledWhenElements[0];
							IConfigurationElement[] enabledWhenChildren = enabledWhen.getChildren();
							if (enabledWhenChildren.length == 1) {
								try {
									String description = enabledWhen.getAttribute(ENABLED_WHEN_DESC);
									expression = new EnablementTester(this::evaluationContext,
											castNonNull(ExpressionConverter.getDefault().perform(enabledWhenChildren[0])),
											description);
								} catch (CoreException e) {
									LanguageServerPlugin.logWarning(e.getMessage(), e);
								}
							}
						}
					}
					if (contentType != null) {
						contentTypes.add(new ContentTypeMapping(contentType, id, languageId, expression));
					}
				}
			}
		}

		for (ContentTypeMapping mapping : contentTypes) {
			LanguageServerDefinition lsDefinition = servers.get(mapping.id);
			if (lsDefinition != null) {
				registerAssociation(mapping.contentType, lsDefinition, mapping.languageId, mapping.enablement);
			} else {
				LanguageServerPlugin.logWarning("server '" + mapping.id + "' not available", null); //$NON-NLS-1$ //$NON-NLS-2$
			}
		}
	}

	private @Nullable IEvaluationContext evaluationContext() {
		final var handlerService = PlatformUI.getWorkbench().getService(IHandlerService.class);
		return handlerService == null
				? null
				: handlerService.getCurrentState();
	}

	private void persistContentTypeToLaunchConfigurationMapping() {
		final var builder = new StringBuilder();
		for (ContentTypeToLSPLaunchConfigEntry entry : getContentTypeToLSPLaunches()) {
			entry.appendPreferenceTo(builder);
			builder.append(',');
		}
		if (builder.length() > 0) {
			builder.deleteCharAt(builder.length() - 1);
		}
		this.preferenceStore.setValue(CONTENT_TYPE_TO_LSP_LAUNCH_PREF_KEY, builder.toString());
		if (this.preferenceStore instanceof IPersistentPreferenceStore persistentStore) {
			try {
				persistentStore.save();
			} catch (IOException e) {
				LanguageServerPlugin.logError(e);
			}
		}
	}

	/**
	 * @param contentType
	 * @return the {@link LanguageServerDefinition}s <strong>directly</strong> associated to the given content-type.
	 * This does <strong>not</strong> include the one that match transitively as per content-type hierarchy
	 */
	List<ContentTypeToLanguageServerDefinition> findProviderFor(final IContentType contentType) {
		return connections.stream()
			.filter(entry -> entry.getKey().equals(contentType))
			.sorted((mapping1, mapping2) -> {
				// this sort should make that the content-type hierarchy is respected
				// and the most specialized content-type are placed before the more generic ones
				if (mapping1.getKey().isKindOf(mapping2.getKey())) {
					return -1;
				} else if (mapping2.getKey().isKindOf(mapping1.getKey())) {
					return +1;
				}
				// TODO support "priority" attribute, but it's not made public
				return mapping1.getKey().getId().compareTo(mapping2.getKey().getId());
			}).toList();
	}

	public void registerAssociation(IContentType contentType, ILaunchConfiguration launchConfig, Set<String> launchMode) {
		final var mapping = new ContentTypeToLSPLaunchConfigEntry(contentType, launchConfig,
				launchMode);
		connections.add(mapping);
		persistContentTypeToLaunchConfigurationMapping();
	}

	public void registerAssociation(IContentType contentType, LanguageServerDefinition serverDefinition,
			@Nullable String languageId, @Nullable EnablementTester enablement) {
		if (languageId != null) {
			serverDefinition.registerAssociation(contentType, languageId);
		}

		connections.add(new ContentTypeToLanguageServerDefinition(contentType, serverDefinition, enablement));
	}

	public void setAssociations(List<ContentTypeToLSPLaunchConfigEntry> wc) {
		this.connections.removeIf(ContentTypeToLSPLaunchConfigEntry.class::isInstance);
		this.connections.addAll(wc);
		persistContentTypeToLaunchConfigurationMapping();
	}

	public List<ContentTypeToLSPLaunchConfigEntry> getContentTypeToLSPLaunches() {
		return this.connections.stream().filter(ContentTypeToLSPLaunchConfigEntry.class::isInstance).map(ContentTypeToLSPLaunchConfigEntry.class::cast).toList();
	}

	public List<ContentTypeToLanguageServerDefinition> getContentTypeToLSPExtensions() {
		return this.connections.stream().filter(mapping -> mapping.getValue() instanceof ExtensionLanguageServerDefinition).toList();
	}

	public @Nullable LanguageServerDefinition getDefinition(String languageServerId) {
		for (ContentTypeToLanguageServerDefinition mapping : this.connections) {
			if (mapping.getValue().id.equals(languageServerId)) {
				return mapping.getValue();
			}
		}
		return null;
	}

	/**
	 * internal class to capture content-type mappings for language servers
	 */
	private static final class ContentTypeMapping {

		public final String id;
		public final IContentType contentType;
		public final @Nullable String languageId;
		public final @Nullable EnablementTester enablement;

		public ContentTypeMapping(IContentType contentType, String id, @Nullable String languageId,
				@Nullable EnablementTester enablement) {
			this.contentType = contentType;
			this.id = id;
			this.languageId = languageId;
			this.enablement = enablement;
		}

	}

	/**
	 * @param file
	 * @param serverDefinition
	 * @return whether the given serverDefinition is suitable for the file
	 */
	public boolean matches(IFile file, LanguageServerDefinition serverDefinition) {
		return getAvailableLSFor(LSPEclipseUtils.getFileContentTypes(file), file.getLocationURI()).contains(serverDefinition);
	}

	/**
	 * @param document
	 * @param serverDefinition
	 * @return whether the given serverDefinition is suitable for the file
	 */
	public boolean matches(IDocument document, LanguageServerDefinition serverDefinition) {
		return getAvailableLSFor(LSPEclipseUtils.getDocumentContentTypes(document), LSPEclipseUtils.toUri(document)).contains(serverDefinition);
	}

	public boolean canUseLanguageServer(IEditorInput editorInput) {
		return !getAvailableLSFor(List.of(Platform.getContentTypeManager().findContentTypesFor(editorInput.getName())),
				LSPEclipseUtils.toUri(editorInput)).isEmpty();
	}

	public boolean canUseLanguageServer(IDocument document) {
		List<IContentType> contentTypes = LSPEclipseUtils.getDocumentContentTypes(document);

		if (contentTypes.isEmpty()) {
			return false;
		}

		return !getAvailableLSFor(contentTypes, LSPEclipseUtils.toUri(document)).isEmpty();
	}

	public boolean canUseLanguageServer(IFile file) {
		return !getAvailableLSFor(LSPEclipseUtils.getFileContentTypes(file), file.getLocationURI()).isEmpty();
	}

	/**
	 *
	 * @param contentTypes content-types to check against LS registry. Base types are checked too.
	 * @return definitions that can support the following content-types
	 */
	private Set<LanguageServerDefinition> getAvailableLSFor(Collection<IContentType> contentTypes, @Nullable URI uri) {
		final var res = new HashSet<LanguageServerDefinition>();
		contentTypes = expandToSuperTypes(contentTypes);
		for (ContentTypeToLanguageServerDefinition mapping : this.connections) {
			if (mapping.isEnabled(uri) && contentTypes.contains(mapping.getKey())) {
				res.add(mapping.getValue());
			}
		}
		return res;
	}

	private Collection<IContentType> expandToSuperTypes(Collection<IContentType> contentTypes) {
		final var res = new ArrayList<IContentType>(contentTypes);
		for (int i = 0; i < res.size(); i++) {
			IContentType current = res.get(i);
			IContentType base = current.getBaseType();
			if (base != null && !res.contains(base)) {
				res.add(base);
			}
		}
		return res;
	}

}
