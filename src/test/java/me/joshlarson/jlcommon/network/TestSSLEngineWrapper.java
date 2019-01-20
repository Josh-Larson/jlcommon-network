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

import me.joshlarson.jlcommon.network.SSLEngineWrapper;
import org.junit.Assert;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TestSSLEngineWrapper {
	
	@Test
	public void testHandshake() throws Exception {
		SSLContext context = SSLContext.getInstance("TLSv1.2");
		context.init(null, null, new SecureRandom());
		PipedOutputStream serverOutput = new PipedOutputStream();
		PipedOutputStream clientOutput = new PipedOutputStream();
		
		PipedInputStream serverInput = new PipedInputStream(clientOutput, 16*1024);
		PipedInputStream clientInput = new PipedInputStream(serverOutput, 16*1024);
		
		SSLEngine serverEngine = context.createSSLEngine();
		SSLEngine clientEngine = context.createSSLEngine();
		serverEngine.setUseClientMode(false);
		clientEngine.setUseClientMode(true);
		serverEngine.setEnabledCipherSuites(serverEngine.getSupportedCipherSuites());
		clientEngine.setEnabledCipherSuites(clientEngine.getSupportedCipherSuites());
		
		SSLEngineWrapper client = new SSLEngineWrapper(clientEngine, bb -> { while (bb.hasRemaining()) { clientOutput.write(bb.get()); } });
		SSLEngineWrapper server = new SSLEngineWrapper(serverEngine, bb -> { while (bb.hasRemaining()) { serverOutput.write(bb.get()); } });
		AtomicBoolean clientCompleted = new AtomicBoolean(false);
		AtomicBoolean serverCompleted = new AtomicBoolean(false);
		
		final byte [] clientMessage = "Hello Server!".getBytes(StandardCharsets.UTF_8);
		final byte [] serverMessage = new byte[clientMessage.length];
		Thread clientThread = new Thread(() -> {
			try {
				byte [] buffer = new byte[128];
				int n;
				client.startHandshake(); 
				while (!client.isConnectionInitialized() && (n = clientInput.read(buffer)) > 0) {
					client.read(buffer, 0, n);
				}
				client.write(clientMessage);
				clientCompleted.set(true);
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		Thread serverThread = new Thread(() -> {
			try {
				byte [] buffer = new byte[512];
				int n;
				AtomicInteger readOffset = new AtomicInteger(0);
				server.setReadCallback(bb -> bb.get(serverMessage, readOffset.getAndAdd(bb.remaining()), bb.remaining()));
				while (readOffset.get() < serverMessage.length && (n = serverInput.read(buffer)) > 0) {
					server.read(buffer, 0, n);
				}
				serverCompleted.set(true);
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
		
		clientThread.start();
		serverThread.start();
		
		clientThread.join();
		serverThread.join();
		
		Assert.assertTrue(clientCompleted.get());
		Assert.assertTrue(serverCompleted.get());
		Assert.assertArrayEquals(clientMessage, serverMessage);
	}
	
}
