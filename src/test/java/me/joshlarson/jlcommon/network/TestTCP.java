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

import me.joshlarson.jlcommon.concurrency.ThreadPool;
import me.joshlarson.jlcommon.log.Log;
import me.joshlarson.jlcommon.log.log_wrapper.ConsoleLogWrapper;
import me.joshlarson.jlcommon.network.TCPServer.SecureTCPSession;
import me.joshlarson.jlcommon.network.TCPServer.TCPSession;
import me.joshlarson.jlcommon.network.TCPSocket.TCPSocketCallback;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.net.ssl.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

@RunWith(JUnit4.class)
public class TestTCP {
	
	@Before
	public void init() {
		Log.addWrapper(new ConsoleLogWrapper());
	}
	
	@After
	public void term() {
		Log.clearWrappers();
	}
	
	@Test
	public void testEncryptedServer() throws IOException, NoSuchAlgorithmException, KeyManagementException {
		SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
		sslContext.init(null, new TrustManager[]{new X509TrustManager() {
			@Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
			@Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
			@Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
		}}, new SecureRandom());
		SSLEngine serverEngine = sslContext.createSSLEngine();
		SSLEngine clientEngine = sslContext.createSSLEngine();
		clientEngine.setEnabledCipherSuites(clientEngine.getSupportedCipherSuites());
		serverEngine.setEnabledCipherSuites(serverEngine.getSupportedCipherSuites());
		serverEngine.setUseClientMode(false);
		clientEngine.setUseClientMode(true);
		
		ThreadPool threadPool = new ThreadPool(4, "test-encrypted-server-%d");
		threadPool.start();
		
		AtomicBoolean serverConnection = new AtomicBoolean(false);
		AtomicBoolean clientConnection = new AtomicBoolean(false);
		AtomicReference<TCPSession> sess = new AtomicReference<>(null);
		Queue<byte[]> dataQueue = new ArrayDeque<>();
		
		TCPServer<TCPSession> server = TCPServer.builder().setBufferSize(128).setSessionCreator(c -> new SecureTCPSession(c, serverEngine, 1024, threadPool::execute) {
			
			protected void onConnected() {
				sess.set(this);
				serverConnection.set(true);
			}
			
			protected void onDisconnected() { serverConnection.set(false); }
			
			protected void onIncomingData(@NotNull ByteBuffer buffer) {
				byte[] data = new byte[buffer.remaining()];
				buffer.get(data);
				dataQueue.add(data);
			}
			
			protected void onError(Throwable t) {
				Log.e(t);
				Assert.fail();
			}
		}).createTCPServer();
		
		server.bind();
		
		TCPSocket socket = new SecureTCPSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), clientEngine, threadPool::execute);
		socket.setCallback(new TCPSocketCallback() {
			public void onConnected(TCPSocket socket) { clientConnection.set(true); }
			public void onDisconnected(TCPSocket socket) { clientConnection.set(false); }
			public void onIncomingData(TCPSocket socket, ByteBuffer buffer) { byte [] data = new byte[buffer.remaining()]; buffer.get(data); dataQueue.add(data); }
			public void onError(TCPSocket socket, Throwable t) {Log.e(t); Assert.fail(); }
		});
		
		Assert.assertFalse(serverConnection.get());
		Assert.assertFalse(clientConnection.get());
		Assert.assertTrue(dataQueue.isEmpty());
		
		socket.connect();
		
		waitForCondition(1000, () -> serverConnection.get() && clientConnection.get());
		
		Assert.assertNotNull(sess.get());
		Assert.assertTrue(serverConnection.get());
		Assert.assertTrue(clientConnection.get());
		Assert.assertTrue(dataQueue.isEmpty());
		
		// Client -> Server
		Assert.assertEquals(4, socket.send(new byte[]{1, 2, 3, 4}));
		waitForCondition(100, () -> !dataQueue.isEmpty());
		Assert.assertArrayEquals(new byte[]{1,2,3,4}, dataQueue.poll());
		
		// Server -> Client
		Assert.assertEquals(4, sess.get().writeToChannel(new byte[]{1, 2, 3, 4}));
		waitForCondition(100, () -> !dataQueue.isEmpty());
		Assert.assertArrayEquals(new byte[]{1,2,3,4}, dataQueue.poll());
		
		Assert.assertTrue(serverConnection.get());
		Assert.assertTrue(clientConnection.get());
		
		threadPool.stop(true);
		threadPool.awaitTermination(1000);
	}
	
	@Test
	public void testUnencryptedServer() throws IOException {
		AtomicBoolean serverConnection = new AtomicBoolean(false);
		AtomicBoolean clientConnection = new AtomicBoolean(false);
		AtomicReference<TCPSession> sess = new AtomicReference<>(null);
		Queue<byte[]> dataQueue = new ArrayDeque<>();
		
		TCPServer<TCPSession> server = TCPServer.builder().setBufferSize(128).setSessionCreator(c -> new TCPSession(c) {
			
			protected void onConnected() {
				sess.set(this);
				serverConnection.set(true);
			}
			
			protected void onDisconnected() { serverConnection.set(false); }
			
			protected void onIncomingData(@NotNull ByteBuffer buffer) {
				byte[] data = new byte[buffer.remaining()];
				buffer.get(data);
				dataQueue.add(data);
			}
			
			protected void onError(Throwable t) {
				Log.e(t);
				Assert.fail();
			}
		}).createTCPServer();
		
		server.bind();
		
		TCPSocket socket = new TCPSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), 128);
		socket.setCallback(new TCPSocketCallback() {
			public void onConnected(TCPSocket socket) { clientConnection.set(true); }
			public void onDisconnected(TCPSocket socket) { clientConnection.set(false); }
			public void onIncomingData(TCPSocket socket, ByteBuffer buffer) { byte [] data = new byte[buffer.remaining()]; buffer.get(data); dataQueue.add(data); }
			public void onError(TCPSocket socket, Throwable t) {Log.e(t); Assert.fail(); }
		});
		
		Assert.assertFalse(serverConnection.get());
		Assert.assertFalse(clientConnection.get());
		Assert.assertTrue(dataQueue.isEmpty());
		
		socket.connect();
		
		waitForCondition(1000, () -> serverConnection.get() && clientConnection.get());
		
		Assert.assertNotNull(sess.get());
		Assert.assertTrue(serverConnection.get());
		Assert.assertTrue(clientConnection.get());
		Assert.assertTrue(dataQueue.isEmpty());
		
		// Client -> Server
		Assert.assertEquals(4, socket.send(new byte[]{1, 2, 3, 4}));
		waitForCondition(100, () -> !dataQueue.isEmpty());
		Assert.assertArrayEquals(new byte[]{1,2,3,4}, dataQueue.poll());
		
		// Server -> Client
		Assert.assertEquals(4, sess.get().writeToChannel(new byte[]{1, 2, 3, 4}));
		waitForCondition(100, () -> !dataQueue.isEmpty());
		Assert.assertArrayEquals(new byte[]{1,2,3,4}, dataQueue.poll());
		
		Assert.assertTrue(serverConnection.get());
		Assert.assertTrue(clientConnection.get());
	}
	
	@Test
	public void testEncryptedManyClients() throws IOException, NoSuchAlgorithmException, KeyManagementException {
		SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
		sslContext.init(null, new TrustManager[]{new X509TrustManager() {
			@Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
			@Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
			@Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
		}}, new SecureRandom());
		
		ThreadPool threadPool = new ThreadPool(12, "test-encrypted-server-%d");
		threadPool.start();
		AtomicBoolean clientConnection = new AtomicBoolean(false);
		
		TCPServer<TCPSession> server = TCPServer.builder().setBufferSize(128).setSessionCreator(c -> new SecureTCPSession(c, createServerEngine(sslContext), 128, threadPool::execute) {
			
			protected void onConnected() { }
			
			protected void onDisconnected() { }
			
			protected void onIncomingData(@NotNull ByteBuffer buffer) {
				try {
					writeToChannel(buffer);
				} catch (IOException e) {
					Log.e(e);
					Assert.fail();
				}
			}
			
			protected void onError(Throwable t) {
				Log.e(t);
				Assert.fail();
			}
		}).createTCPServer();
		server.bind();
		
		byte [] data = new byte[1024];
		ThreadLocalRandom.current().nextBytes(data);
		for (int clientCount = 0; clientCount < 200; clientCount++) {
			SSLEngine clientEngine = sslContext.createSSLEngine();
			clientEngine.setEnabledCipherSuites(clientEngine.getSupportedCipherSuites());
			clientEngine.setUseClientMode(true);
			ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
			clientConnection.set(false);
			TCPSocket socket = new SecureTCPSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), clientEngine, threadPool::execute);
			socket.setCallback(new TCPSocketCallback() {
				public void onConnected(TCPSocket socket) { clientConnection.set(true); }
				public void onDisconnected(TCPSocket socket) { }
				public void onIncomingData(TCPSocket socket, ByteBuffer buffer) { byte [] data = new byte[buffer.remaining()]; buffer.get(data); try { baos.write(data); }catch (IOException e) { Log.e(e); Assert.fail(); } }
				public void onError(TCPSocket socket, Throwable t) {Log.e(t); Assert.fail(); }
			});
			socket.connect();
			socket.send(data);
			
			waitForCondition(1000, () -> clientConnection.get() && baos.size() >= 1024);
			
			Assert.assertTrue(clientConnection.get());
			Assert.assertEquals(1024, baos.size());
			Assert.assertArrayEquals(data, baos.toByteArray());
			
			if (clientCount % 2 == 0)
				Assert.assertTrue(socket.disconnect());
			else
				socket.getSocket().shutdownOutput();
		}
		
		threadPool.stop(true);
		threadPool.awaitTermination(1000);
	}
	
	@Test
	public void testUnencryptedManyClients() throws IOException {
		AtomicBoolean clientConnection = new AtomicBoolean(false);
		
		TCPServer<TCPSession> server = TCPServer.builder().setBufferSize(128).setSessionCreator(c -> new TCPSession(c) {
			
			protected void onConnected() { }
			
			protected void onDisconnected() { }
			
			protected void onIncomingData(@NotNull ByteBuffer buffer) {
				try {
					writeToChannel(buffer);
				} catch (IOException e) {
					Log.e(e);
					Assert.fail();
				}
			}
			
			protected void onError(Throwable t) {
				Log.e(t);
				Assert.fail();
			}
		}).createTCPServer();
		server.bind();
		
		byte [] data = new byte[1024];
		ThreadLocalRandom.current().nextBytes(data);
		for (int clientCount = 0; clientCount < 200; clientCount++) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
			clientConnection.set(false);
			TCPSocket socket = new TCPSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), 128);
			socket.setCallback(new TCPSocketCallback() {
				public void onConnected(TCPSocket socket) { clientConnection.set(true); }
				public void onDisconnected(TCPSocket socket) { }
				public void onIncomingData(TCPSocket socket, ByteBuffer buffer) { byte [] data = new byte[buffer.remaining()]; buffer.get(data); try { baos.write(data); }catch (IOException e) { Log.e(e); Assert.fail(); } }
				public void onError(TCPSocket socket, Throwable t) {Log.e(t); Assert.fail(); }
			});
			socket.connect();
			socket.send(data);
			
			waitForCondition(1000, () -> clientConnection.get() && baos.size() >= 1024);
			
			Assert.assertTrue(clientConnection.get());
			Assert.assertEquals(1024, baos.size());
			Assert.assertArrayEquals(data, baos.toByteArray());
			
			if (clientCount % 2 == 0)
				Assert.assertTrue(socket.disconnect());
			else
				socket.getSocket().close();
		}
		
	}
	
	@Test
	public void testJavaEncryptedSocket() throws IOException, NoSuchAlgorithmException, KeyManagementException {
		SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
		sslContext.init(null, new TrustManager[]{new X509TrustManager() {
			@Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
			@Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
			@Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
		}}, new SecureRandom());
		
		ThreadPool threadPool = new ThreadPool(12, "test-encrypted-server-%d");
		threadPool.start();
		
		TCPServer<TCPSession> server = TCPServer.builder().setBufferSize(128).setSessionCreator(c -> new SecureTCPSession(c, createServerEngine(sslContext), 128, threadPool::execute) {
			
			protected void onConnected() { }
			
			protected void onDisconnected() { }
			
			protected void onIncomingData(@NotNull ByteBuffer buffer) {
				try {
					writeToChannel(buffer);
				} catch (IOException e) {
					Log.e(e);
					Assert.fail();
				}
			}
			
			protected void onError(Throwable t) {
				Log.e(t);
				Assert.fail();
			}
		}).createTCPServer();
		server.bind();
		
		byte [] data = new byte[1024];
		ThreadLocalRandom.current().nextBytes(data);
		for (int clientCount = 0; clientCount < 10; clientCount++) {
			SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
			socket.setEnabledCipherSuites(socket.getSupportedCipherSuites());
			socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()));
			socket.startHandshake();
			
			socket.setSoTimeout(0);
			socket.getOutputStream().write(data);
			byte [] recv = socket.getInputStream().readNBytes(1024);
			
			Assert.assertArrayEquals(data, recv);
			
			if (clientCount % 2 == 0)
				socket.close();
			else
				socket.shutdownOutput();
		}
		
		threadPool.stop(true);
		threadPool.awaitTermination(1000);
	}
	
	private static SSLEngine createServerEngine(SSLContext context) {
		SSLEngine engine = context.createSSLEngine();
		engine.setUseClientMode(false);
		engine.setEnabledCipherSuites(engine.getSupportedCipherSuites());
		return engine;
	}
	
	private static void waitForCondition(long timeout, Supplier<Boolean> condition) {
		long start = System.nanoTime();
		while (!condition.get() && (System.nanoTime() - start)/1E6 < timeout) {
			LockSupport.parkNanos(10000);
		}
	}
	
}
