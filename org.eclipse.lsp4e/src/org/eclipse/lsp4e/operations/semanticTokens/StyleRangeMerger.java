/*******************************************************************************
 * Copyright (c) 2022 Avaloq Evolution AG.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lsp4e.operations.semanticTokens;

import java.util.Iterator;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.TextPresentation;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyleRange;

/**
 * Merger to allow semantic highlighting to unset bold and/or italic
 * (presumably set/owned by TM4E highlighting) when it is unset.
 *
 * The css {font-weight: normal} otherwise has no effect. As a side-effect, the
 * css {} will also unset.
 */
public class StyleRangeMerger {

	private boolean unsetBoldWhenNotSet;
	private boolean unsetItalicWhenNotSet;

	public StyleRangeMerger(boolean unsetBoldWhenNotSet, boolean unsetItalicWhenNotSet) {
		this.unsetBoldWhenNotSet = unsetBoldWhenNotSet;
		this.unsetItalicWhenNotSet = unsetItalicWhenNotSet;
	}

	/**
	 * Merge style ranges from semantic highlighting into the existing text
	 * presentation.
	 * <p>
	 * In addition to creating new style ranges and merging features from old and
	 * new (e.g. background color and foreground color), italic and bold will be
	 * unset if semantic highlighting does not set them.
	 *
	 * @param textPresentation
	 *            the {@link TextPresentation}
	 * @param styleRangeHolder
	 *            the semantic highlighting style ranges
	 */
	@SuppressWarnings("null")
	public void mergeStyleRanges(final TextPresentation textPresentation, @Nullable StyleRangeHolder styleRangeHolder) {
		final IRegion extent = textPresentation.getExtent();

		if (extent == null || styleRangeHolder == null) {
			return;
		}

		StyleRange[] styleRanges = styleRangeHolder.overlappingRanges(extent);

		if (styleRanges.length == 0) {
			return;
		}

		// text presentation's merge will merge bold and italic with "or" -> this will
		// not unset them
		textPresentation.mergeStyleRanges(styleRanges);

		// style ranges are modified by TextPresentation merge, so fetch a new copy
		styleRanges = styleRangeHolder.overlappingRanges(extent);

		// now that the style ranges have been merged into textPresentation each
		// semantic styleRange has exact overlap with 1 or more in the textPresentation
		// (exact means the first overlapping range has the same start and the last the
		// same end)
		Iterator<StyleRange> e = textPresentation.getNonDefaultStyleRangeIterator();
		StyleRange target = e.next(); // as we merged a non-empty array of style ranges there is at least 1
		for (final StyleRange template : styleRanges) {
			if (!isStyleModifying(template)) {
				// only consider style ranges that potentially modify an existing style
				continue;
			}

			// find the target style range with the same start
			while (target.start != template.start) {
				target = e.next();
			}

			// apply modification until we have a style range at or after the end
			int templateEnd = template.start + template.length;
			do {
				modifyStyle(target, template);
			} while (e.hasNext() && (target = e.next()).start < templateEnd);
		}
	}

	/**
	 * Checks if the given style requires modifications beyond the
	 * standard {@code textPresentation} merge.
	 *
	 * @param style the {@link StyleRange} to evaluate
	 * @return {@code true} if additional modifications are needed;
	 *         {@code false} otherwise
	 */
	protected boolean isStyleModifying(StyleRange style) {
		int mask = SWT.NORMAL;
		if (unsetBoldWhenNotSet)
			mask |= SWT.BOLD;
		if (unsetItalicWhenNotSet)
			mask |= SWT.ITALIC;
		return (style.fontStyle | mask) != style.fontStyle;
	}


	/**
	 * Apply necessary modifications from template to target.
	 *
	 * @param target the target style
	 * @param template the template style
	 */
	protected void modifyStyle(StyleRange target, StyleRange template) {
		int mask = ~SWT.NORMAL;
		if (unsetBoldWhenNotSet && (template.fontStyle | SWT.BOLD) != template.fontStyle)
			mask &= ~SWT.BOLD;
		if (unsetItalicWhenNotSet && (template.fontStyle | SWT.ITALIC) != template.fontStyle)
			mask &= ~SWT.ITALIC;
		target.fontStyle &= mask;
	}

}