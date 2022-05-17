package fcdiscord.server;

import java.awt.Color;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import fcdiscord.ApiCommon;
import org.javacord.api.entity.channel.ServerTextChannel;
import org.javacord.api.entity.message.MessageAuthor;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.WebhookMessageBuilder;
import org.javacord.api.entity.message.mention.AllowedMentions;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;
import org.javacord.api.entity.webhook.IncomingWebhook;

public final class ApiServer extends ApiCommon {
	public ApiServer(InetSocketAddress address, String pw) {
		this.pw = pw;

		listenThread = new ListenThread(address);
	}

	@Override
	public void close() {
		listenThread.interrupt();

		for (ServerChannelContext context : connections) {
			context.closeChannel();
		}
	}

	@Override
	protected void close(ChannelContext context) {
		connections.remove(context);

		super.close(context);
	}

	@Override
	protected boolean processCommand(ChannelContext rawContext, String cmd, ByteBuffer buffer, boolean eof) {
		ServerChannelContext context = (ServerChannelContext) rawContext;

		if (!context.loggedIn) {
			if (!cmd.equals(COMMAND_C2S_LOGIN)) {
				System.out.printf("[DCAPI] missing login from %s%n", rawContext.getRemoteAddress());
				return false;
			}

			int version = buffer.getInt();

			if (version != API_VERSION) {
				System.out.printf("[DCAPI] invalid api version %d from %s%n", version, rawContext.getRemoteAddress());
				return false;
			}

			if (!readString(buffer).equals(pw)) {
				System.out.printf("[DCAPI] invalid password from %s%n", rawContext.getRemoteAddress());
				return false;
			}

			System.out.printf("[DCAPI] successful login from %s%n", rawContext.getRemoteAddress());
			context.loggedIn = true;
			return true;
		}

		switch (cmd) {
		case COMMAND_C2S_SEND_MESSAGE -> {
			ServerTextChannel channel = readChannel(buffer);

			if (channel != null) {
				new MessageBuilder().append(readString(buffer)).setAllowedMentions(NO_MENTIONS).send(channel);
			}
		}
		case COMMAND_C2S_SEND_WEBHOOK_MESSAGE -> {
			ServerTextChannel channel = readChannel(buffer);
			if (channel == null) break;

			String msg = readString(buffer);
			String user = readString(buffer);
			URL avatarUrl;

			try {
				avatarUrl = new URL(readString(buffer));
			} catch (MalformedURLException e) {
				e.printStackTrace();
				break;
			}

			getCreateWebHook(channel).whenComplete((hook, exc) -> {
				if (exc != null) {
					exc.printStackTrace();
					return;
				}

				new WebhookMessageBuilder()
				.append(msg)
				.setDisplayName(user)
				.setDisplayAvatar(avatarUrl)
				.setAllowedMentions(NO_MENTIONS)
				.send(hook);
			});
		}
		case COMMAND_C2S_ADD_REACTION -> {
			ServerTextChannel channel = readChannel(buffer);
			long messageId = buffer.getLong();
			String reaction = readString(buffer);
			channel.getMessageById(messageId).thenAccept(m -> m.addReaction(reaction));
		}
		case COMMAND_C2S_SUBSCRIBE_CHANNEL -> {
			int count = buffer.getShort() & 0xffff;

			for (int i = 0; i < count; i++) {
				context.subscribedChannels.add(buffer.getLong());
			}
		}
		case COMMAND_C2S_UNSUBSCRIBE_CHANNEL -> {
			int count = buffer.getShort() & 0xffff;

			for (int i = 0; i < count; i++) {
				context.subscribedChannels.remove(buffer.getLong());
			}
		}
		default -> {
			System.err.printf("[DCAPI] Unknown command: %s%n", cmd);
			return false;
		}
		}

		return true;
	}

	public synchronized void setServer(Server server) {
		this.server = server;
		webhooks.clear();

		if (server != null && !registeredMessageListener) {
			server.getApi().addMessageCreateListener(event -> {
				Long channelId = event.getChannel().getId();

				for (ServerChannelContext context : connections) {
					if (!context.subscribedChannels.contains(channelId)) continue;

					MessageAuthor author = event.getMessageAuthor();
					List<Long> roleIds;
					User user = author.asUser().orElse(null);

					if (user == null) {
						roleIds = Collections.emptyList();
					} else {
						List<Role> roles = user.getRoles(server);
						roleIds = new ArrayList<>(roles.size());

						for (Role role : roles) {
							roleIds.add(role.getId());
						}
					}

					Color roleColor = author.getRoleColor().orElse(null);

					ByteBuffer buffer = commandBuffer(COMMAND_S2C_ON_MESSAGE, 24);
					buffer.putLong(event.getMessageId());
					buffer.putLong(event.getChannel().getId());
					buffer.putLong(author.getId());
					buffer = writeString(author.getDisplayName(), buffer);
					buffer = reserve(6, buffer);
					buffer.put((byte) (author.isBotUser() || author.isWebhook() ? 1 : 0));
					writeLongs(roleIds, buffer);
					buffer.put((byte) (roleColor != null ? 1 : 0));
					if (roleColor != null) buffer.putInt(roleColor.getRGB());
					buffer = writeString(event.getMessageContent(), buffer);
					context.frameAndWrite(buffer);
				}
			});

			registeredMessageListener = true;
		}
	}

	private ServerTextChannel readChannel(ByteBuffer buffer) {
		Server server = this.server;
		if (server == null) return null;

		return server.getTextChannelById(buffer.getLong()).orElse(null);
	}

	private CompletableFuture<IncomingWebhook> getCreateWebHook(ServerTextChannel channel) {
		IncomingWebhook ret = webhooks.get(channel);
		if (ret != null) return CompletableFuture.completedFuture(ret);

		return channel.createWebhookBuilder().setName("fcbot").create()
				.thenApply(hook -> {
					IncomingWebhook prev = webhooks.putIfAbsent(channel, hook);
					return prev != null ? prev : hook;
				})
				.exceptionally(exc -> {
					IncomingWebhook hook = webhooks.get(channel);

					if (hook != null) {
						return hook;
					} else {
						throw exc instanceof RuntimeException ? (RuntimeException) exc : new RuntimeException(exc);
					}
				});
	}

	private final class ServerChannelContext extends ChannelContext {
		boolean loggedIn;
		final Set<Long> subscribedChannels = Collections.synchronizedSet(new HashSet<>());
	}

	private final class ListenThread extends Thread {
		ListenThread(SocketAddress address) {
			super("[DCAPI] listener");

			this.address = address;

			setDaemon(true);
			start();
		}

		@Override
		public void run() {
			try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
				serverChannel.bind(address);

				for (;;) {
					SocketChannel channel = serverChannel.accept();
					ServerChannelContext context = new ServerChannelContext();
					connections.add(context);
					context.init(channel);
					System.out.printf("[DCAPI] connection from %s%n", context.getRemoteAddress());
				}
			} catch (ClosedChannelException e) {
				System.out.println("[DCAPI] listening channel closed");
			} catch (Throwable t) {
				System.err.printf("[DCAPI] listening failed: %s%n", t.toString());
			}
		}

		private final SocketAddress address;
	};

	public static final AllowedMentions NO_MENTIONS = new AllowedMentionsBuilder().build();

	private final String pw;
	private final ListenThread listenThread;
	private final List<ServerChannelContext> connections = new CopyOnWriteArrayList<>();
	private volatile Server server;
	private final Map<ServerTextChannel, IncomingWebhook> webhooks = new ConcurrentHashMap<>();
	private boolean registeredMessageListener;
}
