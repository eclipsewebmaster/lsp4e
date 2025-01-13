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
package org.eclipse.lsp4e.test.utils;

import static org.junit.Assert.*;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.lsp4e.ui.LSPImages;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.SymbolTag;
import org.eclipse.swt.graphics.Image;
import org.junit.Test;

public class LSPImagesTest {
	
	@Test
	public void testImageForSymbolKind() {
		for (SymbolKind kind : SymbolKind.values()) {
			Image img = LSPImages.imageFromSymbolKind(kind);
			
			assertNotNull(img);
		}
	}
	
	@Test
	public void testOverlayImageForSymbolTag() {
		for (SymbolTag tag : SymbolTag.values()) {
			ImageDescriptor descriptor = LSPImages.imageDescriptorOverlayFromSymbolTag(tag);
			Image img = LSPImages.imageOverlayFromSymbolTag(tag);
			
			assertNotNull(descriptor);
			assertNotNull(img);
		}
	}

}
