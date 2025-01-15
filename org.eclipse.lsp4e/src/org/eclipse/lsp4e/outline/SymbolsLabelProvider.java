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
 *******************************************************************************/
package org.eclipse.lsp4e.outline;

import static org.eclipse.lsp4e.LSPEclipseUtils.findResourceFor;
import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNullable;

import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.viewers.DecorationOverlayIcon;
import org.eclipse.jface.viewers.DelegatingStyledCellLabelProvider.IStyledLabelProvider;
import org.eclipse.jface.viewers.IDecoration;
import org.eclipse.jface.viewers.ILabelProviderListener;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.LabelProviderChangedEvent;
import org.eclipse.jface.viewers.StyledString;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.internal.StyleUtil;
import org.eclipse.lsp4e.outline.SymbolsModel.DocumentSymbolWithURI;
import org.eclipse.lsp4e.ui.LSPImages;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.swt.graphics.Image;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.internal.progress.ProgressManager;
import org.eclipse.ui.navigator.ICommonContentExtensionSite;
import org.eclipse.ui.navigator.ICommonLabelProvider;
import org.eclipse.ui.progress.PendingUpdateAdapter;

import com.google.common.collect.RangeMap;
import com.google.common.collect.TreeRangeMap;

public class SymbolsLabelProvider extends LabelProvider
		implements ICommonLabelProvider, IStyledLabelProvider, IPreferenceChangeListener {

	private final Map<IResource, RangeMap<Integer, Integer>> severities = new HashMap<>();
	private final IResourceChangeListener listener = e -> {
		try {
			IResourceDelta delta = e.getDelta();
			if (delta != null) {
				delta.accept(d -> {
					if (d.getMarkerDeltas().length > 0) {
						severities.remove(d.getResource());
					}
					return true;
				});
			}
		} catch (CoreException ex) {
			LanguageServerPlugin.logError(ex);
		}
	};
	/*
	 * key: initial object image
	 * value: array of images decorated with marker for severity (index + 1)
	 */
	private final Map<Image, Image[]> overlays = new HashMap<>();
	private final Map<Object /*URI|String*/, IResource> resourceCache = new HashMap<>();

	private final boolean showLocation;

	private boolean showKind;

	public SymbolsLabelProvider() {
		this(false, InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID)
				.getBoolean(CNFOutlinePage.SHOW_KIND_PREFERENCE, false));
	}

	public SymbolsLabelProvider(boolean showLocation, boolean showKind) {
		this.showLocation = showLocation;
		this.showKind = showKind;
		InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID).addPreferenceChangeListener(this);
		ResourcesPlugin.getWorkspace().addResourceChangeListener(listener);
	}

	@Override
	public void dispose() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
		InstanceScope.INSTANCE.getNode(LanguageServerPlugin.PLUGIN_ID).removePreferenceChangeListener(this);
		overlays.values().stream().flatMap(Arrays::stream).filter(Objects::nonNull).forEach(Image::dispose);
		overlays.clear();
		super.dispose();
	}

	@Override
	public @Nullable Image getImage(@Nullable Object element) {
		if (element == null) {
			return null;
		}
		if (element instanceof PendingUpdateAdapter) {
			return JFaceResources.getImage(ProgressManager.WAITING_JOB_KEY);
		}
		if (element instanceof Throwable) {
			return LSPImages.getSharedImage(ISharedImages.IMG_OBJS_ERROR_TSK);
		}
		if (element instanceof Either<?, ?> either) {
			element = either.get();
		}

		Image image = null;
		IResource file = null;
		if (element instanceof SymbolInformation info) {
			image = LSPImages.imageFromSymbolKind(info.getKind());
			file = resourceCache.computeIfAbsent(info.getLocation().getUri(), uri -> findResourceFor((String) uri));
		} else if (element instanceof WorkspaceSymbol symbol) {
			image = LSPImages.imageFromSymbolKind(symbol.getKind());
			file = resourceCache.computeIfAbsent(getUri(symbol), uri -> findResourceFor((String) uri));
		} else if (element instanceof DocumentSymbol symbol) {
			image = LSPImages.imageFromSymbolKind(symbol.getKind());
		} else if (element instanceof DocumentSymbolWithURI symbolWithURI) {
			image = LSPImages.imageFromSymbolKind(symbolWithURI.symbol.getKind());
			file = resourceCache.computeIfAbsent(symbolWithURI.uri, uri -> findResourceFor((URI) uri));
		}

		/*
		 * Implementation node: for problem decoration, maybe consider using a ILabelDecorator/IDelayedLabelDecorator?
		 */
		if (file != null && image != null) {
			Range range = null;
			if (element instanceof SymbolInformation symbol) {
				range = symbol.getLocation().getRange();
			} else if (element instanceof WorkspaceSymbol symbol && symbol.getLocation().isLeft()) {
				range = symbol.getLocation().getLeft().getRange();
			} else if (element instanceof DocumentSymbol documentSymbol) {
				range = documentSymbol.getRange();
			} else if (element instanceof DocumentSymbolWithURI symbolWithURI) {
				range = symbolWithURI.symbol.getRange();
			}
			if (range != null) {
				try {
					// use existing documents only to calculate the severity
					// to avoid extra documents being created (and connected
					// to the language server just for this (bug 550968)
					IDocument doc = LSPEclipseUtils.getExistingDocument(file);

					if (doc != null) {
						int maxSeverity = getMaxSeverity(file, doc, range);
						if (maxSeverity > IMarker.SEVERITY_INFO) {
							return getOverlay(image, maxSeverity);
						}
					}
				} catch (CoreException | BadLocationException e) {
					LanguageServerPlugin.logError(e);
				}
			}
		}
		return image;
	}

	protected int getMaxSeverity(IResource resource, IDocument doc, Range range)
			throws CoreException, BadLocationException {
		if (!severities.containsKey(resource)) {
			refreshMarkersByLine(resource);
		}
		RangeMap<Integer, Integer> severitiesForResource = severities.get(resource);
		if (severitiesForResource == null) {
			return -1;
		}
		int bound1 = LSPEclipseUtils.toOffset(range.getStart(), doc);
		int bound2 = LSPEclipseUtils.toOffset(range.getEnd(), doc);
		// using bounds here because doc may have changed in the meantime so toOffset can return wrong results.
		com.google.common.collect.Range<Integer> subRange = com.google.common.collect.Range.closed(
				Math.min(bound1, bound2), // we guard that lower <= endOffset
				bound2);
		return severitiesForResource.subRangeMap(subRange)
				.asMapOfRanges()
				.values()
				.stream()
				.max(Comparator.naturalOrder())
				.orElse(-1);
	}

	private void refreshMarkersByLine(IResource resource) throws CoreException {
		RangeMap<Integer, Integer> rangeMap = TreeRangeMap.create();
		Arrays.stream(resource.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_ZERO))
			.filter(marker -> marker.getAttribute(IMarker.SEVERITY, -1) > IMarker.SEVERITY_INFO)
			.sorted(Comparator.comparingInt(marker -> marker.getAttribute(IMarker.SEVERITY, -1)))
			.forEach(marker -> {
				int start = marker.getAttribute(IMarker.CHAR_START, -1);
				int end = marker.getAttribute(IMarker.CHAR_END, -1);
				if (end < start) {
					end = start;
				}
				int severity = marker.getAttribute(IMarker.SEVERITY, -1);
				if (start != end) {
					com.google.common.collect.Range<Integer> markerRange = com.google.common.collect.Range.closed(start,end - 1);
					rangeMap.remove(markerRange);
					rangeMap.put(markerRange, severity);
				}
			});
		severities.put(resource, rangeMap);
	}

	private Image getOverlay(Image res, int maxSeverity) {
		if (maxSeverity != 1 && maxSeverity != 2) {
			throw new IllegalArgumentException("Severity " + maxSeverity + " not supported."); //$NON-NLS-1$ //$NON-NLS-2$
		}
		Image[] currentOverlays = this.overlays.computeIfAbsent(res, key -> new Image [2]);
		if (castNullable(currentOverlays[maxSeverity - 1]) == null) {
			String overlayId = null;
			if (maxSeverity == IMarker.SEVERITY_ERROR) {
				overlayId = ISharedImages.IMG_DEC_FIELD_ERROR;
			} else if (maxSeverity == IMarker.SEVERITY_WARNING) {
				overlayId = ISharedImages.IMG_DEC_FIELD_WARNING;
			}
			currentOverlays[maxSeverity - 1] = new DecorationOverlayIcon(res,
					LSPImages.getSharedImageDescriptor(overlayId), IDecoration.BOTTOM_LEFT).createImage();
		}
		return currentOverlays[maxSeverity - 1];
	}

	@Override
	public String getText(Object element) {
		return getStyledText(element).getString();
	}

	@Override
	public StyledString getStyledText(@Nullable Object element) {

		if (element instanceof PendingUpdateAdapter) {
			return new StyledString(Messages.outline_computingSymbols);
		}
		if (element instanceof Throwable throwable) {
			String message = throwable.getMessage();
			if (message == null) {
				message = element.getClass().getName();
			}
			return new StyledString(message);
		}
		final var res = new StyledString();
		if (element == null){
			return res;
		}
		if (element instanceof Either<?, ?> either) {
			element = either.get();
		}
		String name = null;
		SymbolKind kind = null;
		String detail = null;
		URI location = null;
		boolean deprecated = false;
		if (element instanceof SymbolInformation symbolInformation) {
			name = symbolInformation.getName();
			kind = symbolInformation.getKind();
			deprecated = isDeprecated(symbolInformation.getTags()) || symbolInformation.getDeprecated() != null && symbolInformation.getDeprecated();
			try {
				location = URI.create(symbolInformation.getLocation().getUri());
			} catch (IllegalArgumentException e) {
				LanguageServerPlugin.logError("Invalid URI: " + symbolInformation.getLocation().getUri(), e); //$NON-NLS-1$
			}
		} else if (element instanceof WorkspaceSymbol workspaceSymbol) {
			name = workspaceSymbol.getName();
			kind = workspaceSymbol.getKind();
			String rawUri = getUri(workspaceSymbol);
			deprecated = isDeprecated(workspaceSymbol.getTags());
			try {
				location = URI.create(rawUri);
			} catch (IllegalArgumentException e) {
				LanguageServerPlugin.logError("Invalid URI: " + rawUri, e); //$NON-NLS-1$
			}
		} else if (element instanceof DocumentSymbol documentSymbol) {
			name = documentSymbol.getName();
			kind = documentSymbol.getKind();
			detail = documentSymbol.getDetail();
			deprecated = isDeprecated(documentSymbol.getTags()) || documentSymbol.getDeprecated() != null && documentSymbol.getDeprecated();
		} else if (element instanceof DocumentSymbolWithURI symbolWithURI) {
			name = symbolWithURI.symbol.getName();
			kind = symbolWithURI.symbol.getKind();
			detail = symbolWithURI.symbol.getDetail();
			location = symbolWithURI.uri;
			deprecated = isDeprecated(symbolWithURI.symbol.getTags()) || symbolWithURI.symbol.getDeprecated() != null && symbolWithURI.symbol.getDeprecated();
		}
		if (name != null) {
			if (deprecated) {
				res.append(name, StyleUtil.DEPRECATE);
			} else {
				res.append(name, null);
			}
		}

		if (detail != null) {
			res.append(' ');
			res.append(detail, StyledString.DECORATIONS_STYLER);
		}

		if (showKind && kind != null) {
			res.append(" :", null); //$NON-NLS-1$
			res.append(kind.toString(), StyledString.DECORATIONS_STYLER);
		}

		if (showLocation && location != null) {
			res.append(' ');
			res.append(location.getPath(), StyledString.QUALIFIER_STYLER);
		}
		return res;
	}

	private boolean isDeprecated(@Nullable List<SymbolTag> tags) {
		if(tags != null){
			return tags.contains(SymbolTag.Deprecated);
		}
		return false;
	}

	@Override
	public void restoreState(final IMemento aMemento) {
	}

	@Override
	public void saveState(final IMemento aMemento) {
	}

	@Override
	public @Nullable String getDescription(final Object anElement) {
		return null;
	}

	@Override
	public void init(final ICommonContentExtensionSite aConfig) {
	}

	@Override
	public void preferenceChange(PreferenceChangeEvent event) {
		if (event.getKey().equals(CNFOutlinePage.SHOW_KIND_PREFERENCE)) {
			this.showKind = Boolean.parseBoolean(event.getNewValue().toString());
			for (Object listener : this.getListeners()) {
				if (listener instanceof ILabelProviderListener labelProviderListener) {
					labelProviderListener.labelProviderChanged(new LabelProviderChangedEvent(this));
				}
			}
		}
	}

	private static String getUri(WorkspaceSymbol symbol) {
		return symbol.getLocation().map(Location::getUri, WorkspaceSymbolLocation::getUri);
	}

}
