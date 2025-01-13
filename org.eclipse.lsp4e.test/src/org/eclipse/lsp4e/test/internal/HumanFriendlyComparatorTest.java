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
package org.eclipse.lsp4e.test.internal;

import static org.junit.Assert.assertEquals;

import java.text.Collator;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.eclipse.lsp4e.internal.HumanFriendlyComparator;
import org.junit.Test;

public class HumanFriendlyComparatorTest {

	@Test
	public void testNumericSorting() {
		List<String> input = Arrays.asList("file10.txt", "file2.txt", "file1.txt", "file100.txt");
		List<String> expected = Arrays.asList("file1.txt", "file2.txt", "file10.txt", "file100.txt");

		input.sort(HumanFriendlyComparator.DEFAULT);

		assertEquals(expected, input);
	}

	@Test
	public void testMixedTextAndNumbers() {
		List<String> input = Arrays.asList("a10b", "a2b", "a10a", "a2a");
		List<String> expected = Arrays.asList("a2a", "a2b", "a10a", "a10b");

		input.sort(HumanFriendlyComparator.DEFAULT);

		assertEquals(expected, input);
	}

	@Test
	public void testLocaleSpecificSorting() {
		List<String> input = Arrays.asList("ä2", "a10", "a2", "ä10", "á100", "A2");
		List<String> expected = Arrays.asList("a2", "a10", "A2", "á100", "ä2", "ä10");

		Collator collator = Collator.getInstance(Locale.GERMAN);
		collator.setStrength(Collator.TERTIARY);

		var comparator = new HumanFriendlyComparator(collator);
		input.sort(comparator);

		assertEquals(expected, input);
	}

	@Test
	public void testLeadingZeros() {
		List<String> input = Arrays.asList("file002.txt", "file2.txt", "file0002.txt", "file01.txt");
		List<String> expected = Arrays.asList("file01.txt", "file2.txt", "file002.txt", "file0002.txt");

		input.sort(HumanFriendlyComparator.DEFAULT);

		assertEquals(expected, input);
	}

	@Test
	public void testEmptyStringsAndSpecialCases() {
		List<String> input = Arrays.asList("", "file", "file10", "file2", "file 10", "file 2", "file2", " ");
		List<String> expected = Arrays.asList("", " ", "file", "file2", "file2", "file10", "file 2", "file 10");

		input.sort(HumanFriendlyComparator.DEFAULT);

		assertEquals(expected, input);
	}
}
