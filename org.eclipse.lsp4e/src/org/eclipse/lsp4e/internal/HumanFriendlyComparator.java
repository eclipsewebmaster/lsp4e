/*******************************************************************************
 * Copyright (c) 2025 Sebastian Thomschke and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 * Sebastian Thomschke - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.internal;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A comparator for strings that compares alphanumeric strings in a
 * human-friendly order.
 *
 * <p>
 * This comparator handles strings containing both alphabetic and numeric
 * sequences. Numeric parts of the strings are compared numerically, while
 * alphabetic parts are compared lexicographically.
 * </p>
 *
 * <p>
 * Example: Given strings "file2.txt", "file10.txt", and "file1.txt", this
 * comparator will sort them as "file1.txt", "file2.txt", "file10.txt".
 * </p>
 */
public final class HumanFriendlyComparator implements Comparator<String> {

	public static final HumanFriendlyComparator DEFAULT = new HumanFriendlyComparator();

	private final Collator collator;

	/**
	 * Convenience constructor that uses the default Locale's Collator.
	 */
	public HumanFriendlyComparator() {
		this(Collator.getInstance(Locale.getDefault()));
	}

	/**
	 * Creates a HumanFriendlyComparator that uses the given Collator for comparing
	 * non-numeric substrings (Unicode-aware, locale-specific).
	 */
	public HumanFriendlyComparator(final Collator collator) {
		this.collator = collator;
	}

	@Override
	public int compare(final String str1, final String str2) {
		final List<Token> tokens1 = tokenize(str1);
		final List<Token> tokens2 = tokenize(str2);

		final int tokenCount1 = tokens1.size();
		final int tokenCount2 = tokens2.size();
		int i = 0;

		while (i < tokenCount1 && i < tokenCount2) {
			final Token t1 = tokens1.get(i);
			final Token t2 = tokens2.get(i);

			if (t1.isNumeric && t2.isNumeric) {
				// Compare numeric tokens
				final int cmp = compareNumeric(str1, t1, str2, t2);
				if (cmp != 0) {
					return cmp;
				}
			} else if (!t1.isNumeric && !t2.isNumeric) {
				// Compare text tokens using Collator
				final String sub1 = str1.substring(t1.start, t1.end);
				final String sub2 = str2.substring(t2.start, t2.end);

				final int cmp = collator.compare(sub1, sub2);
				if (cmp != 0) {
					return cmp;
				}
			} else {
				// One is numeric, the other is text
				return t1.isNumeric ? -1 : 1;
			}
			i++;
		}

		return tokenCount1 - tokenCount2;
	}

	/**
	 * Compares numeric tokens from the original strings.
	 */
	private int compareNumeric(final String str1, final Token tok1, final String str2, final Token tok2) {
		// Skip leading zeros
		final int start1 = skipLeadingZeros(str1, tok1.start, tok1.end);
		final int start2 = skipLeadingZeros(str2, tok2.start, tok2.end);

		final int len1 = tok1.end - start1;
		final int len2 = tok2.end - start2;

		// Compare lengths of numeric parts after leading zeros
		if (len1 != len2) {
			return len1 - len2;
		}

		// Same length => compare digit by digit
		for (int i = 0; i < len1; i++) {
			final char ch1 = str1.charAt(start1 + i);
			final char ch2 = str2.charAt(start2 + i);
			if (ch1 != ch2) {
				return ch1 - ch2;
			}
		}

		// If numeric values are identical, compare number of leading zeros
		final int leadingZeros1 = start1 - tok1.start;
		final int leadingZeros2 = start2 - tok2.start;
		return leadingZeros1 - leadingZeros2;
	}

	/**
	 * Skips leading zeros within the specified range of a string.
	 */
	private int skipLeadingZeros(final String str, int start, final int end) {
		while (start < end && str.charAt(start) == '0') {
			start++;
		}
		return start;
	}

	/**
	 * Tokenizes a string into numeric and non-numeric segments.
	 */
	private List<Token> tokenize(final String str) {
		final List<Token> tokens = new ArrayList<>();
		final int len = str.length();
		int i = 0;

		while (i < len) {
			final int start = i;
			final char ch = str.charAt(i++);
			if (isDigit(ch)) {
				while (i < len && isDigit(str.charAt(i))) {
					i++;
				}
				tokens.add(new Token(true, start, i));
			} else {
				while (i < len && !isDigit(str.charAt(i))) {
					i++;
				}
				tokens.add(new Token(false, start, i));
			}
		}
		return tokens;
	}

	/**
	 * Faster alternative to Character.isDigit(c) for ASCII digits.
	 */
	private boolean isDigit(char ch) {
		return ch >= '0' && ch <= '9';
	}

	/**
	 * Represents a token within a string, identified by start and end indices.
	 */
	private static final class Token {
		final boolean isNumeric;
		final int start;
		final int end;

		Token(final boolean isNumeric, final int start, final int end) {
			this.isNumeric = isNumeric;
			this.start = start;
			this.end = end;
		}
	}
}
