/*******************************************************************************
 * Copyright (c) 2022-3 Cocotec Ltd and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Ahmed Hussain (Cocotec Ltd) - initial implementation
 *
 *******************************************************************************/
package org.eclipse.lsp4e.test;

import static org.eclipse.lsp4e.LanguageServiceAccessor.hasActiveLanguageServers;
import static org.eclipse.lsp4e.test.utils.TestUtils.*;
import static org.junit.Assert.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Vector;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.lsp4e.LanguageServerWrapper;
import org.eclipse.lsp4e.LanguageServers;
import org.eclipse.lsp4e.LanguageServers.LanguageServerDocumentExecutor;
import org.eclipse.lsp4e.internal.Pair;
import org.eclipse.lsp4e.test.utils.AbstractTestWithProject;
import org.eclipse.lsp4e.test.utils.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockConnectionProvider;
import org.eclipse.lsp4e.tests.mock.MockLanguageServer;
import org.eclipse.lsp4e.tests.mock.MockTextDocumentService;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.tests.harness.util.DisplayHelper;
import org.junit.Assume;
import org.junit.Test;

public class LanguageServersTest extends AbstractTestWithProject {

	private final Predicate<ServerCapabilities> MATCH_ALL = sc -> true;

	@Test
	public void testCollectAll() throws Exception {
		final var hoverCount = new AtomicInteger();
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent" + hoverCount.incrementAndGet())), new Range(new Position(0,  0), new Position(0, 10)));
				return CompletableFuture.completedFuture(hoverResponse);
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<List<String>> result =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.collectAll(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h.getContents().getLeft().get(0).getLeft()));

		List<String> hovers = result.join();

		assertTrue(hovers.contains("HoverContent1"));
		assertTrue(hovers.contains("HoverContent2"));
	}

	@Test
	public void testCollectAllExcludesNulls() throws Exception {
		final var hoverCount = new AtomicInteger();
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent" + hoverCount.incrementAndGet())), new Range(new Position(0,  0), new Position(0, 10)));
				return CompletableFuture.completedFuture(hoverCount.get() == 1 ? hoverResponse : null);
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<List<String>> result =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.collectAll(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h == null ? null : h.getContents().getLeft().get(0).getLeft()));

		List<String> hovers = result.join();

		assertTrue(hovers.contains("HoverContent1"));
		assertFalse(hovers.contains(null));
	}

	@Test
	public void testComputeAll() throws Exception {
		final var hoverCount = new AtomicInteger();
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent" + hoverCount.incrementAndGet())), new Range(new Position(0,  0), new Position(0, 10)));
				final int currentCount = hoverCount.get();
				return CompletableFuture.completedFuture(hoverResponse).thenApplyAsync(t -> {
					try {
						Thread.sleep(currentCount * 1000);
					} catch (InterruptedException e) {

					}
					return t;
				});
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		List<CompletableFuture<String>> result =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.computeAll(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h.getContents().getLeft().get(0).getLeft()));

		assertEquals("Should have had two responses", 2, result.size());

		final Object first = CompletableFuture.anyOf(result.get(0), result.get(1)).join();

		assertEquals("HoverContent1 should have returned first, independently", "HoverContent1", first);

		List<String> hovers = result.stream().map(CompletableFuture::join).toList();

		assertTrue(hovers.contains("HoverContent1"));
		assertTrue(hovers.contains("HoverContent2"));
	}


	/**
	 * The raw CompletableFuture objects returned by the LSP4j layer receive their results on a dedicated listener thread which just reads responses
	 * from the LS output stream and dispatches them synchronously. If we compose work synchronously onto those objects then that work has
	 * to be done by the listener thread, which will tie it up and prevent it reading more messages. The LSExecutor API prevents the user from doing
	 * this by chaining <code>.thenApplyAsync(Function.identiy())</code> onto the raw objects, so that any extra work the user appends will
	 * run in the default executor pool, not the listener thread.
	 */
	@Test
	public void testCollectAllUserCannotBlockListener() throws Exception {
		// This test will only work if a minimum of two tasks can be run in the common pool without blocking!
		Assume.assumeTrue("Test skipped as common thread pool does not have multiple executors", ForkJoinPool.commonPool().getParallelism() >= 2);
		final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent")), new Range(new Position(0,  0), new Position(0, 10)));
		MockLanguageServer.INSTANCE.setHover(hoverResponse);

		IFile testFile = TestUtils.createUniqueTestFile(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		final long startTime = System.currentTimeMillis();

		CompletableFuture<String> resultThreadFuture =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.collectAll(ls -> ls.getTextDocumentService().hover(params))

				// Schedule a slow 'computation' on the response, and make a note of the thread it runs in
				.thenApply(hoverResult -> {
					try {
						Thread.sleep(3000);
					} catch (InterruptedException e) {

					}
					return Thread.currentThread().getName();
				});

		CompletableFuture<?> fastHover = LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.collectAll(ls -> ls.getTextDocumentService().hover(params));

		fastHover.join();

		final long secondResponseTime = System.currentTimeMillis() - startTime;

		final String resultThread = resultThreadFuture.join();

		assertTrue("Second hover response should not have been blocked by the first but took " + secondResponseTime + " ms", secondResponseTime < 1000);
		assertTrue("Result should not have run on an LS listener thread but ran on " + resultThread, !resultThread.startsWith("LS"));
	}

	@Test
	public void testComputeFirst() throws Exception {
		final var hoverCount = new AtomicInteger();
		final var internalResults = new Vector<CompletableFuture<?>>();
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent" + hoverCount.incrementAndGet())), new Range(new Position(0,  0), new Position(0, 10)));
				final int currentCount = hoverCount.get();
				CompletableFuture<Hover> result =  CompletableFuture.completedFuture(hoverResponse).thenApplyAsync(t -> {
					try {
						Thread.sleep(currentCount * 1000);
					} catch (InterruptedException e) {

					}
					return t;
				});
				internalResults.add(result);
				return result;
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<Optional<String>> response =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.computeFirst(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h.getContents().getLeft().get(0).getLeft()));

		Optional<String> result = response.join();
		assertTrue(result.isPresent());

		assertEquals("HoverContent1 should have arrived first", "HoverContent1", result.get());

		// It won't *normally) matter in production but because the tests run quickly, make sure the test teardown doesn't
		// occur before the slower, ignored result has completed, otherwise will get a load of console noise
		internalResults.forEach(CompletableFuture::join);
	}

	@Test
	public void testComputeFirstSkipsEmptyResults() throws Exception {
		final var hoverCount = new AtomicInteger();
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent" + hoverCount.incrementAndGet())), new Range(new Position(0,  0), new Position(0, 10)));
				if (hoverCount.get() == 1) {
					return CompletableFuture.completedFuture(null);
				}
				return CompletableFuture.completedFuture(hoverResponse).thenApplyAsync(t -> {
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {

					}
					return t;
				});
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<Optional<String>> response =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.computeFirst(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h == null ? null : h.getContents().getLeft().get(0).getLeft()));

		Optional<String> result = response.join();
		assertTrue("Should have returned a result", result.isPresent());

		assertEquals("HoverContent2 should have been the result", "HoverContent2", result.get());

	}

	@Test
	public void testComputeFirstReturnsEmptyOptionalIfNoResult() throws Exception {
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				return CompletableFuture.completedFuture(null);
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<Optional<String>> response =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.computeFirst(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h == null ? null : h.getContents().getLeft().get(0).getLeft()));

		Optional<String> result = response.join();
		assertTrue("Should not have returned a result", result.isEmpty());
	}

	@Test
	public void testComputeFirstTreatsEmptyListAsNull() throws Exception {
		final var hoverCount = new AtomicInteger();
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent" + hoverCount.incrementAndGet())), new Range(new Position(0,  0), new Position(0, 10)));
				if (hoverCount.get() == 1) {
					return CompletableFuture.completedFuture(null);
				}
				return CompletableFuture.completedFuture(hoverResponse).thenApplyAsync(t -> {
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {

					}
					return t;
				});
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<Optional<List<String>>> response =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.computeFirst(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h == null ? Collections.emptyList() : List.of(h.getContents().getLeft().get(0).getLeft())));

		Optional<List<String>> result = response.join();
		assertTrue("Should have returned a result", result.isPresent());

		assertEquals("HoverContent2 should have been the result", "HoverContent2", result.get().get(0));
	}

	/**
	 * Sends a (large) series of alternating document updates and hover requests, checking that the
	 * ordering of events on the client side is correctly reflected in the order in which the messages
	 * arrive [are sent to] the server
	 */
	@Test
	public void editInterleavingTortureTest() throws Exception {

		final Vector<Integer> tooEarlyHover = new Vector<>();
		final Vector<Integer> tooLateHover = new Vector<>();

		MockLanguageServer.INSTANCE.getInitializeResult().getCapabilities()
		.setTextDocumentSync(TextDocumentSyncKind.Incremental);
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			int changeVersion = 0;
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
				changeVersion++;
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final int targetVersionForRequest = position.getPosition().getCharacter();
				if (targetVersionForRequest < changeVersion) {
					tooLateHover.add(targetVersionForRequest);
				} else if (targetVersionForRequest > changeVersion){
					tooEarlyHover.add(targetVersionForRequest);
				}
				return super.hover(position);
			}
		});

		final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent")), new Range(new Position(0,  0), new Position(0, 10)));
		MockLanguageServer.INSTANCE.setHover(hoverResponse);
		CompletableFuture<?> initial = CompletableFuture.completedFuture(null);

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		StyledText text = viewer.getTextWidget();
		Thread.sleep(1000);

		for (int i = 0; i < 5000; i++) {
			final int current = i + 1;
			text.append(i + "\n");
			final var params = new HoverParams();
			final var position = new Position();

			// encode the iteration number in a suitable numeric field on the hover request params, so the
			// mock server can use it to verify the requests are indeed received in the correct order
			position.setCharacter(current);
			position.setLine(0);
			params.setPosition(position);

			CompletableFuture<List<Hover>> result =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.collectAll(ls -> ls.getTextDocumentService().hover(params));
			initial = CompletableFuture.allOf(initial, result);
		}

		initial.join();
		final var message = new StringBuilder();
		message.append("Too Early hover requests: "); message.append(tooEarlyHover.size());
		message.append(System.lineSeparator());
		tooEarlyHover.forEach(i -> {
			message.append("  Too Early ");
			message.append(i);
			message.append(System.lineSeparator());
		});
		message.append("Too Late hover requests: "); message.append(tooLateHover.size());
		message.append(System.lineSeparator());
		tooLateHover.forEach(i -> {
			message.append("  Too Late " );message.append(i);
			message.append(System.lineSeparator());
		});
		assertTrue(message.toString(), tooEarlyHover.isEmpty() && tooLateHover.isEmpty());
	}

	/**
	 * Sends a sequence of bulky updates to a slow server, and checks that
	 * (a) Dispatch does not block, but returns an async result 'immediately'
	 * (b) Dispatch does not occur on the UI thread
	 */
	@Test
	public void testBlockingServerDoesNotBlockUIThread() throws Exception {
		final var uiDispatchCount = new AtomicInteger();

		MockLanguageServer.INSTANCE.getInitializeResult().getCapabilities()
		.setTextDocumentSync(TextDocumentSyncKind.Incremental);
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				// No need for any special processing, but needs to be synchronized to
				// make server block if processing a
				return super.hover(position);
			}
		});

		final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent")), new Range(new Position(0,  0), new Position(0, 10)));
		MockLanguageServer.INSTANCE.setHover(hoverResponse);
		CompletableFuture<?> initial = CompletableFuture.completedFuture(null);

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();
		StyledText text = viewer.getTextWidget();
		Thread.sleep(1000);

		final long startTime = System.currentTimeMillis();

		final var bulkyText = new StringBuilder();

		// Construct a reasonably bulky payload for the document updates: if the
		// payload is small then buffering will mitigate any back-pressure from the server
		// (typically 8k for a unix pipe)
		for (int i = 0; i < 1000; i++) {
			bulkyText.append("Some Text; ");
		}

		final String content = bulkyText.toString();

		for (int i = 0; i < 10; i++) {
			final int current = i + 1;
			text.append(content + "\n");
			final var params = new HoverParams();
			final var position = new Position();
			position.setCharacter(current);
			position.setLine(0);
			params.setPosition(position);
			CompletableFuture<?> hoverFuture = LanguageServers.forDocument(document)
			.withCapability(ServerCapabilities::getHoverProvider)
			.collectAll(ls -> {
				try {
					// If this is non-null then we're running on a/the SWT event thread
					if (Display.getCurrent() != null) {
					uiDispatchCount.incrementAndGet();
				}
					return ls.getTextDocumentService().hover(params);
				} catch (Exception e) {

				}
				return CompletableFuture.completedFuture(null);

			});
			initial = CompletableFuture.allOf(initial, hoverFuture);
		}

		final long dispatchTime = System.currentTimeMillis() - startTime;

		initial.join();

		final long finishTime = System.currentTimeMillis() - startTime;

		assertTrue(String.format("Dispatch should not have blocked but took %d ms vs overall test time of %d ms", dispatchTime, finishTime), dispatchTime < 1000);
		assertEquals("Should not have been any messages dispatched on UI thread", 0, uiDispatchCount.get());
	}

	@Test
	public void testAnyMatchingIsNonBlocking() throws Exception {
		// test with no LS available
		long start = System.currentTimeMillis();
		assertFalse(LanguageServers.forProject(project).anyMatching());
		long duration = System.currentTimeMillis() - start;
		assertTrue("LanguageServers.anyMatching() took too long: " + duration + "ms", duration < 100);

		// test with one slow LS available
		MockLanguageServer.INSTANCE.setTimeToProceedQueries(5_000);
		var testFile1 = createUniqueTestFile(project, "");
		var editor1 = openEditor(testFile1);
		start = System.currentTimeMillis();
		try {
			assertTrue(LanguageServers.forProject(project).anyMatching());
			duration = System.currentTimeMillis() - start;
			assertTrue("LanguageServers.anyMatching() took too long: " + duration + "ms", duration < 100);
		} finally {
			editor1.getSite().getPage().closeEditor(editor1, false);
		}
	}

	@Test
	public void testNoMatchingServers() throws Exception {
		final var hoverResponse = new Hover(List.of(Either.forLeft("HoverContent")), new Range(new Position(0,  0), new Position(0, 10)));
		MockLanguageServer.INSTANCE.setHover(hoverResponse);

		IFile testFile = TestUtils.createUniqueTestFile(project, "");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		LanguageServerDocumentExecutor executor = LanguageServers.forDocument(document).withFilter(sc -> false);

		assertFalse("Should not have been any valid LS", executor.anyMatching());

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		Optional<?> result = executor.computeFirst(ls -> ls.getTextDocumentService().hover(params)).get(10, TimeUnit.SECONDS);
		assertFalse("Should not have had a result", result.isPresent());

		List<?> collectedResult = executor.collectAll(ls -> ls.getTextDocumentService().hover(params)).get(10, TimeUnit.SECONDS);
		assertTrue("Should not have had a result", collectedResult.isEmpty());

		List<CompletableFuture<Hover>> allResults = executor.computeAll(ls -> ls.getTextDocumentService().hover(params));
		for (CompletableFuture<Hover> f : allResults) {
			Hover h = f.get(10, TimeUnit.SECONDS);
			assertNull(h);
		}
	}

	@Test(expected=CompletionException.class)
	public void testComputeFirstBubblesException() throws Exception {
		MockLanguageServer.INSTANCE.setTextDocumentService(new MockTextDocumentService(MockLanguageServer.INSTANCE::buildMaybeDelayedFuture) {
			@Override
			public synchronized void didChange(DidChangeTextDocumentParams params) {
				super.didChange(params);
			}

			@Override
			public synchronized CompletableFuture<Hover> hover(HoverParams position) {
				final var result = new CompletableFuture<Hover>();
				result.completeExceptionally(new IllegalStateException("No hovering here"));
				return result;
			}
		});

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<Optional<String>> response =  LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.computeFirst(ls -> ls.getTextDocumentService().hover(params).thenApply(h -> h == null ? null : h.getContents().getLeft().get(0).getLeft()));

		response.join();
	}

	/**
	 * The LSExecutor request methods can optionally supply an ILSWrapper as well as the raw language server
	 * proxy to the consuming functions. This is intended to support constructing objects that need access to
	 * the same language server for follow-up calls
	 */
	@Test
	public void testWrapperWrapsSameLS() throws Exception {
		final var hoverResponse = new Hover(
				List.of(Either.forLeft("HoverContent")), new Range(new Position(0,  0), new Position(0, 10)));
		MockLanguageServer.INSTANCE.setHover(hoverResponse);

		IFile testFile = TestUtils.createUniqueTestFileMultiLS(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final var params = new HoverParams();
		final var position = new Position();
		position.setCharacter(10);
		position.setLine(0);
		params.setPosition(position);

		CompletableFuture<List<Pair<LanguageServerWrapper, LanguageServer>>> async = LanguageServers.forDocument(document)
				.withCapability(ServerCapabilities::getHoverProvider)
				.collectAll((w, ls) -> ls.getTextDocumentService().hover(params).thenApply(h -> Pair.of(w, ls)));

		final List<Pair<LanguageServerWrapper, LanguageServer>> result = async.join();

		final var matching = new AtomicInteger();

		assertEquals("Should have had two responses", 2, result.size());
		assertNotEquals("LS should have been different proxies", result.get(0).second(), result.get(1).second());
		result.forEach(p -> {
			p.first().execute(ls -> {
				if (ls == p.second()) {
					matching.incrementAndGet();
				}
				return CompletableFuture.completedFuture(null);
			}).join();
		});

		assertEquals("Wrapper should have used same LS", 2, matching.get());
	}

	/**
	 * Project-level executors work slightly differently: there's (currently) no direct way
	 * of associating a LS with a project directly, and you can't find out a server's capabilties
	 * until it has started, so LSP4e relies on a document within the project having previously
	 * triggered a server to start. A server may shut down after inactivity, but capabilities are
	 * still available. Candidate LS for a project-level operation may include only currently-running LS,
	 * or can restart any previously-started ones that match the filter.
	 */
	@Test
	public void testProjectExecutor() throws Exception {
		var testFile1 = createUniqueTestFile(project, "");
		var testFile2 = createUniqueTestFile(project, "lspt-different", "");

		var editor1 = openEditor(testFile1);
		var editor2 = openEditor(testFile2);

		final var serverCounter = new AtomicInteger();

		final List<String> serversForProject = LanguageServers.forProject(project).collectAll(ls -> CompletableFuture.completedFuture("Server" + serverCounter.incrementAndGet())).join();
		assertTrue(serversForProject.contains("Server1"));
		assertTrue(serversForProject.contains("Server2"));

		editor1.getSite().getPage().closeEditor(editor1, false);
		editor2.getSite().getPage().closeEditor(editor2, false);

		waitForCondition(5_000, () -> !hasActiveLanguageServers(MATCH_ALL));

		serverCounter.set(0);
		final List<String> serversForProject2 = LanguageServers.forProject(project).excludeInactive().collectAll(ls -> CompletableFuture.completedFuture("Server" + serverCounter.incrementAndGet())).join();
		assertTrue(serversForProject2.isEmpty());

		serverCounter.set(0);
		editor1 = openEditor(testFile1);
		final List<String> serversForProject3 = LanguageServers.forProject(project).excludeInactive().collectAll(ls -> CompletableFuture.completedFuture("Server" + serverCounter.incrementAndGet())).join();
		assertTrue(serversForProject3.contains("Server1"));
		assertFalse(serversForProject3.contains("Server2"));

		serverCounter.set(0);
		final List<String> serversForProject4 = LanguageServers.forProject(project).collectAll(ls -> CompletableFuture.completedFuture("Server" + serverCounter.incrementAndGet())).join();
		assertTrue(serversForProject4.contains("Server1"));
		assertTrue(serversForProject4.contains("Server2"));
	}

	@Test
	public void testGetDocument() throws Exception {
		IFile testFile = TestUtils.createUniqueTestFile(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		final IDocument document = viewer.getDocument();

		final LanguageServerDocumentExecutor executor = LanguageServers.forDocument(document);

		assertEquals(document, executor.getDocument());
	}

	@Test
	public void testCancellable() throws Exception {
		IFile testFile = TestUtils.createUniqueTestFile(project, "Here is some content");
		ITextViewer viewer = TestUtils.openTextViewer(testFile);
		Display display = viewer.getTextWidget().getDisplay();
		DisplayHelper.sleep(display, 2000);

		final IDocument document = viewer.getDocument();
		final LanguageServerDocumentExecutor executor = LanguageServers.forDocument(document);
		MockLanguageServer.INSTANCE.setTimeToProceedQueries(3000);

		// Test lsWrapper.execute() forwards cancellation
		LanguageServerWrapper lsWrapper = executor.computeFirst((wrapper, ls) -> CompletableFuture.completedFuture(wrapper)).get().get();
		CompletableFuture<?> request = lsWrapper.execute(ls -> ls.getTextDocumentService().references(new ReferenceParams()));
		DisplayHelper.sleep(viewer.getTextWidget().getDisplay(), 500);
		request.cancel(false);
		assertTrue(DisplayHelper.waitForCondition(display, 3000, () -> !MockConnectionProvider.cancellations.isEmpty()));

		// Test executor.computeFirst() forwards cancellation
		MockConnectionProvider.cancellations.clear();
		request = executor.computeFirst(ls -> ls.getTextDocumentService().references(new ReferenceParams()));
		DisplayHelper.sleep(viewer.getTextWidget().getDisplay(), 500);
		request.cancel(false);
		DisplayHelper.sleep(viewer.getTextWidget().getDisplay(), 100);
		assertTrue(DisplayHelper.waitForCondition(display, 3000, () -> !MockConnectionProvider.cancellations.isEmpty()));

		// Test executor.collectAll() forwards cancellation
		MockConnectionProvider.cancellations.clear();
		request = executor.collectAll(ls -> ls.getTextDocumentService().references(new ReferenceParams()));
		DisplayHelper.sleep(viewer.getTextWidget().getDisplay(), 500);
		request.cancel(false);
		DisplayHelper.sleep(viewer.getTextWidget().getDisplay(), 100);
		assertTrue(DisplayHelper.waitForCondition(display, 3000, () -> !MockConnectionProvider.cancellations.isEmpty()));

		// Test executor.computeAll() forwards cancellation
		MockConnectionProvider.cancellations.clear();
		@NonNull List<@NonNull CompletableFuture<@Nullable List<? extends Location>>> requests = executor.computeAll(ls -> ls.getTextDocumentService().references(new ReferenceParams()));
		DisplayHelper.sleep(viewer.getTextWidget().getDisplay(), 500);
		requests.forEach(r -> r.cancel(false));
		DisplayHelper.sleep(viewer.getTextWidget().getDisplay(), 100);
		assertTrue(DisplayHelper.waitForCondition(display, 3000, () -> !MockConnectionProvider.cancellations.isEmpty()));
	}
}
