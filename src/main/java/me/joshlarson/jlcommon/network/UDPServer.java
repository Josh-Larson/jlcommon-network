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
import me.joshlarson.jlcommon.concurrency.Delay;
import me.joshlarson.jlcommon.log.Log;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.*;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * This class represents a UDP server that listens for packets and
 * will call the callback when it receives one
 */
public class UDPServer {
	
	private final byte [] dataBuffer;
	private final InetSocketAddress bindAddr;
	private final Consumer<DatagramPacket> callback;
	private final Consumer<Throwable> errorCallback;
	private final BasicThread thread;
	
	private DatagramSocket socket;
	
	public UDPServer(@NotNull InetSocketAddress bindAddr, @NotNull Consumer<DatagramPacket> callback) {
		this(bindAddr, 1024, callback);
	}
	
	public UDPServer(@NotNull InetSocketAddress bindAddr, int packetSize, @NotNull Consumer<DatagramPacket> callback) {
		this(bindAddr, packetSize, callback, Log::e);
	}
	
	public UDPServer(@NotNull InetSocketAddress bindAddr, int packetSize, @NotNull Consumer<DatagramPacket> callback, @NotNull Consumer<Throwable> errorCallback) {
		this.dataBuffer = new byte[packetSize];
		this.bindAddr = bindAddr;
		this.callback = callback;
		this.errorCallback = errorCallback;
		this.thread = new BasicThread("udp-server-"+bindAddr, this::run);
		this.socket = null;
	}
	
	public void bind() throws SocketException {
		bind(null);
	}
	
	public void bind(Consumer<DatagramSocket> customizationCallback) throws SocketException {
		assert !thread.isExecuting() : "biding twice";
		socket = new DatagramSocket(bindAddr);
		if (customizationCallback != null)
			customizationCallback.accept(socket);
		thread.start();
	}
	
	public void close() {
		thread.stop(true);
		thread.awaitTermination(500);
		socket.close();
		socket = null;
	}
	
	public int getPort() {
		int port = socket.getLocalPort();
		while (port == 0) {
			port = socket.getLocalPort();
			if (!Delay.sleepMilli(5))
				break;
		}
		return port;
	}
	
	public boolean isRunning() {
		return thread.isExecuting();
	}
	
	public boolean send(DatagramPacket packet) {
		try {
			socket.send(packet);
			return true;
		} catch (IOException e) {
			String msg = e.getMessage();
			if (msg == null || !msg.toLowerCase(Locale.US).contains("socket closed")) {
				Log.e(e);
				close();
			}
		}
		return false;
	}
	
	public boolean send(int port, InetAddress addr, byte [] data) {
		return send(new DatagramPacket(data, data.length, addr, port));
	}
	
	public boolean send(int port, String addr, byte [] data) {
		try {
			return send(port, InetAddress.getByName(addr), data);
		} catch (UnknownHostException e) {
			Log.e(e);
		}
		return false;
	}
	
	public boolean send(InetSocketAddress addr, byte [] data) {
		return send(new DatagramPacket(data, data.length, addr));
	}
	
	public static UDPServerBuilder builder() {
		return new UDPServerBuilder();
	}
	
	private void run() {
		try {
			while (!Delay.isInterrupted()) {
				DatagramPacket packet = new DatagramPacket(dataBuffer, dataBuffer.length);
				try {
					socket.receive(packet);
					if (packet.getLength() > 0) {
						byte [] buffer = new byte[packet.getLength()];
						System.arraycopy(packet.getData(), 0, buffer, 0, packet.getLength());
						packet.setData(buffer);
						try {
							callback.accept(packet);
						} catch (Throwable t) {
							try {
								errorCallback.accept(t);
							} catch (Throwable errorException) {
								// Gotta draw the line somewhere..
							}
						}
					}
				} catch (IOException e) {
					String msg = e.getMessage();
					if (msg == null || !msg.toLowerCase(Locale.US).contains("socket closed")) {
						Log.e(e);
						close();
					}
					packet.setLength(0);
				}
			}
		} catch (Throwable t) {
			Log.e(t);
		}
	}
	
}
