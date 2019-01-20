/***********************************************************************************
 * MIT License                                                                     *
 *                                                                                 *
 * Copyright (c) 2018 Josh Larson                                                  *
 *                                                                                 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy    *
 * of this software and associated documentation files (the "Software"), to deal   *
 * in the Software without restriction, including without limitation the rights    *
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell       *
 * copies of the Software, and to permit persons to whom the Software is           *
 * furnished to do so, subject to the following conditions:                        *
 *                                                                                 *
 * The above copyright notice and this permission notice shall be included in all  *
 * copies or substantial portions of the Software.                                 *
 *                                                                                 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR      *
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,        *
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE     *
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER          *
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,   *
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE   *
 * SOFTWARE.                                                                       *
 ***********************************************************************************/
package me.joshlarson.jlcommon.network;

import me.joshlarson.jlcommon.utilities.Arguments;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLSession;
import java.io.Closeable;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.nio.ByteBuffer;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class SSLEngineWrapper implements Closeable {
	
	private static final int DEFAULT_BUFFER_SIZE = 1024;
	private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);
	
	private final SSLEngine engine;
	private final Consumer<Runnable> executor;
	private final IOCallback<ByteBuffer> sender;
	private final ByteBuffer localEncrypted;
	private final ByteBuffer remoteUnencrypted;
	private final ByteBuffer remoteEncrypted;
	private final AtomicBoolean handshakeCompleted;
	private final Semaphore handshakeLock;
	
	private IOCallback<ByteBuffer> readCallback;
	
	public SSLEngineWrapper(@NotNull SSLEngine engine, @NotNull SSLEngineWrapper.IOCallback<ByteBuffer> sender) {
		this(engine, DEFAULT_BUFFER_SIZE, sender);
	}
	
	public SSLEngineWrapper(@NotNull SSLEngine engine, int bufferSize, @NotNull SSLEngineWrapper.IOCallback<ByteBuffer> sender) {
		this(engine, bufferSize, Runnable::run, sender);
	}
	
	public SSLEngineWrapper(@NotNull SSLEngine engine, int bufferSize, @NotNull Consumer<Runnable> executor, @NotNull SSLEngineWrapper.IOCallback<ByteBuffer> sender) {
		this.engine = engine;
		this.executor = executor;
		this.sender = sender;
		SSLSession session = engine.getSession();
		this.localEncrypted = ByteBuffer.allocateDirect(session.getPacketBufferSize() + 50);
		this.remoteUnencrypted = ByteBuffer.allocateDirect(bufferSize);
		this.remoteEncrypted = ByteBuffer.allocateDirect(session.getPacketBufferSize() + 50);
		this.handshakeCompleted = new AtomicBoolean(false);
		this.handshakeLock = new Semaphore(0);
		
		this.readCallback = null;
	}
	
	public void setReadCallback(@Nullable IOCallback<ByteBuffer> readCallback) {
		this.readCallback = readCallback;
	}
	
	public void read(byte [] incomingData) throws IOException {
		read(ByteBuffer.wrap(incomingData));
	}
	
	public void read(byte [] incomingData, int offset, int length) throws IOException {
		read(ByteBuffer.wrap(incomingData, offset, length));
	}
	
	public void read(ByteBuffer incomingData) throws IOException {
		int actualLimit = incomingData.limit();
		while (incomingData.hasRemaining()) {
			int maxSend = Math.min(remoteEncrypted.remaining(), incomingData.remaining());
			incomingData.limit(incomingData.position() + maxSend);
			remoteEncrypted.put(incomingData);
			incomingData.limit(actualLimit);
			handleLargeUnwrap();
		}
	}
	
	public int write(byte [] outgoingData) throws IOException {
		return write(ByteBuffer.wrap(outgoingData));
	}
	
	public int write(byte [] outgoingData, int offset, int length) throws IOException {
		return write(ByteBuffer.wrap(outgoingData, offset, length));
	}
	
	public int write(ByteBuffer outgoingData) throws IOException {
		if (engine.isOutboundDone())
			return 0;
		int remaining = outgoingData.remaining();
		try {
			handshakeLock.acquire();
			handleLargeWrap(outgoingData);
		} catch (InterruptedException e) {
			// Ignored
		} finally {
			handshakeLock.release();
		}
		return remaining - outgoingData.remaining();
	}
	
	public void startHandshake() throws IOException {
		engine.beginHandshake();
		handleResult(handleWrap());
	}
	
	public void close() throws IOException {
		engine.closeOutbound();
		handleStatus(engine.getHandshakeStatus());
	}
	
	public boolean isConnectionInitialized() {
		return handshakeCompleted.get();
	}
	
	private void handleStatus(HandshakeStatus status) throws IOException {
		switch (status) {
			case NEED_TASK: {
				while (status == HandshakeStatus.NEED_TASK) {
					Runnable task;
					while ((task = engine.getDelegatedTask()) != null) {
						executor.accept(task);
					}
					status = engine.getHandshakeStatus();
				}
				handleStatus(status);
				break;
			}
			case NEED_WRAP:
				handleLargeWrap(EMPTY_BUFFER);
				break;
			case NEED_UNWRAP:
				handleLargeUnwrap();
				break;
			default:
				break;
		}
	}
	
	private SSLEngineResult.Status handleResult(SSLEngineResult result) throws IOException {
		SSLEngineResult.Status resultStatus = result.getStatus();
		HandshakeStatus handshakeStatus = result.getHandshakeStatus();
		if (handshakeStatus == HandshakeStatus.FINISHED) {
			if (!handshakeCompleted.getAndSet(true))
				handshakeLock.release();
		} else if (handshakeStatus != HandshakeStatus.NOT_HANDSHAKING) {
			if (handshakeStatus == HandshakeStatus.NEED_UNWRAP && (resultStatus == Status.BUFFER_UNDERFLOW))
				return resultStatus;
			handleStatus(handshakeStatus);
		}
		if (resultStatus == Status.CLOSED) {
			close();
			throw new SSLClosedException();
		}
		return resultStatus;
	}
	
	private void handleLargeUnwrap() throws IOException {
		SSLEngineResult result = handleUnwrap(remoteUnencrypted);
		if (result.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING && (result.getHandshakeStatus() != HandshakeStatus.NEED_UNWRAP || result.getStatus() != Status.BUFFER_OVERFLOW)) {
			handleResult(result);
			return;
		}
		int currentSize = BufferContainer.calculateBufferSize(remoteUnencrypted) + 1;
		ByteBuffer tmp = null;
		do {
			if (tmp != null)
				BufferContainer.INSTANCE.release(tmp);
			tmp = BufferContainer.INSTANCE.rent(currentSize);
			
			result = handleUnwrap(tmp);
			if (result.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING && (result.getHandshakeStatus() != HandshakeStatus.NEED_UNWRAP || result.getStatus() != Status.BUFFER_OVERFLOW)) {
				BufferContainer.INSTANCE.release(tmp);
				handleResult(result);
				return;
			}
			currentSize++;
		} while (result.getStatus() == Status.BUFFER_OVERFLOW && currentSize <= 31);
		BufferContainer.INSTANCE.release(tmp);
	}
	
	private SSLEngineResult handleUnwrap(ByteBuffer output) throws IOException {
		remoteEncrypted.flip();
		output.clear();
		SSLEngineResult result = engine.unwrap(remoteEncrypted, output);
		output.flip();
		remoteEncrypted.compact();
		if (output.hasRemaining()) {
			IOCallback<ByteBuffer> callback = this.readCallback;
			if (callback != null)
				callback.accept(output);
		}
		return result;
	}
	
	private void handleLargeWrap(ByteBuffer outboundData) throws IOException {
		SSLEngineResult result = handleWrap(outboundData, localEncrypted);
		if (result.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING && (result.getHandshakeStatus() != HandshakeStatus.NEED_WRAP || result.getStatus() != Status.BUFFER_OVERFLOW)) {
			handleResult(result);
			return;
		}
		int currentSize = BufferContainer.calculateBufferSize(localEncrypted) + 1;
		ByteBuffer tmp = null;
		do {
			if (tmp != null)
				BufferContainer.INSTANCE.release(tmp);
			tmp = BufferContainer.INSTANCE.rent(currentSize);
			
			result = handleWrap(outboundData, tmp);
			if (result.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING && (result.getHandshakeStatus() != HandshakeStatus.NEED_WRAP || result.getStatus() != Status.BUFFER_OVERFLOW)) {
				BufferContainer.INSTANCE.release(tmp);
				handleResult(result);
				return;
			}
			currentSize++;
		} while (result.getStatus() == Status.BUFFER_OVERFLOW && currentSize <= 31);
		BufferContainer.INSTANCE.release(tmp);
	}
	
	private SSLEngineResult handleWrap() throws IOException {
		return handleWrap(EMPTY_BUFFER, localEncrypted);
	}
	
	private SSLEngineResult handleWrap(ByteBuffer outboundData, ByteBuffer destination) throws IOException {
		SSLEngineResult result = engine.wrap(outboundData, destination);
		destination.flip();
		sender.accept(destination);
		destination.compact();
		return result;
	}
	
	@FunctionalInterface
	public interface IOCallback<T> {
		void accept(T data) throws IOException;
	}
	
	private enum BufferContainer {
		INSTANCE;
		
		private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocateDirect(16 * 1024);
		private final Map<Integer, Deque<Reference<ByteBuffer>>> buffers;
		
		BufferContainer() {
			this.buffers = new ConcurrentHashMap<>();
		}
		
		/**
		 * Returns a buffer with at least the given size
		 * @param expSize the size (base 2) of the buffer - ex. expSize=2 returns a buffer of size 32 bytes
		 * @return a byte buffer with the given size
		 */
		public ByteBuffer rent(int expSize) {
			Arguments.validate(expSize >= 0, "expSize must be positive");
			Deque<Reference<ByteBuffer>> cache = buffers.get(expSize);
			if (cache != null) {
				while (!cache.isEmpty()) {
					Reference<ByteBuffer> ref = cache.pollLast();
					if (ref == null)
						break;
					ByteBuffer buf = ref.get();
					if (buf != null)
						return buf;
				}
			}
			return ByteBuffer.allocateDirect(1 << expSize);
		}
		
		public void release(ByteBuffer data) {
			int expSize = calculateBufferSize(data);
			buffers.computeIfAbsent(expSize, e -> new ConcurrentLinkedDeque<>()).add(new SoftReference<>(wipeBuffer(data)));
		}
		
		public static int calculateBufferSize(ByteBuffer data) {
			return 31 - Integer.numberOfLeadingZeros(data.capacity());
		}
		
		private static ByteBuffer wipeBuffer(ByteBuffer data) {
			data.clear();
			synchronized (EMPTY_BUFFER) {
				final ByteBuffer emptyBuffer = EMPTY_BUFFER;
				while (data.hasRemaining()) {
					int limit = Math.min(emptyBuffer.capacity(), data.remaining());
					emptyBuffer.clear();
					emptyBuffer.limit(limit);
					data.put(emptyBuffer);
					assert emptyBuffer.limit() == limit;
				}
			}
			data.clear();
			return data;
		}
		
	}
	
	public static class SSLClosedException extends IOException {
		
	}
	
}
