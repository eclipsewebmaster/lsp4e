/*******************************************************************************
 * Copyright (c) 2024 Advantest GmbH and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Dietrich Travkin (Solunar GmbH) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.test.symbols;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.List;

import org.eclipse.lsp4e.operations.symbols.SymbolsUtil;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolTag;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.junit.Test;

public class SymbolsUtilTest { //extends AbstractTest {
	
	@Test
	public void testDeprecatedCheck() {
		List<SymbolTag> symbolTagsWithDeprecated = Arrays.asList(SymbolTag.Package, SymbolTag.Deprecated, SymbolTag.ReadOnly);
		List<SymbolTag> symbolTags = Arrays.asList(SymbolTag.Public, SymbolTag.Declaration, SymbolTag.Static);
		
		var symbolInformation = new SymbolInformation();
		
		assertFalse(SymbolsUtil.isDeprecated(symbolInformation));
		
		symbolInformation.setDeprecated(true);
		
		assertTrue(SymbolsUtil.isDeprecated(symbolInformation));
		
		symbolInformation = new SymbolInformation();
		symbolInformation.setTags(symbolTagsWithDeprecated);
		
		assertTrue(SymbolsUtil.isDeprecated(symbolInformation));
		
		symbolInformation.setTags(symbolTags);
		
		assertFalse(SymbolsUtil.isDeprecated(symbolInformation));
		
		
		var workspaceSymbol = new WorkspaceSymbol();
		
		assertFalse(SymbolsUtil.isDeprecated(workspaceSymbol));
		
		workspaceSymbol.setTags(symbolTagsWithDeprecated);
		
		assertTrue(SymbolsUtil.isDeprecated(workspaceSymbol));
		
		workspaceSymbol.setTags(symbolTags);
		
		assertFalse(SymbolsUtil.isDeprecated(workspaceSymbol));
		
		
		var documentSymbol = new DocumentSymbol();
		
		assertFalse(SymbolsUtil.isDeprecated(documentSymbol));
		
		documentSymbol.setDeprecated(true);
		
		assertTrue(SymbolsUtil.isDeprecated(documentSymbol));
		
		documentSymbol = new DocumentSymbol();
		documentSymbol.setTags(symbolTagsWithDeprecated);
		
		assertTrue(SymbolsUtil.isDeprecated(documentSymbol));
		
		documentSymbol.setTags(symbolTags);
		
		assertFalse(SymbolsUtil.isDeprecated(documentSymbol));
	}

}
