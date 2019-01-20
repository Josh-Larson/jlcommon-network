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

import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

public class SecureTCPSocket extends TCPSocket {
	
	private @NotNull SSLEngineWrapper security;
	
	public SecureTCPSocket(@NotNull SSLContext sslContext, Consumer<Runnable> executor) {
		this(sslContext.createSSLEngine(), executor);
	}
	
	public SecureTCPSocket(@NotNull SSLEngine engine, Consumer<Runnable> executor) {
		this(engine, 1024, executor);
	}
	
	public SecureTCPSocket(@NotNull SSLEngine engine, int bufferSize, @NotNull Consumer<Runnable> executor) {
		super(8*1024);
		this.security = new SSLEngineWrapper(engine, bufferSize, executor, super::send);
		this.security.setReadCallback(super::onRead);
	}
	
	public SecureTCPSocket(@NotNull InetSocketAddress address, @NotNull SSLContext sslContext, Consumer<Runnable> executor) {
		this(address, sslContext.createSSLEngine(), executor);
	}
	
	public SecureTCPSocket(@NotNull InetSocketAddress address, @NotNull SSLEngine engine, Consumer<Runnable> executor) {
		this(address, engine, 1024, executor);
	}
	
	public SecureTCPSocket(@NotNull InetSocketAddress address, @NotNull SSLEngine engine, int bufferSize, @NotNull Consumer<Runnable> executor) {
		super(address, 8*1024);
		engine.setUseClientMode(true);
		this.security = new SSLEngineWrapper(engine, bufferSize, executor, super::send);
		this.security.setReadCallback(super::onRead);
	}
	
	@Override
	protected void onConnect() {
		try {
			security.startHandshake();
		} catch (IOException e) {
			onError(e);
		}
		super.onConnect();
	}
	
	@Override
	protected void onRead(ByteBuffer data) throws IOException {
		security.read(data);
	}
	
	@Override
	public int send(ByteBuffer data) {
		try {
			return security.write(data);
		} catch (IOException e) {
			onError(e);
			return -1;
		}
	}
	
}
