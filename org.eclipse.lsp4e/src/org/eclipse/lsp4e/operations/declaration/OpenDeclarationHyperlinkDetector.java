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
 *  Michał Niewrzał (Rogue Wave Software Inc.) - hyperlink range detection
 *  Lucas Bullen (Red Hat Inc.) - [Bug 517428] Requests sent before initialization
 *  Dietrich Travkin (Solunar GmbH) - Adapt order of definitions and declarations (issue 1104)
 *******************************************************************************/
package org.eclipse.lsp4e.operations.declaration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.Region;
import org.eclipse.jface.text.hyperlink.AbstractHyperlinkDetector;
import org.eclipse.jface.text.hyperlink.IHyperlink;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4e.internal.Pair;
import org.eclipse.lsp4e.ui.Messages;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentPositionParams;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class OpenDeclarationHyperlinkDetector extends AbstractHyperlinkDetector {

	@Override
	public IHyperlink @Nullable [] detectHyperlinks(ITextViewer textViewer, IRegion region, boolean canShowMultipleHyperlinks) {
		final IDocument document = textViewer.getDocument();
		if (document == null) {
			return null;
		}
		TextDocumentPositionParams params;
		try {
			params = LSPEclipseUtils.toTextDocumentPosistionParams(region.getOffset(), document);
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
			return null;
		}
		final var allLinks = new LinkedHashMap<Either<Location, LocationLink>,LSBasedHyperlink>();
		try {
			var definitions = LanguageServers.forDocument(document).withCapability(ServerCapabilities::getDefinitionProvider)
				.collectAll(ls -> ls.getTextDocumentService().definition(LSPEclipseUtils.toDefinitionParams(params)).thenApply(l -> Pair.of(Messages.definitionHyperlinkLabel, l)));
			var declarations = LanguageServers.forDocument(document).withCapability(ServerCapabilities::getDeclarationProvider)
				.collectAll(ls -> ls.getTextDocumentService().declaration(LSPEclipseUtils.toDeclarationParams(params)).thenApply(l -> Pair.of(Messages.declarationHyperlinkLabel, l)));
			var typeDefinitions = LanguageServers.forDocument(document).withCapability(ServerCapabilities::getTypeDefinitionProvider)
				.collectAll(ls -> ls.getTextDocumentService().typeDefinition(LSPEclipseUtils.toTypeDefinitionParams(params)).thenApply(l -> Pair.of(Messages.typeDefinitionHyperlinkLabel, l)));
			var implementations = LanguageServers.forDocument(document).withCapability(ServerCapabilities::getImplementationProvider)
				.collectAll(ls -> ls.getTextDocumentService().implementation(LSPEclipseUtils.toImplementationParams(params)).thenApply(l -> Pair.of(Messages.implementationHyperlinkLabel, l)));

			var locationPairs = LanguageServers.addAll(definitions, declarations)
					.get(800, TimeUnit.MILLISECONDS);

			locationPairs = sortElementsUnderCursorToTheEnd(locationPairs, document, region);
			locationPairs.addAll(LanguageServers.addAll(typeDefinitions, implementations)
					.get(800, TimeUnit.MILLISECONDS));

			locationPairs.stream()
				.flatMap(locations -> toHyperlinks(document, region, locations.first(), locations.second()).stream())
				.forEach(link -> allLinks.putIfAbsent(link.getLocation(), link));
		} catch (ExecutionException e) {
			LanguageServerPlugin.logError(e);
		} catch (InterruptedException e) {
			LanguageServerPlugin.logError(e);
			Thread.currentThread().interrupt();
		} catch (TimeoutException e) {
			LanguageServerPlugin.logWarning("Could not detect hyperlinks due to timeout after 800 milliseconds", e);  //$NON-NLS-1$
		}
		if (allLinks.isEmpty()) {
			return null;
		}
		return allLinks.values().toArray(IHyperlink[]::new);
	}

	private List<Pair<@Nullable String, @Nullable Either<@Nullable List<? extends @Nullable Location>, @Nullable List<? extends @Nullable LocationLink>>>> sortElementsUnderCursorToTheEnd(
			List<Pair<@Nullable String, @Nullable Either<@Nullable List<? extends @Nullable Location>, @Nullable List<? extends @Nullable LocationLink>>>> locations,
			IDocument document,
			IRegion textViewerRange) {

		List<Pair<@Nullable String, Either<List<? extends Location>, List<? extends LocationLink>>>> filteredLocationPairs = new ArrayList<>(locations.size());
		List<Pair<@Nullable String, Either<List<? extends Location>, List<? extends LocationLink>>>> currentLocationPairs = new ArrayList<>(2);

		for (var pair : locations) {
			if (pair.second() != null && pair.second().isLeft()) {
				List<Location> remainingLocations = new ArrayList<>(pair.second().getLeft().size());
				List<Location> currentLocations = new ArrayList<>(2);
				for (Location location : pair.second().getLeft()) {
					if (location == null) {
						continue;
					}
					if (cursorOnSameElement(document, textViewerRange, location)) {
						currentLocations.add(location);
					} else {
						remainingLocations.add(location);
					}
				}
				filteredLocationPairs.add(new Pair<>(pair.first(), Either.forLeft(remainingLocations)));
				currentLocationPairs.add(new Pair<>(pair.first(), Either.forLeft(currentLocations)));
			} else {
				filteredLocationPairs.add(pair);
			}
		}

		filteredLocationPairs.addAll(currentLocationPairs);

		filteredLocationPairs = filteredLocationPairs.stream()
				.filter(pair -> pair.second().isRight() || !pair.second().getLeft().isEmpty())
				.collect(Collectors.toList());

		return filteredLocationPairs;
	}

	private boolean cursorOnSameElement(IDocument document, IRegion textViewerRange, Location location) {
		final URI openDocumentUri = LSPEclipseUtils.toUri(document);

		if (openDocumentUri == null) {
			return false;
		}

		final String openDocumentUriText = openDocumentUri.toString();

		if (!location.getUri().equals(openDocumentUriText)) {
			return false;
		}

		Range locationRange = location.getRange();

		Position locationRangeStartPos = locationRange.getStart();
		Position locationRangeEndPos = locationRange.getEnd();

		int viewerRangeOffset = textViewerRange.getOffset();
		int viewerRangeLength = textViewerRange.getLength();

		int viewerRangeLine = -1;
		int viewerRangeLineOffset = -1;
		int viewerRangeEndLine = -1;
		int viewerRangeEndLineOffset = -1;
		try {
			viewerRangeLine = document.getLineOfOffset(viewerRangeOffset);
			viewerRangeLineOffset = document.getLineOffset(viewerRangeLine);
			viewerRangeEndLine = document.getLineOfOffset(viewerRangeOffset + viewerRangeLength);
			viewerRangeEndLineOffset = document.getLineOffset(viewerRangeEndLine);
		} catch (BadLocationException e) {
			LanguageServerPlugin.logError(e);
		}
		int viewerRangeStartCharIndex = viewerRangeOffset - viewerRangeLineOffset;
		int viewerRangeEndCharIndex = viewerRangeOffset + viewerRangeLength - viewerRangeEndLineOffset;

		if (locationRangeStartPos.getLine() <= viewerRangeLine
				&& locationRangeEndPos.getLine() >= viewerRangeLine
				&& locationRangeStartPos.getCharacter() <= viewerRangeStartCharIndex
				&& locationRangeEndPos.getCharacter() >= viewerRangeEndCharIndex) {
			return true;
		}

		return false;
	}

	/**
	 * Returns a list of {@link LSBasedHyperlink} using the given LSP locations
	 *
	 * @param document
	 *            the document
	 * @param region
	 *            the region
	 * @param locationType
	 *            the location type
	 * @param locations
	 *            the LSP locations
	 */
	private static Collection<LSBasedHyperlink> toHyperlinks(IDocument document, IRegion region,
			String locationType, @NonNullByDefault({}) Either<List<? extends Location>, List<? extends LocationLink>> locations) {
		if (locations == null) {
			return Collections.emptyList();
		}
		return locations.map(//
				l -> l.stream().filter(Objects::nonNull)
						.map(location -> new LSBasedHyperlink(location, findWord(document, region), locationType))
						.toList(),
				r -> r.stream().filter(Objects::nonNull).map(locationLink -> new LSBasedHyperlink(locationLink,
						getSelectedRegion(document, region, locationLink), locationType)).toList());
	}

	/**
	 * Returns the selection region, or if that fails , fallback to
	 * {@link #findWord(IDocument, IRegion)}
	 */
	private static IRegion getSelectedRegion(IDocument document, IRegion region, LocationLink locationLink) {
		Range originSelectionRange = locationLink.getOriginSelectionRange();
		if (originSelectionRange != null) {
			try {
				int offset = LSPEclipseUtils.toOffset(originSelectionRange.getStart(), document);
				int endOffset = LSPEclipseUtils.toOffset(originSelectionRange.getEnd(), document);
				return new Region(offset, endOffset - offset);
			} catch (BadLocationException e) {
				LanguageServerPlugin.logError(e.getMessage(), e);
			}
		}
		return findWord(document, region);
	}

	/**
	 * Fallback for missing range value (which can be used to highlight hyperlink)
	 * in LSP 'definition' response.
	 */
	private static IRegion findWord(IDocument document, IRegion region) {
		int start = -2;
		int end = -1;
		int offset = region.getOffset();

		try {

			int pos = offset;
			char c;

			while (pos >= 0 && pos < document.getLength()) {
				c = document.getChar(pos);
				if (!Character.isUnicodeIdentifierPart(c)) {
					break;
				}
				--pos;
			}

			start = pos;

			pos = offset;
			int length = document.getLength();

			while (pos < length) {
				c = document.getChar(pos);
				if (!Character.isUnicodeIdentifierPart(c))
					break;
				++pos;
			}

			end = pos;

		} catch (BadLocationException x) {
			LanguageServerPlugin.logWarning(x.getMessage(), x);
		}

		if (start >= -1 && end > -1) {
			if (start == offset && end == offset)
				return new Region(offset, 0);
			else if (start == offset)
				return new Region(start, end - start);
			else
				return new Region(start + 1, end - start - 1);
		}

		return region;
	}

}
