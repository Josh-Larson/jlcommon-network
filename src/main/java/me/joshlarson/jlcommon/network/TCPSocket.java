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

import me.joshlarson.jlcommon.concurrency.BasicThread;
import me.joshlarson.jlcommon.data.FileBackedBuffer;
import me.joshlarson.jlcommon.network.SSLEngineWrapper.SSLClosedException;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

public class TCPSocket {
	
	private static final Pattern SOCKET_CLOSED_MESSAGE = Pattern.compile(".*socket.+closed.*", Pattern.CASE_INSENSITIVE);
	
	private final BasicThread listener;
	private final AtomicReference<InetSocketAddress> address;
	private final AtomicReference<SocketState> state;
	private final ByteBuffer buffer;
	private final Object writeBufferMutex;
	private FileBackedBuffer writeBuffer;
	private SocketChannel socket;
	private Selector selector;
	private TCPSocketCallback callback;
	
	public TCPSocket(InetSocketAddress address, int bufferSize) {
		this.listener = new BasicThread("tcpsocket-listener-"+address, this::listener);
		this.address = new AtomicReference<>(address);
		this.state = new AtomicReference<>(SocketState.CLOSED);
		this.writeBufferMutex = new Object();
		this.buffer = ByteBuffer.allocateDirect(bufferSize);
		
		this.writeBuffer = null;
		this.socket = null;
		this.selector = null;
		this.callback = null;
	}
	
	public TCPSocket(int bufferSize) {
		this(null, bufferSize);
	}
	
	public TCPSocket() {
		this(1024);
	}
	
	public int getBufferSize() {
		return buffer.capacity();
	}
	
	public InetSocketAddress getRemoteAddress() {
		return address.get();
	}
	
	public void setRemoteAddress(InetSocketAddress address) {
		this.address.set(address);
	}
	
	public Socket getSocket() {
		return socket.socket();
	}
	
	public SocketChannel getSocketChannel() {
		return socket;
	}
	
	public boolean isAlive() {
		return listener.isExecuting();
	}
	
	public boolean isConnected() {
		SocketChannel socket = this.socket;
		return socket != null && socket.isConnected();
	}
	
	public void setCallback(TCPSocketCallback callback) {
		this.callback = callback;
	}
	
	public TCPSocketCallback getCallback() {
		return callback;
	}
	
	public void removeCallback() {
		this.callback = null;
	}
	
	public void createConnection() {
		if (!checkAndSetState(SocketState.CLOSED, SocketState.CREATED))
			return;
		try {
			this.writeBuffer = FileBackedBuffer.create("tcpsocketfbb", ".bin", 1024*1024);
			this.socket = createSocket();
		} catch (IOException e) {
			checkAndSetState(SocketState.CREATED, SocketState.CLOSED);
			throw new RuntimeException(e);
		}
	}
	
	public void startConnection() throws IOException {
		try {
			if (!checkAndSetState(SocketState.CREATED, SocketState.CONNECTING))
				return;
			socket.connect(getRemoteAddress());
			if (!socket.finishConnect())
				throw new IOException("Failed to connect");
		} catch (IOException e) {
			checkAndSetState(SocketState.CONNECTING, SocketState.CLOSED);
			socket.close();
			throw e;
		}
		
		socket.configureBlocking(false);
		listener.start();
		if (!checkAndSetState(SocketState.CONNECTING, SocketState.CONNECTED))
			return;
		
		onConnect();
	}
	
	public void connect() throws IOException {
		createConnection();
		startConnection();
	}
	
	public boolean disconnect() {
		if (!checkAndSetState(SocketState.CONNECTED, SocketState.CLOSED))
			return true;
		try {
			socket.close();
			
			listener.awaitTermination(1000);
			onDisconnect();
			return listener.awaitTermination(0);
		} catch (Throwable t) {
			onError(t);
		}
		return false;
	}
	
	public int send(ByteBuffer data) {
		FileBackedBuffer fbb = this.writeBuffer;
		if (fbb == null)
			return -1;
		int remainingBefore = data.remaining();
		synchronized (writeBufferMutex) {
			ByteBuffer writeBuffer = fbb.getBuffer();
			writeBuffer.put(data);
		}
		if (data.hasRemaining())
			throw new BufferOverflowException();
		Selector selector = this.selector;
		if (selector != null) {
			try {
				socket.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);
				selector.wakeup();
			} catch (IOException e) {
				onError(e);
			}
		}
		return remainingBefore;
	}
	
	public int send(byte [] data) {
		return send(ByteBuffer.wrap(data));
	}
	
	public int send(byte [] data, int offset,  int length) {
		return send(ByteBuffer.wrap(data, offset, length));
	}
	
	protected SocketChannel createSocket() throws IOException {
		return SocketChannel.open();
	}
	
	protected void onConnect() {
		TCPSocketCallback callback = this.callback;
		if (callback != null)
			callback.onConnected(this);
	}
	
	protected void onDisconnect() {
		TCPSocketCallback callback = this.callback;
		if (callback != null)
			callback.onDisconnected(this);
	}
	
	protected void onRead(ByteBuffer data) throws IOException {
		TCPSocketCallback callback = this.callback;
		if (callback != null)
			callback.onIncomingData(this, data);
	}
	
	protected void onError(Throwable t) {
		TCPSocketCallback callback = this.callback;
		if (callback != null)
			callback.onError(this, t);
	}
	
	private void listener() {
		try (Selector selector = Selector.open()) {
			this.selector = selector;
			SelectionKey key = socket.register(selector, SelectionKey.OP_READ);
			if (writeBuffer.getBuffer().position() > 0)
				key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
			
			while (socket != null && socket.isOpen() && selector.isOpen()) {
				selector.select(this::handle);
			}
		} catch (EOFException | ClosedChannelException e) {
			// We're all fine here - just means the socket closed normally
		} catch (IOException e) {
			String message = e.getMessage();
			if (message != null && SOCKET_CLOSED_MESSAGE.matcher(message).matches())
				return;
			onError(e);
		} catch (Throwable t) {
			onError(t);
		} finally {
			this.selector = null;
			disconnect();
		}
	}
	
	private void handle(SelectionKey key) {
		try {
			if (key.isValid() && key.isReadable()) {
				read();
			}
			if (key.isValid() && key.isWritable()) {
				write();
				key.interestOps(SelectionKey.OP_READ);
			}
		} catch (ClosedChannelException | EOFException | SSLClosedException e) {
			// Ignored
			disconnect();
		} catch (IOException e) {
			onError(e);
		}
	}
	
	private void read() throws IOException {
		buffer.clear();
		if (socket.read(buffer) < 0)
			throw new EOFException("End of stream");
		buffer.flip();
		onRead(buffer);
	}
	
	private void write() throws IOException {
		FileBackedBuffer buffer = this.writeBuffer;
		assert buffer != null;
		synchronized (writeBufferMutex) {
			ByteBuffer writeBuffer = buffer.getBuffer();
			
			writeBuffer.flip();
			socket.write(writeBuffer);
			writeBuffer.compact();
		}
	}
	
	/**
	 * Checks the current state to see if it matches the expected, and if so, changes it to the new state. If not, it fails the assertion
	 * @param expected the expected state
	 * @param state the new state
	 */
	private boolean checkAndSetState(SocketState expected, SocketState state) {
		Objects.requireNonNull(expected, "Expected state cannot be null!");
		Objects.requireNonNull(state, "New state cannot be null!");
		return this.state.compareAndSet(expected, state);
	}
	
	public interface TCPSocketCallback {
		void onConnected(TCPSocket socket);
		void onDisconnected(TCPSocket socket);
		void onIncomingData(TCPSocket socket, ByteBuffer data);
		void onError(TCPSocket socket, Throwable t);
	}
	
	private enum SocketState {
		CLOSED,
		CREATED,
		CONNECTING,
		CONNECTED
	}
	
}
