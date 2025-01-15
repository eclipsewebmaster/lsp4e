/*******************************************************************************
 * Copyright (c) 2016-2023 Rogue Wave Software Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Michał Niewrzał (Rogue Wave Software Inc.) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.ui;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.resource.ImageRegistry;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.ISharedImages;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

public final class LSPImages {

	private LSPImages() {
		// this class shouldn't be instantiated
	}

	private static @Nullable ImageRegistry imageRegistry;
	private static final Map<java.awt.Color, Image> colorToImageCache = new HashMap<>();
	private static final String ICONS_PATH = "$nl$/icons/full/"; //$NON-NLS-1$
	private static final String OBJECT = ICONS_PATH + "obj16/"; // basic colors - size 16x16 //$NON-NLS-1$
	private static final String ACTION = ICONS_PATH + "elcl16/"; // basic colors - size 16x16 //$NON-NLS-1$
	private static final Image EMPTY_IMAGE = new Image(UI.getDisplay(), 16, 16);

	public static final String IMG_MODULE = "IMG_MODULE"; //$NON-NLS-1$
	public static final String IMG_NAMESPACE = "IMG_NAMESPACE"; //$NON-NLS-1$
	public static final String IMG_PACKAGE = "IMG_PACKAGE"; //$NON-NLS-1$
	public static final String IMG_CLASS = "IMG_CLASS"; //$NON-NLS-1$
	public static final String IMG_TYPE_PARAMETER = "IMG_TYPE_PARAMETER"; //$NON-NLS-1$
	public static final String IMG_METHOD = "IMG_METOHD"; //$NON-NLS-1$
	public static final String IMG_PROPERTY = "IMG_PROPERTY"; //$NON-NLS-1$
	public static final String IMG_FIELD = "IMG_FIELD"; //$NON-NLS-1$
	public static final String IMG_CONSTRUCTOR = "IMG_CONSTRUCTOR"; //$NON-NLS-1$
	public static final String IMG_ENUM = "IMG_ENUM"; //$NON-NLS-1$
	public static final String IMG_ENUM_MEMBER = "IMG_ENUM_MEMBER"; //$NON-NLS-1$
	public static final String IMG_STRUCT = "IMG_STRUCT"; //$NON-NLS-1$
	public static final String IMG_INTERACE = "IMG_INTERFACE"; //$NON-NLS-1$
	public static final String IMG_FUNCTION = "IMG_FUNCTION"; //$NON-NLS-1$
	public static final String IMG_VARIABLE = "IMG_VARIABLE"; //$NON-NLS-1$
	public static final String IMG_CONSTANT = "IMG_CONSTANT"; //$NON-NLS-1$
	public static final String IMG_OBJECT = "IMG_OBJECT"; //$NON-NLS-1$
	public static final String IMG_TEXT = "IMG_TEXT"; //$NON-NLS-1$
	public static final String IMG_STRING = IMG_TEXT;
	public static final String IMG_NUMBER = "IMG_NUMBER"; //$NON-NLS-1$
	public static final String IMG_BOOLEAN = "IMG_BOOLEAN"; //$NON-NLS-1$
	public static final String IMG_ARRAY = "IMG_ARRAY"; //$NON-NLS-1$
	public static final String IMG_NULL = "IMG_NULL"; //$NON-NLS-1$
	public static final String IMG_UNIT = "IMG_UNIT"; //$NON-NLS-1$
	public static final String IMG_VALUE = "IMG_VALUE"; //$NON-NLS-1$
	public static final String IMG_KEYWORD = "IMG_KEYWORD"; //$NON-NLS-1$
	public static final String IMG_SNIPPET = "IMG_SNIPPET"; //$NON-NLS-1$
	public static final String IMG_COLOR = "IMG_COLOR"; //$NON-NLS-1$
	public static final String IMG_REFERENCE = "IMG_REFERENCE"; //$NON-NLS-1$
	public static final String IMG_TERMINATE_CO = "IMG_TERMINATE_CO"; //$NON-NLS-1$

	public static final String IMG_SUPERTYPE = "IMG_SUPERTYPE"; //$NON-NLS-1$
	public static final String IMG_SUBTYPE = "IMG_SUBTYPE"; //$NON-NLS-1$

	public static void initalize(ImageRegistry registry) {
		imageRegistry = registry;

		declareRegistryImage(IMG_MODULE, OBJECT + "module.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_NAMESPACE, OBJECT + "namespace.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_PACKAGE, OBJECT + "package.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_CLASS, OBJECT + "class.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_TYPE_PARAMETER, OBJECT + "type_parameter.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_METHOD, OBJECT + "method.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_PROPERTY, OBJECT + "property.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_FIELD, OBJECT + "field.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_CONSTRUCTOR, OBJECT + "constructor.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_ENUM, OBJECT + "enum.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_ENUM_MEMBER, OBJECT + "enum_member.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_STRUCT, OBJECT + "struct.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_INTERACE, OBJECT + "interface.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_FUNCTION, OBJECT + "function.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_VARIABLE, OBJECT + "variable.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_CONSTANT, OBJECT + "constant.png"); //$NON-NLS-1$
      declareRegistryImage(IMG_OBJECT, OBJECT + "object.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_STRING, OBJECT + "string.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_NUMBER, OBJECT + "number.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_BOOLEAN, OBJECT + "boolean.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_ARRAY, OBJECT + "array.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_NULL, OBJECT + "null.png"); //$NON-NLS-1$

		declareRegistryImage(IMG_TEXT, OBJECT + "text.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_UNIT, OBJECT + "unit.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_VALUE, OBJECT + "value.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_KEYWORD, OBJECT + "keyword.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_SNIPPET, OBJECT + "snippet.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_COLOR, OBJECT + "color.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_REFERENCE, OBJECT + "reference.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_SUPERTYPE, ACTION + "super_co.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_SUBTYPE, ACTION + "sub_co.png"); //$NON-NLS-1$
		declareRegistryImage(IMG_TERMINATE_CO, OBJECT + "terminate_co.png"); //$NON-NLS-1$
	}

	private static void declareRegistryImage(String key, String path) {
		ImageDescriptor desc = ImageDescriptor.getMissingImageDescriptor();
		Bundle bundle = Platform.getBundle(LanguageServerPlugin.PLUGIN_ID);
		URL url = null;
		if (bundle != null) {
			url = FileLocator.find(bundle, new Path(path), null);
			if (url != null) {
				desc = ImageDescriptor.createFromURL(url);
			}
		}
		getImageRegistry().put(key, desc);
	}

	/**
	 * Returns the <code>Image</code> identified by the given key, or <code>null</code> if it does not exist.
	 */
	public static @Nullable Image getImage(String key) {
		return getImageRegistry().get(key);
	}

	/**
	 * Returns the <code>ImageDescriptor</code> identified by the given key, or <code>null</code> if it does not exist.
	 */
	public static @Nullable ImageDescriptor getImageDescriptor(String key) {
		return getImageRegistry().getDescriptor(key);
	}

	public static ImageRegistry getImageRegistry() {
		ImageRegistry imageRegistry = LSPImages.imageRegistry;
		if (imageRegistry == null) {
			imageRegistry = LSPImages.imageRegistry = LanguageServerPlugin.getDefault().getImageRegistry();
		}
		return imageRegistry;
	}

	/**
	 * @param imageId See static IMG_* fields of {@link ISharedImages}
	 * @return the workbench's shared image for the , or null if not found
	 */
	public static @Nullable Image getSharedImage(@Nullable String imageId) {
		if(imageId == null) {
			return null;
		}
		return PlatformUI.getWorkbench().getSharedImages().getImage(imageId);
	}

	/**
	 * @param imageId See static IMG_* fields of {@link ISharedImages}
	 * @return the workbench's shared image descriptor for the workbench, or null if not found
	 */
	public static @Nullable ImageDescriptor getSharedImageDescriptor(@Nullable String imageId) {
		if(imageId == null) {
			return null;
		}
		return PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(imageId);
	}

	public static @Nullable Image imageFromSymbolKind(@Nullable SymbolKind kind) {
		if (kind == null) {
			return EMPTY_IMAGE;
		}
		return switch (kind) {
		case Array -> getImage(IMG_ARRAY);
		case Boolean -> getImage(IMG_BOOLEAN);
		case Class -> getImage(IMG_CLASS);
		case Constant -> getImage(IMG_CONSTANT);
		case Constructor -> getImage(IMG_CONSTRUCTOR);
		case Enum -> getImage(IMG_ENUM);
		case EnumMember -> getImage(IMG_ENUM_MEMBER);
		case Struct -> getImage(IMG_STRUCT);
		case Field -> getImage(IMG_FIELD);
		case File -> getSharedImage(ISharedImages.IMG_OBJ_FILE);
		case Function -> getImage(IMG_FUNCTION);
		case Interface -> getImage(IMG_INTERACE);
		case Method -> getImage(IMG_METHOD);
		case Module -> getImage(IMG_MODULE);
		case Namespace -> getImage(IMG_NAMESPACE);
		case Number -> getImage(IMG_NUMBER);
		case Object -> getImage(IMG_OBJECT);
		case Package -> getImage(IMG_PACKAGE);
		case Property -> getImage(IMG_PROPERTY);
		case String -> getImage(IMG_STRING);
		case TypeParameter -> getImage(IMG_TYPE_PARAMETER);
		case Variable -> getImage(IMG_VARIABLE);
		case Null -> getImage(IMG_NULL);
		default -> EMPTY_IMAGE; // when the SymbolKind is out the cases above
		};
	}

	public static @Nullable Image imageFromCompletionItem(CompletionItem completionItem) {
		return switch (completionItem.getKind()) {
		case Text -> getImage(IMG_TEXT);
		case Method -> getImage(IMG_METHOD);
		case Function -> getImage(IMG_FUNCTION);
		case Constructor -> getImage(IMG_CONSTRUCTOR);
		case Field -> getImage(IMG_FIELD);
		case Variable -> getImage(IMG_VARIABLE);
		case Class -> getImage(IMG_CLASS);
		case Interface -> getImage(IMG_INTERACE);
		case Module -> getImage(IMG_MODULE);
		case Property -> getImage(IMG_PROPERTY);
		case Unit -> getImage(IMG_UNIT);
		case Value -> getImage(IMG_VALUE);
		case Enum -> getImage(IMG_ENUM);
		case EnumMember -> getImage(IMG_ENUM_MEMBER);
		case Struct -> getImage(IMG_STRUCT);
		case Keyword -> getImage(IMG_KEYWORD);
		case Snippet -> getImage(IMG_SNIPPET);
		case Color -> getImageForColor(completionItem);
		case File -> getSharedImage(ISharedImages.IMG_OBJ_FILE);
		case Folder -> getSharedImage(ISharedImages.IMG_OBJ_FOLDER);
		case Reference -> getImage(IMG_REFERENCE);
		default -> null;
		};
	}

	private static @Nullable Image getImageForColor(CompletionItem completionItem) {
		String hexValue = null;

		// TODO most probably can be extended for more cases
		String docString = LSPEclipseUtils.getDocString(completionItem.getDocumentation());
		if (docString != null && docString.startsWith("#")) { //$NON-NLS-1$
			hexValue = docString;
		} else if (completionItem.getLabel().startsWith("#")) { //$NON-NLS-1$
			hexValue = completionItem.getLabel();
		}
		if (hexValue == null) {
			return null;
		}

		java.awt.Color decodedColor = null;
		try {
			decodedColor = java.awt.Color.decode(hexValue);
		} catch (NumberFormatException e) {
			LanguageServerPlugin.logError(e);
			return null;
		}

		return colorToImageCache.computeIfAbsent(decodedColor, key -> {
			// TODO most probably some scaling should be done for HIDPI
			final var image = new Image(Display.getDefault(), 16, 16);
			final var gc = new GC(image);
			final var color = new Color(Display.getDefault(), key.getRed(), key.getGreen(),
					key.getBlue(), key.getAlpha());
			gc.setBackground(color);
			gc.fillRectangle(0, 0, 16, 16);
			gc.dispose();
			return image;
		});
	}
}
