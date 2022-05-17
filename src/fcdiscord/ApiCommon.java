package fcdiscord;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public abstract class ApiCommon implements Closeable {
	protected static ByteBuffer commandBuffer(String command, int minSize) {
		ByteBuffer ret = ByteBuffer.allocate(Math.max(100, command.length() + minSize));
		ret.putInt(0);
		ret = writeString(command, ret);

		return reserve(minSize, ret);
	}

	protected static String readString(ByteBuffer buffer) {
		int len = buffer.getShort() & 0xffff;
		byte[] data = new byte[len];
		buffer.get(data);

		return new String(data, StandardCharsets.UTF_8);
	}

	protected static ByteBuffer writeString(String str, ByteBuffer buffer) {
		byte[] data = str.getBytes(StandardCharsets.UTF_8);
		if (data.length > 0xffff) throw new IllegalArgumentException("oversized string");

		buffer = reserve(2 + data.length, buffer);
		buffer.putShort((short) data.length);
		buffer.put(data);

		return buffer;
	}

	protected static List<Long> readLongs(ByteBuffer buffer) {
		int count = buffer.getShort() & 0xffff;
		List<Long> ret = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			ret.add(buffer.getLong());
		}

		return ret;
	}

	protected static ByteBuffer writeLongs(Collection<Long> values, ByteBuffer buffer) {
		if (values.size() > 0xffff) throw new IllegalArgumentException("oversized collection");

		buffer = reserve(2 + values.size() * Long.BYTES, buffer);
		buffer.putShort((short) values.size());

		for (long val : values) {
			buffer.putLong(val);
		}

		return buffer;
	}

	protected static ByteBuffer reserve(int size, ByteBuffer buffer) {
		if (buffer == null) return ByteBuffer.allocate(size);
		if (buffer.remaining() >= size) return buffer;

		ByteBuffer ret = ByteBuffer.allocate(buffer.position() + size);
		buffer.flip();
		ret.put(buffer);

		return ret;
	}

	private ByteBuffer processBuffer(ChannelContext context, ByteBuffer buffer, boolean eof) {
		buffer.flip();

		while (buffer.remaining() >= 4) {
			int len = buffer.getInt(buffer.position());

			if (len < 4 || len > 1_000_000) {
				System.err.printf("[DCAPI] invalid packet size %d for %s%n", len, context.getRemoteAddress());
				return null;
			}

			if (buffer.remaining() < len) {
				if (buffer.capacity() < len) {
					ByteBuffer newBuffer = ByteBuffer.allocate(len);
					newBuffer.put(buffer);

					return newBuffer;
				}

				break;
			}

			buffer.position(buffer.position() + 4); // skip len
			int oldLimit = buffer.limit();
			buffer.limit(buffer.position() + len - 4);
			String cmd = null;

			try {
				cmd = readString(buffer);

				if (cmd.equals(COMMAND_EXIT)) {
					return null;
				}

				if (!processCommand(context, cmd, buffer, eof && buffer.limit() == oldLimit)) {
					return null;
				}
			} catch (Throwable t) {
				System.err.printf("[DCAPI] Error processing command %s from %s: %s%n", cmd, context.getRemoteAddress(), t.toString());
				t.printStackTrace();

				return null;
			}

			buffer.limit(oldLimit);
		}

		buffer.compact();

		return buffer;
	}

	protected abstract boolean processCommand(ChannelContext context, String cmd, ByteBuffer buffer, boolean eof);

	protected void close(ChannelContext context) {
		context.clear();
	}

	protected final class ReadThread extends Thread {
		public ReadThread(SocketChannel channel, ChannelContext context) {
			super("[DCAPI] read "+context.getRemoteAddress());

			this.channel = channel;
			this.context = context;

			setDaemon(true);
			start();
		}

		@Override
		public void run() {
			try {
				ByteBuffer buffer = ByteBuffer.allocate(4000);

				while (buffer != null && channel.read(buffer) >= 0) {
					buffer = processBuffer(context, buffer, false);
				}

				if (buffer != null && buffer.hasRemaining()) processBuffer(context, buffer, true);
			} catch (ClosedChannelException e) {
				// ignore
			} catch (Throwable t) {
				System.err.printf("[DCAPI] read failed: %s%n", t.toString());
			}

			synchronized (this) {
				if (context.readThread == this) close(context);
			}
		}

		private final SocketChannel channel;
		private final ChannelContext context;
	}

	protected final class WriteThread extends Thread {
		public WriteThread(SocketChannel channel, ChannelContext context) {
			super("[DCAPI] write "+context.getRemoteAddress());

			this.channel = channel;
			this.context = context;

			setDaemon(true);
			start();
		}

		@Override
		public void run() {
			try {
				ByteBuffer buffer = ByteBuffer.allocate(4000);

				while (bufferData(buffer)) {
					while (buffer.hasRemaining()) {
						channel.write(buffer);
					}

					buffer.clear();
				}
			} catch (ClosedChannelException e) {
				// ignore
			} catch (Throwable t) {
				System.err.printf("[DCAPI] read failed: %s%n", t.toString());
			}

			synchronized (this) {
				if (context.writeThread == this) close(context);
			}
		}

		private synchronized boolean bufferData(ByteBuffer out) throws InterruptedException {
			while (pendingBuffer.position() == 0 && channel.isOpen()) {
				wait();
			}

			if (!channel.isOpen()) return false;;

			pendingBuffer.flip();

			int oldLimit = pendingBuffer.limit();

			if (pendingBuffer.remaining() > out.remaining()) {
				pendingBuffer.limit(pendingBuffer.position() + out.remaining());
			}

			out.put(pendingBuffer);

			pendingBuffer.limit(oldLimit);
			pendingBuffer.compact();
			out.flip();

			return true;
		}

		public synchronized boolean write(ByteBuffer buffer) {
			if (!channel.isOpen()) return false;

			if (pendingBuffer.remaining() < buffer.remaining()) {
				if (pendingBuffer.capacity() > 1_000_000) return false;

				ByteBuffer newBuffer = ByteBuffer.allocate(pendingBuffer.position() + buffer.remaining());
				pendingBuffer.flip();
				newBuffer.put(pendingBuffer);
				pendingBuffer = newBuffer;
			}

			pendingBuffer.put(buffer);
			notifyAll();

			return true;
		}

		private final SocketChannel channel;
		private final ChannelContext context;
		private ByteBuffer pendingBuffer = ByteBuffer.allocate(4000);
	}

	public class ChannelContext {
		public synchronized void init(SocketChannel channel) {
			if (this.channel != null) throw new IllegalStateException("already initialized");

			this.channel = channel;
			this.writeThread = new WriteThread(channel, this);
			this.readThread = new ReadThread(channel, this);
		}

		public boolean clear() {
			Thread readThread;
			Thread writeThread;

			synchronized (this) {
				if (channel == null) return false;

				writeThread = this.writeThread;
				readThread = this.readThread;

				this.readThread = null;
				this.writeThread = null;

				try {
					if (channel.isOpen()) channel.close();
				} catch (IOException e) { }

				this.channel = null;

				synchronized (writeThread) {
					writeThread.notifyAll();
				}
			}

			try {
				if (readThread != Thread.currentThread()) readThread.join();
				if (writeThread != Thread.currentThread()) writeThread.join();
			} catch (InterruptedException e) { }

			return true;
		}

		public synchronized void closeChannel() {
			try {
				if (channel != null && channel.isOpen()) channel.close();
			} catch (IOException e) { }
		}

		public String getRemoteAddress() {
			try {
				return channel.getRemoteAddress().toString();
			} catch (IOException e) {
				return "(unknown)";
			}
		}

		public final boolean frameAndWrite(ByteBuffer buffer) {
			if (buffer.position() == 0) throw new IllegalStateException("empty buffer");

			buffer.flip();
			buffer.putInt(0, buffer.remaining());

			return write(buffer);
		}

		public synchronized final boolean write(ByteBuffer buffer) {
			if (channel == null) return false;

			return writeThread.write(buffer);
		}

		private SocketChannel channel;
		private ReadThread readThread;
		private WriteThread writeThread;
	}

	protected static final int API_VERSION = 1;

	protected static final String COMMAND_EXIT = "exit";

	protected static final String COMMAND_C2S_LOGIN = "login";
	protected static final String COMMAND_C2S_SEND_MESSAGE = "sendChannel";
	protected static final String COMMAND_C2S_SEND_WEBHOOK_MESSAGE = "sendChannelWebhook";
	protected static final String COMMAND_C2S_ADD_REACTION = "addReaction";
	protected static final String COMMAND_C2S_SUBSCRIBE_CHANNEL = "subscribeChannel";
	protected static final String COMMAND_C2S_UNSUBSCRIBE_CHANNEL = "unsubscribeChannel";
	protected static final String COMMAND_S2C_ON_MESSAGE = "onMessage";
}
