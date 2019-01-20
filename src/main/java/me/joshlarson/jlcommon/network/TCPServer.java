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

import me.joshlarson.jlcommon.annotations.Unused;
import me.joshlarson.jlcommon.concurrency.BasicThread;
import me.joshlarson.jlcommon.data.FileBackedBuffer;
import me.joshlarson.jlcommon.log.Log;
import me.joshlarson.jlcommon.network.SSLEngineWrapper.SSLClosedException;
import me.joshlarson.jlcommon.network.TCPServer.TCPSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLEngine;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

public class TCPServer<T extends TCPSession> {
	
	private final Map<SocketChannel, T> channelToSession;
	private final List<T> sessions;
	private final List<T> sessionsImmutable;
	private final BasicThread listener;
	private final AtomicBoolean running;
	private final InetSocketAddress addr;
	private final Function<SocketChannel, T> sessionCreator;
	private final TCPServerCallback<T> serverCallback;
	private final ByteBuffer buffer;
	
	private ServerSocketChannel channel;
	
	TCPServer(@NotNull InetSocketAddress addr, int bufferSize, @NotNull Function<SocketChannel, T> sessionCreator, @Nullable TCPServerCallback<T> serverCallback) {
		if (bufferSize < 0)
			throw new IllegalArgumentException("bufferSize is negative");
		this.channelToSession = new ConcurrentHashMap<>();
		this.sessions = new CopyOnWriteArrayList<>();
		this.sessionsImmutable = Collections.unmodifiableList(sessions);
		this.listener = new BasicThread("tcpserver-listener-" + addr.getPort(), this::runListener);
		this.running = new AtomicBoolean(false);
		this.addr = addr;
		this.channel = null;
		this.sessionCreator = sessionCreator;
		this.serverCallback = serverCallback;
		this.buffer = ByteBuffer.allocateDirect(bufferSize);
	}
	
	public int getPort() {
		return channel.socket().getLocalPort();
	}
	
	public void bind() throws IOException {
		bind(50);
	}
	
	public void bind(int backlog) throws IOException {
		assert !running.get() : "TCPServer is already running";
		if (running.getAndSet(true))
			return;
		channel = ServerSocketChannel.open();
		channel.bind(addr, backlog);
		channel.configureBlocking(false);
		listener.start();
	}
	
	/**
	 * Attempts to disconnect the session with the specified ID
	 * @param sessionId the session ID to disconnect
	 * @return TRUE if successfully disconnected, FALSE otherwise
	 */
	public boolean disconnect(long sessionId) {
		for (T session : sessions) {
			if (session.getSessionId() == sessionId) {
				disconnect(session);
				return true;
			}
		}
		return false;
	}
	
	public boolean disconnect(@NotNull T session) {
		if (channelToSession.remove(session.getChannel()) != null) {
			sessions.remove(session);
			session.close();
			onDisconnected(session);
			return true;
		}
		return false;
	}
	
	public boolean disconnect(@NotNull SocketChannel sc) {
		T session = channelToSession.get(sc);
		if (session != null)
			return disconnect(session);
		return false;
	}
	
	@NotNull
	public Collection<T> getSessions() {
		return sessionsImmutable;
	}
	
	@Nullable
	public T getSession(long sessionId) {
		for (T session : sessions) {
			if (session.getSessionId() == sessionId) {
				return session;
			}
		}
		return null;
	}
	
	@Nullable
	public T getSession(@NotNull SocketChannel sc) {
		return channelToSession.get(sc);
	}
	
	public boolean close() {
		assert running.get() : "TCPServer isn't running";
		if (!running.getAndSet(false))
			return true;
		safeClose(channel);
		listener.stop(false);
		return listener.awaitTermination(1000);
	}
	
	private void runListener() {
		try (Selector selector = Selector.open()) {
			channel.register(selector, SelectionKey.OP_ACCEPT);
			while (channel.isOpen()) {
				selector.select(this::handle);
			}
		} catch (IOException e) {
			Log.e(e);
		}
	}
	
	private void handle(SelectionKey key) {
		if (key.isAcceptable()) {
			accept(key.selector());
			return;
		}
		
		final Object attachment = key.attachment();
		if (!(attachment instanceof TCPSession))
			return;
		@SuppressWarnings("unchecked") // guaranteed on registration
		final T session = (T) attachment;
		final SocketChannel sc = session.getChannel();
		
		try {
			if (!key.isValid())
				disconnect(session);
			if (key.isValid() && key.isReadable())
				read(session, sc, key);
			if (key.isValid() && key.isWritable())
				write(session, sc, key);
		} catch (ClosedChannelException | CancelledKeyException | SSLClosedException e) {
			// Ignored
			key.cancel();
			disconnect(session);
		} catch (IOException e) {
			key.cancel();
			onError(session, e);
			disconnect(session);
		} catch (Throwable t) {
			onError(session, t);
		}
	}
	
	private void accept(Selector selector) {
		try {
			SocketChannel sc = channel.accept();
			assert sc != null : "TCPServer did not accept the connection successfully";
			sc.configureBlocking(false);
			T session = sessionCreator.apply(sc);
			if (session == null) {
				Log.w("Session creator for TCPServer-%d created a null session!", addr.getPort());
				safeClose(sc);
				return;
			}
			if (session.getChannel() != sc) {
				Log.w("Session creator for TCPServer-%d created a session with an invalid channel!", addr.getPort());
				safeClose(sc);
				return;
			}
			
			try {
				SelectionKey key = sc.register(selector, SelectionKey.OP_READ, session);
				((TCPSession) session).initializeSelector(key);
				
				channelToSession.put(sc, session);
				sessions.add(session);
				onConnected(session);
			} catch (IOException e) {
				safeClose(sc);
				onError(session, e);
			} catch (Throwable t) {
				onError(session, t);
			}
		} catch (ClosedChannelException e) {
			// Ignored
		} catch (Throwable t) {
			Log.w("TCPServer - exception in accept(): %s: %s", t.getClass().getName(), t.getMessage());
		}
	}
	
	private void read(T session, SocketChannel sc, SelectionKey key) throws IOException {
		int n;
		do {
			buffer.clear();
			n = sc.read(buffer);
			if (n > 0) {
				buffer.flip();
				session.onIncomingDataRaw(buffer);
			}
		} while (n > 0);
		if (n < 0) {
			disconnect(session);
			key.cancel();
		}
	}
	
	private void write(T session, SocketChannel sc, SelectionKey key) throws IOException {
		ByteBuffer writeBuffer = ((TCPSession) session).getWriteBuffer();
		synchronized (((TCPSession) session).getWriteBufferMutex()) {
			writeBuffer.flip();
			sc.write(writeBuffer);
			writeBuffer.compact();
			key.interestOpsAnd(~SelectionKey.OP_WRITE);
		}
	}
	
	private void onConnected(T session) {
		try {
			if (serverCallback != null)
				serverCallback.onConnected(session);
		} catch (Throwable t) {
			onError(session, t);
		}
		try {
			session.onConnected();
		} catch (Throwable t) {
			onError(session, t);
		}
	}
	
	private void onDisconnected(T session) {
		try {
			if (serverCallback != null)
				serverCallback.onDisconnected(session);
		} catch (Throwable t) {
			onError(session, t);
		}
		try {
			session.onDisconnected();
		} catch (Throwable t) {
			onError(session, t);
		}
	}
	
	private void onError(T session, Throwable error) {
		try {
			if (serverCallback != null)
				serverCallback.onError(session, error);
		} catch (Throwable t) {
			// Ignored - could create recursive onError call
		}
		try {
			session.onError(error);
		} catch (Throwable t) {
			// Ignored - could create recursive onError call
		}
	}
	
	private static void safeClose(@NotNull Closeable c) {
		try {
			c.close();
		} catch (Exception e) {
			// Ignored - as long as it's closed
		}
	}
	
	public interface TCPServerCallback<T> {
		default void onConnected(T session) {}
		default void onDisconnected(T session) {}
		default void onError(T session, Throwable t) {}
	}
	
	public static <T extends TCPSession> TCPServerBuilder<T> builder() {
		return new TCPServerBuilder<>();
	}
	
	public abstract static class TCPSession {
		
		private static final AtomicLong GLOBAL_SESSION_ID = new AtomicLong(0);
		
		private final SocketChannel sc;
		private final SocketAddress addr;
		private final FileBackedBuffer fbb;
		private final long sessionId;
		private SelectionKey selectionKey;
		
		protected TCPSession(@NotNull SocketChannel sc) {
			this(sc, 1024*1024);
		}
		
		protected TCPSession(@NotNull SocketChannel sc, int maxOutbound) {
			this.sc = sc;
			this.selectionKey = null;
			this.sessionId = GLOBAL_SESSION_ID.incrementAndGet();
			
			try {
				this.fbb = FileBackedBuffer.create("tcpserverfbb", ".bin", maxOutbound);
				this.addr = sc.getRemoteAddress();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		
		protected abstract void onConnected();
		
		protected abstract void onDisconnected();
		
		/**
		 * Called when an error occurs related to this session
		 * @param t the throwable that occured
		 */
		protected void onError(Throwable t) {
			
		}
		
		/**
		 * Returns a globally unique session id for this particular connection
		 *
		 * @return the unique session id
		 */
		protected final long getSessionId() {
			return sessionId;
		}
		
		/**
		 * Returns the socket channel associated with this session
		 *
		 * @return the socket channel
		 */
		@NotNull
		protected final SocketChannel getChannel() {
			return sc;
		}
		
		/**
		 * Returns the remote address that this socket is/was connected to
		 *
		 * @return the remote socket address
		 */
		@Unused(reason = "API")
		@NotNull
		protected final SocketAddress getRemoteAddress() {
			return addr;
		}
		
		@Unused(reason = "API")
		protected int writeToChannel(@NotNull ByteBuffer data) throws IOException {
			int remainingBefore = data.remaining();
			synchronized (getWriteBufferMutex()) {
				fbb.getBuffer().put(data);
				requestWrite();
			}
			if (data.hasRemaining())
				throw new BufferOverflowException();
			return remainingBefore;
		}
		
		@Unused(reason = "API")
		protected int writeToChannel(@NotNull byte[] data) throws IOException {
			return writeToChannel(ByteBuffer.wrap(data));
		}
		
		@Unused(reason = "API")
		protected int writeToChannel(@NotNull byte[] data, int offset, int length) throws IOException {
			return writeToChannel(ByteBuffer.wrap(data, offset, length));
		}
		
		protected void close() {
			safeClose(sc);
			closeWriteBuffer();
		}
		
		protected abstract void onIncomingData(@NotNull ByteBuffer data);
		
		protected void onIncomingDataRaw(ByteBuffer data) throws IOException {
			onIncomingData(data);
		}
		
		private void initializeSelector(SelectionKey selectionKey) {
			this.selectionKey = selectionKey;
			if (fbb.getBuffer().position() > 0)
				requestWrite();
		}
		
		private Object getWriteBufferMutex() {
			return fbb;
		}
		
		private ByteBuffer getWriteBuffer() {
			return fbb.getBuffer();
		}
		
		private void closeWriteBuffer() {
			fbb.close();
		}
		
		private void requestWrite() {
			SelectionKey selectionKey = this.selectionKey;
			if (selectionKey != null) {
				selectionKey.interestOpsOr(SelectionKey.OP_WRITE);
				selectionKey.selector().wakeup();
			}
		}
		
	}
	
	public abstract static class SecureTCPSession extends TCPSession {
		
		private final SSLEngineWrapper security;
		
		protected SecureTCPSession(@NotNull SocketChannel sc, @NotNull SSLEngine engine, int bufferSize, @NotNull Consumer<Runnable> executor) {
			super(sc);
			this.security = new SSLEngineWrapper(engine, bufferSize, executor, super::writeToChannel);
			security.setReadCallback(this::onIncomingData);
		}
		
		protected void close() {
			safeClose(security);
			super.close();
		}
		
		/**
		 * Determines whether or not the SSL handshake has been completed
		 * @return TRUE if this session has been initialized, FALSE otherwise
		 */
		public boolean isConnected() {
			return security.isConnectionInitialized();
		}
		
		@Override
		protected int writeToChannel(@NotNull ByteBuffer data) throws IOException {
			return security.write(data);
		}
		
		@Override
		protected int writeToChannel(@NotNull byte[] data) throws IOException {
			return security.write(data);
		}
		
		@Override
		protected int writeToChannel(@NotNull byte[] data, int offset, int length) throws IOException {
			return security.write(data, offset, length);
		}
		
		@Override
		protected void onIncomingDataRaw(@NotNull ByteBuffer data) throws IOException {
			security.read(data);
		}
		
		protected abstract void onIncomingData(@NotNull ByteBuffer data);
		
	}
	
}
