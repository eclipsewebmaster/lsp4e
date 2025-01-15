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
package org.eclipse.lsp4e.operations.hover;

import java.net.URL;

import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.internal.text.html.BrowserInformationControl;
import org.eclipse.jface.resource.ColorRegistry;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.DefaultInformationControl;
import org.eclipse.jface.text.IInformationControlCreator;
import org.eclipse.jface.util.Util;
import org.eclipse.lsp4e.LSPEclipseUtils;
import org.eclipse.lsp4e.LanguageServerPlugin;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.editors.text.EditorsUI;
import org.eclipse.ui.texteditor.AbstractDecoratedTextEditorPreferenceConstants;

import com.google.common.base.Strings;

@SuppressWarnings("restriction")
public class FocusableBrowserInformationControl extends BrowserInformationControl {

	private static final String HEAD = "<head>"; //$NON-NLS-1$
	private static final String HTML_TEMPLATE = "<head>%s</head>%s"; //$NON-NLS-1$

	private static final LocationListener HYPER_LINK_LISTENER = new LocationListener() {

		@Override
		public void changing(LocationEvent event) {
			if (!"about:blank".equals(event.location)) { //$NON-NLS-1$
				LSPEclipseUtils.open(event.location, null);
				event.doit = false;
			}
		}

		@Override
		public void changed(LocationEvent event) {
			// comment requested by sonar
		}
	};

	public FocusableBrowserInformationControl(Shell parent, String symbolicFontName, boolean resizable) {
		super(parent, JFaceResources.DEFAULT_FONT, resizable);
	}

	public FocusableBrowserInformationControl(Shell parent) {
		super(parent, JFaceResources.DEFAULT_FONT, EditorsUI.getTooltipAffordanceString());
	}

	private double adjust(double height, @Nullable Object margin) {
		if (margin instanceof String marginString && marginString.endsWith("px")) { //$NON-NLS-1$
			try {
				height += Integer.parseInt(marginString.substring(0, marginString.length() - 2));
			} catch (NumberFormatException e) {}
		}
		return height;
	}

	@Override
	protected void createContent(Composite parent) {
		super.createContent(parent);
		final var b = (Browser) (parent.getChildren()[0]);
		b.addProgressListener(ProgressListener.completedAdapter(event -> {
			if (getInput() == null)
				return;
			final var browser = (Browser) event.getSource();
			@Nullable
			Point constraints = getSizeConstraints();
			Point hint = computeSizeHint();
			setSize(hint.x, hint.y);

			safeExecute(browser, "document.getElementsByTagName(\"html\")[0].style.whiteSpace = \"nowrap\""); //$NON-NLS-1$
			Double width = 20 + (safeEvaluate(browser, "return document.body.scrollWidth;") instanceof Double evaluated ? evaluated : 0); //$NON-NLS-1$
			setSize(width.intValue(), hint.y);

			safeExecute(browser, "document.getElementsByTagName(\"html\")[0].style.whiteSpace = \"normal\""); //$NON-NLS-1$
			Double height = safeEvaluate(browser, "return document.body.scrollHeight;") instanceof Double evaluated ? evaluated : 0; //$NON-NLS-1$
			Object marginTop = safeEvaluate(browser, "return window.getComputedStyle(document.body).marginTop;"); //$NON-NLS-1$
			Object marginBottom = safeEvaluate(browser, "return window.getComputedStyle(document.body).marginBottom;"); //$NON-NLS-1$
			if (Platform.getPreferencesService().getBoolean(EditorsUI.PLUGIN_ID,
					AbstractDecoratedTextEditorPreferenceConstants.EDITOR_SHOW_TEXT_HOVER_AFFORDANCE, true,
					null)) {
				FontData[] fontDatas = JFaceResources.getDialogFont().getFontData();
				height += fontDatas[0].getHeight();
			}

			width = width * 1.5;
			if (Util.isWin32()) {
				height = adjust(height, marginTop);
				height = adjust(height, marginBottom);
			}
			if (constraints != null && constraints.x < width) {
				width = (double) constraints.x;
			}
			if (constraints != null && constraints.y < height) {
				height = (double) constraints.y;
			}

			setSize(width.intValue(), height.intValue());
		}));
		b.setJavascriptEnabled(true);
	}

	private static @Nullable Object safeEvaluate(Browser browser, String expression) {
		try {
			return browser.evaluate(expression);
		} catch (Exception ex) {
			LanguageServerPlugin.logError(ex);
		}
		return null;
	}

	private static boolean safeExecute(Browser browser, String expression) {
		try {
			return browser.execute(expression);
		} catch (Exception ex) {
			LanguageServerPlugin.logError(ex);
		}
		return false;
	}

	@Override
	public void setInput(@Nullable Object input) {
		if (input instanceof String html) {
			input = styleHtml(html);
		}
		super.setInput(input);
	}

	public String styleHtml(String html) {
		if (html.isEmpty()) {
			return html;
		}

		// put CSS styling to match Eclipse style
		ColorRegistry colorRegistry = JFaceResources.getColorRegistry();
		Color foreground = colorRegistry.get("org.eclipse.ui.workbench.HOVER_FOREGROUND"); //$NON-NLS-1$
		Color background = colorRegistry.get("org.eclipse.ui.workbench.HOVER_BACKGROUND"); //$NON-NLS-1$
		final var style = "<style TYPE='text/css'>html { " + //$NON-NLS-1$
				"font-family: " + JFaceResources.getDefaultFontDescriptor().getFontData()[0].getName() + "; " + //$NON-NLS-1$ //$NON-NLS-2$
				"font-size: " + Integer.toString(JFaceResources.getDefaultFontDescriptor().getFontData()[0].getHeight()) //$NON-NLS-1$
				+ "pt; " + //$NON-NLS-1$
				(background != null ? "background-color: " + toHTMLrgb(background.getRGB()) + "; " : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				(foreground != null ? "color: " + toHTMLrgb(foreground.getRGB()) + "; " : "") + //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				" }</style>"; //$NON-NLS-1$

		String hlStyle = null;
		try {
			var pluginClass = LanguageServerPlugin.getDefault().getClass();
			URL urlHJScript = pluginClass.getResource("/resources/highlight.min.js/highlight.min.js"); //$NON-NLS-1$
			URL urlHJCss = pluginClass.getResource(isDarkTheme() ? //
					"/resources/highlight.min.js/styles/dark.min.css" : //$NON-NLS-1$
					"/resources/highlight.min.js/styles/default.min.css"); //$NON-NLS-1$
			URL fileUrlHJScript = urlHJScript == null ? null : FileLocator.toFileURL(urlHJScript);
			URL fileUrlHJCss = urlHJCss == null ? null : FileLocator.toFileURL(urlHJCss);
			if (fileUrlHJScript != null && fileUrlHJCss != null) {
				hlStyle = "<link rel='stylesheet' href='" + fileUrlHJCss + "'>" + //$NON-NLS-1$ //$NON-NLS-2$
						"<script src='" + fileUrlHJScript + "'></script>" + //$NON-NLS-1$ //$NON-NLS-2$
						"<script>hljs.highlightAll();</script>"; //$NON-NLS-1$
			}
		} catch (Exception ex) {
			LanguageServerPlugin.logError(ex);
		}

		int headIndex = html.indexOf(HEAD);
		String headContent = style + Strings.nullToEmpty(hlStyle);
		if (headIndex > 0) {
			return new StringBuilder(html).insert(headIndex, headContent).toString();
		} else {
			return String.format(HTML_TEMPLATE, headContent, html);
		}
	}

	private boolean isDarkTheme() {
		RGB color = getShell().getBackground().getRGB();
		return (color.red * 0.299 + color.green * 0.587+ color.blue *0.114) < 128; //turn to grey and check the level
	}

	private static CharSequence toHTMLrgb(RGB rgb) {
		final var builder = new StringBuilder(7);
		builder.append('#');
		appendAsHexString(builder, rgb.red);
		appendAsHexString(builder, rgb.green);
		appendAsHexString(builder, rgb.blue);
		return builder;
	}

	private static void appendAsHexString(StringBuilder buffer, int intValue) {
		String hexValue= Integer.toHexString(intValue);
		if (hexValue.length() == 1) {
			buffer.append('0');
		}
		buffer.append(hexValue);
	}

	@Override
	public @Nullable IInformationControlCreator getInformationPresenterControlCreator() {
		return parent -> {
			if (BrowserInformationControl.isAvailable(parent)) {
				final var res = new FocusableBrowserInformationControl(parent, JFaceResources.DEFAULT_FONT, true);
				res.addLocationListener(HYPER_LINK_LISTENER);
				return res;
			} else {
				return new DefaultInformationControl(parent);
			}
		};
	}

}