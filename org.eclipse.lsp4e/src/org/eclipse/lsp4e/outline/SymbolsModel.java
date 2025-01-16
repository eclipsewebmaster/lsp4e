/*******************************************************************************
 * Copyright (c) 2016 Rogue Wave Software Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Michał Niewrzał (Rogue Wave Software Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.outline;

import static org.eclipse.lsp4e.internal.ArrayUtil.NO_OBJECTS;
import static org.eclipse.lsp4e.internal.NullSafetyHelper.castNonNull;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.lsp4e.internal.ArrayUtil;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

public class SymbolsModel {

	private static final SymbolInformation ROOT_SYMBOL_INFORMATION = new SymbolInformation();

	private volatile Map<SymbolInformation, List<SymbolInformation>> childrenMap = Collections.emptyMap();
	private volatile List<DocumentSymbol> rootSymbols = Collections.emptyList();

	private @Nullable URI uri;

	public static class DocumentSymbolWithURI {
		public final DocumentSymbol symbol;
		public final URI uri;

		public DocumentSymbolWithURI(DocumentSymbol symbol, URI uri) {
			this.symbol = symbol;
			this.uri = uri;
		}

		@Override
		public boolean equals(@Nullable Object obj) {
			return obj instanceof DocumentSymbolWithURI other && //
					Objects.equals(this.symbol, other.symbol) && //
					Objects.equals(this.uri, other.uri);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.uri, this.symbol);
		}
	}

	public synchronized boolean update(@Nullable List<Either<SymbolInformation, DocumentSymbol>> response) {
		// TODO update model only on real change
		if (response == null || response.isEmpty()) {
			childrenMap = Collections.emptyMap();
			rootSymbols = Collections.emptyList();
		} else {
			final var newChildrenMap = new HashMap<SymbolInformation, List<SymbolInformation>>();
			final var newRootSymbols = new ArrayList<DocumentSymbol>();

			final var parentStack = new ArrayDeque<SymbolInformation>();
			parentStack.push(ROOT_SYMBOL_INFORMATION);
			final var previousSymbol = new SymbolInformation[1];

			response.stream() //
					.sorted(Comparator.comparing(
							either -> either.isLeft() ? either.getLeft().getLocation().getRange().getStart()
									: either.getRight().getRange().getStart(),
							// strange need to cast here, could be a JDT compiler issue
							Comparator.comparingInt(pos -> ((Position) pos).getLine())
									.thenComparingInt(pos -> ((Position) pos).getCharacter())))
					.forEach((Either<SymbolInformation, DocumentSymbol> either) -> {
						if (either.isLeft()) {
							SymbolInformation symbol = either.getLeft();
							if (isIncluded(previousSymbol[0], symbol)) {
								parentStack.push(castNonNull(previousSymbol[0]));
								addChild(newChildrenMap, castNonNull(parentStack.peek()), symbol);
							} else if (isIncluded(parentStack.peek(), symbol)) {
								addChild(newChildrenMap, castNonNull(parentStack.peek()), symbol);
							} else {
								while (!isIncluded(parentStack.peek(), symbol)) {
									parentStack.pop();
								}
								addChild(newChildrenMap, castNonNull(parentStack.peek()), symbol);
								parentStack.push(symbol);
							}
							previousSymbol[0] = symbol;
						} else if (either.isRight()) {
							newRootSymbols.add(either.getRight());
						}
					});

			childrenMap = newChildrenMap;
			rootSymbols = newRootSymbols;
		}
		return true;
	}

	private boolean isIncluded(@Nullable SymbolInformation parent, @Nullable SymbolInformation symbol) {
		if (parent == null || symbol == null) {
			return false;
		}
		if (parent == ROOT_SYMBOL_INFORMATION) {
			return true;
		}
		return isIncluded(parent.getLocation(), symbol.getLocation());
	}

	private boolean isIncluded(Location reference, Location included) {
		return reference.getUri().equals(included.getUri()) && !reference.equals(included)
				&& isAfter(reference.getRange().getStart(), included.getRange().getStart())
				&& isAfter(included.getRange().getEnd(), reference.getRange().getEnd());
	}

	private boolean isAfter(Position reference, Position included) {
		return included.getLine() > reference.getLine()
				|| (included.getLine() == reference.getLine() && included.getCharacter() >= reference.getCharacter());
	}

	private void addChild(Map<SymbolInformation, List<SymbolInformation>> newChildrenMap, SymbolInformation parent,
			SymbolInformation child) {
		List<SymbolInformation> children = newChildrenMap.computeIfAbsent(parent, key -> new ArrayList<>());
		children.add(child);
	}

	public Object[] getElements() {
		final var res = ArrayUtil.asArrayList(getChildren(ROOT_SYMBOL_INFORMATION));
		final URI current = this.uri;
		Function<DocumentSymbol, Object> mapper = current != null ? symbol -> new DocumentSymbolWithURI(symbol, current)
				: symbol -> symbol;
		rootSymbols.stream().map(mapper).forEach(res::add);
		return res.toArray();
	}

	public Object[] getChildren(@Nullable Object parentElement) {
		if (parentElement != null) {
			if (parentElement instanceof SymbolInformation) {
				List<SymbolInformation> children = childrenMap.get(parentElement);
				if (children != null && !children.isEmpty()) {
					return children.toArray();
				}
			} else if (parentElement instanceof DocumentSymbolWithURI element) {
				List<DocumentSymbol> children = element.symbol.getChildren();
				if (children != null && !children.isEmpty()) {
					return element.symbol.getChildren().stream()
							.map(symbol -> new DocumentSymbolWithURI(symbol, element.uri)).toArray();
				}
			}
		}
		return NO_OBJECTS;
	}

	public boolean hasChildren(@Nullable Object parentElement) {
		if (parentElement != null) {
			if (parentElement instanceof SymbolInformation) {
				List<SymbolInformation> children = childrenMap.get(parentElement);
				if (children != null) {
					return !children.isEmpty();
				}
			} else if (parentElement instanceof DocumentSymbolWithURI element) {
				List<DocumentSymbol> children = element.symbol.getChildren();
				if (children != null) {
					return !children.isEmpty();
				}
			}
		}
		return false;
	}

	public @Nullable Object getParent(@Nullable Object element) {
		if (element instanceof SymbolInformation) {
			for (Map.Entry<SymbolInformation, List<SymbolInformation>> entry : childrenMap.entrySet()) {
				if (entry.getValue().contains(element)) {
					return entry.getKey();
				}
			}
		}
		return null;
	}

	public void setUri(@Nullable URI uri) {
		this.uri = uri;
	}

	public @Nullable TreePath toUpdatedSymbol(TreePath initialSymbol) {
		final var res = new ArrayList<Object>(initialSymbol.getSegmentCount());
		Object currentSymbol = null;
		for (int i = 0; i < initialSymbol.getSegmentCount(); i++) {
			String name = getName(initialSymbol.getSegment(i));
			Object[] currentChildren = (currentSymbol == null ? getElements() : getChildren(currentSymbol));
			currentSymbol = ArrayUtil.findFirstMatching(currentChildren, child -> Objects.equals(getName(child), name));
			if (currentSymbol == null) {
				return null;
			}
			res.add(currentSymbol);
		}
		return new TreePath(res.toArray());
	}

	private @Nullable String getName(Object segment) {
		if (segment instanceof DocumentSymbolWithURI symbolWithURI) {
			segment = symbolWithURI.symbol;
		}
		if (segment instanceof DocumentSymbol documentSymbol) {
			return documentSymbol.getName();
		}
		return null;
	}

}
