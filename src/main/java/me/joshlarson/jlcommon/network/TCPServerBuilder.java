package me.joshlarson.jlcommon.network;

import me.joshlarson.jlcommon.network.TCPServer.TCPSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.function.Function;

public class TCPServerBuilder<T extends TCPSession> {
	
	private int bufferSize = 0;
	private Function<SocketChannel, T> sessionCreator = null;
	private @NotNull InetSocketAddress addr = new InetSocketAddress((InetAddress) null, 0);
	private @Nullable TCPServer.TCPServerCallback<T> serverCallback = null;
	
	public TCPServerBuilder<T> setBufferSize(int bufferSize) {
		this.bufferSize = bufferSize;
		return this;
	}
	
	public TCPServerBuilder<T> setSessionCreator(@NotNull Function<SocketChannel, T> sessionCreator) {
		this.sessionCreator = sessionCreator;
		return this;
	}
	
	public TCPServerBuilder<T> setAddr(@NotNull InetSocketAddress addr) {
		this.addr = addr;
		return this;
	}
	
	public TCPServerBuilder<T> setServerCallback(@Nullable TCPServer.TCPServerCallback<T> serverCallback) {
		this.serverCallback = serverCallback;
		return this;
	}
	
	public TCPServer<T> createTCPServer() {
		return new TCPServer<>(addr, bufferSize, Objects.requireNonNull(sessionCreator, "sessionCreator"), serverCallback);
	}
}
