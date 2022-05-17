package fcdiscord.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;

import fcdiscord.ApiCommon;
import fcdiscord.Config;

public final class ApiClient extends ApiCommon {
	public static ApiClient create(Path configFile, LongConsumer channelIdConsumer) throws UnconfiguredException, IOException {
		Config config = Config.createForFile(configFile, true, channelIdConsumer != null);
		if (!config.isValidForApiClient()
				|| channelIdConsumer != null && config.getChannelId() == 0) {
			throw new UnconfiguredException();
		}

		if (channelIdConsumer != null) channelIdConsumer.accept(config.getChannelId());

		return new ApiClient(config.getClientApiAddress(), config.getApiPassword());
	}

	@SuppressWarnings("serial")
	public static final class UnconfiguredException extends Exception { }

	public static ApiClient create(String host, int port, String pw) {
		return new ApiClient(new InetSocketAddress(host, port), pw);
	}

	public static ApiClient create(InetSocketAddress address, String pw) {
		return new ApiClient(address, pw);
	}

	private ApiClient(InetSocketAddress address, String pw) {
		this.address = address;
		this.pw = pw;

		this.context = new ChannelContext();

		reconnect(context, true);
	}

	public void sendMessage(long channelId, String message) {
		ByteBuffer buffer = commandBuffer(COMMAND_C2S_SEND_MESSAGE, 8);
		buffer.putLong(channelId);
		buffer = writeString(message, buffer);
		context.frameAndWrite(buffer);
	}

	public void sendWebhookMessage(long channelId, String message, String user, URL avatarUrl) {
		ByteBuffer buffer = commandBuffer(COMMAND_C2S_SEND_WEBHOOK_MESSAGE, 8);
		buffer.putLong(channelId);
		buffer = writeString(message, buffer);
		buffer = writeString(user, buffer);
		buffer = writeString(avatarUrl.toString(), buffer);
		context.frameAndWrite(buffer);
	}

	public void addReaction(long channelId, long messageId, String reaction) {
		ByteBuffer buffer = commandBuffer(COMMAND_C2S_ADD_REACTION, 16);
		buffer.putLong(channelId);
		buffer.putLong(messageId);
		buffer = writeString(reaction, buffer);
		context.frameAndWrite(buffer);
	}

	public void registerMessageHandler(MessageHandler handler, long... channels) {
		Set<Long> newChannels = new HashSet<>();

		synchronized (messageHandlers) {
			for (long channel : channels) {
				if (messageHandlers.computeIfAbsent(channel, ignore -> Collections.newSetFromMap(new IdentityHashMap<>())).add(handler)) {
					newChannels.add(channel);
				}
			}

			if (newChannels.isEmpty()) return;

			ByteBuffer buffer = commandBuffer(COMMAND_C2S_SUBSCRIBE_CHANNEL, 2 + newChannels.size() * 8);
			buffer.putShort((short) newChannels.size());

			for (long id : newChannels) {
				buffer.putLong(id);
			}

			context.frameAndWrite(buffer);
		}
	}

	public void unregisterMessageHandler(MessageHandler handler) {
		Set<Long> abandonedChannels = new HashSet<>();

		synchronized (messageHandlers) {
			for (Iterator<Map.Entry<Long, Set<MessageHandler>>> it = messageHandlers.entrySet().iterator(); it.hasNext(); ) {
				Map.Entry<Long, Set<MessageHandler>> entry = it.next();
				Set<MessageHandler> handlers = entry.getValue();

				if (handlers.remove(handler) && handlers.isEmpty()) {
					abandonedChannels.add(entry.getKey());
					it.remove();
				}
			}

			if (abandonedChannels.isEmpty()) return;

			ByteBuffer buffer = commandBuffer(COMMAND_C2S_UNSUBSCRIBE_CHANNEL, 2 + abandonedChannels.size() * 8);
			buffer.putShort((short) abandonedChannels.size());

			for (long id : abandonedChannels) {
				buffer.putLong(id);
			}

			context.frameAndWrite(buffer);
		}
	}

	private void reconnect(ChannelContext context, boolean initial) {
		if (!initial && !context.clear()) return;

		SocketChannel channel = null;

		try {
			channel = SocketChannel.open(address);

			ByteBuffer buffer = ByteBuffer.allocate(100);
			buffer.putInt(0);
			buffer = writeString(COMMAND_C2S_LOGIN, buffer);
			buffer.putInt(API_VERSION);
			buffer = writeString(pw, buffer);
			buffer.putInt(0, buffer.position());

			synchronized (messageHandlers) {
				if (!messageHandlers.isEmpty()) {
					int startPos = buffer.position();
					buffer = reserve(4, buffer);
					buffer.putInt(0);
					buffer = writeString(COMMAND_C2S_SUBSCRIBE_CHANNEL, buffer);
					buffer = reserve(2 + messageHandlers.size() * 8, buffer);
					buffer.putShort((short) messageHandlers.size());

					for (long id : messageHandlers.keySet()) {
						buffer.putLong(id);
					}

					buffer.putInt(startPos, buffer.position() - startPos);
				}
			}

			buffer.flip();

			while (buffer.hasRemaining()) {
				channel.write(buffer);
			}
		} catch (Throwable t) {
			System.err.printf("[DCAPI] Connect failed: %s%n", t.toString());

			try {
				if (channel != null) channel.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

			scheduleReconnect(initial);
			return;
		}

		context.init(channel);
		System.out.println("[DCAPI] connected");
	}

	@Override
	protected boolean processCommand(ChannelContext context, String cmd, ByteBuffer buffer, boolean eof) {
		switch (cmd) {
		case COMMAND_S2C_ON_MESSAGE -> {
			long id = buffer.getLong();
			long channelId = buffer.getLong();
			long authorId = buffer.getLong();
			String authorName = readString(buffer);
			boolean fromBot = buffer.get() != 0;
			List<Long> roles = readLongs(buffer);
			boolean hasRoleColor = buffer.get() != 0;
			Integer roleColor = hasRoleColor ? buffer.getInt() : null;
			String content = readString(buffer);

			synchronized (messageHandlers) {
				for (MessageHandler handler : messageHandlers.getOrDefault(channelId, Collections.emptySet())) {
					handler.onMessage(id, channelId, authorId, authorName, fromBot, roles, roleColor, content);
				}
			}
		}
		default -> {
			System.err.printf("[DCAPI] Unknown command: %s%n", cmd);
			return false;
		}
		}

		return true;
	}

	@Override
	protected void close(ChannelContext context) {
		System.out.println("[DCAPI] disconnected");
		super.close(context);
		scheduleReconnect(false);
	}

	private synchronized void scheduleReconnect(boolean initial) {
		if (reconnectThread != null) return;

		final long reconnectTime = System.nanoTime() + RECONNECT_DELAY_SEC * 1_000_000_000L;
		reconnectThread = new Thread("[DCAPI] client reconnect delay") {
			{
				setDaemon(true);
			}

			@Override
			public void run() {
				long rem;

				while ((rem = reconnectTime - System.nanoTime()) > 0) {
					LockSupport.parkNanos(rem);
				}

				synchronized (ApiClient.this) {
					if (reconnectThread == null) return;
					reconnectThread = null;
					reconnect(context, initial);
				}
			}
		};

		reconnectThread.start();
	}

	@Override
	public void close() {
		synchronized (this) {
			Thread thread = reconnectThread;

			if (thread != null) {
				reconnectThread = null;
				LockSupport.unpark(thread);
			}
		}

		context.closeChannel();
	}

	public interface MessageHandler {
		void onMessage(long id, long channelId, long authorId, String authorName, boolean fromBot, List<Long> roles, Integer roleColor, String content);
	}

	private static final int RECONNECT_DELAY_SEC = 10;

	private final SocketAddress address;
	private final String pw;
	private final Map<Long, Set<MessageHandler>> messageHandlers = new HashMap<>();
	private final ChannelContext context;
	private Thread reconnectThread;
}
