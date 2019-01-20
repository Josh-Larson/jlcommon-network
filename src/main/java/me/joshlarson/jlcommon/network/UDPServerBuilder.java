/***********************************************************************************
 * MIT License                                                                     *
 *                                                                                 *
 * Copyright (c) 2019 Josh Larson                                                  *
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

import me.joshlarson.jlcommon.log.Log;
import org.jetbrains.annotations.NotNull;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.Consumer;

public class UDPServerBuilder {
	
	private InetSocketAddress bindAddr = null;
	private @NotNull Consumer<DatagramPacket> callback = p -> {};
	private int packetSize = 1024;
	private @NotNull Consumer<Throwable> errorCallback = Log::e;
	
	public UDPServerBuilder setBindAddr(@NotNull InetSocketAddress bindAddr) {
		this.bindAddr = bindAddr;
		return this;
	}
	
	public UDPServerBuilder setCallback(@NotNull Consumer<DatagramPacket> callback) {
		this.callback = callback;
		return this;
	}
	
	public UDPServerBuilder setPacketSize(int packetSize) {
		this.packetSize = packetSize;
		return this;
	}
	
	public UDPServerBuilder setErrorCallback(@NotNull Consumer<Throwable> errorCallback) {
		this.errorCallback = errorCallback;
		return this;
	}
	
	public UDPServer build() {
		return new UDPServer(Objects.requireNonNull(bindAddr, "bindAddr"), packetSize, callback, errorCallback);
	}
}
