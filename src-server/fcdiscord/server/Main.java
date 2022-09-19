package fcdiscord.server;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import fcdiscord.Config;
import fcdiscord.server.update.ModUpdateHandler;
import org.javacord.api.DiscordApi;
import org.javacord.api.DiscordApiBuilder;
import org.javacord.api.entity.channel.ServerChannel;
import org.javacord.api.entity.channel.TextChannel;
import org.javacord.api.entity.intent.Intent;
import org.javacord.api.entity.permission.PermissionType;
import org.javacord.api.entity.permission.Role;
import org.javacord.api.entity.server.Server;
import org.javacord.api.event.message.MessageCreateEvent;
import org.javacord.api.event.message.reaction.ReactionAddEvent;
import org.javacord.api.event.server.ServerBecomesAvailableEvent;
import org.javacord.api.interaction.ApplicationCommandPermissionType;
import org.javacord.api.interaction.ApplicationCommandPermissions;
import org.javacord.api.interaction.ApplicationCommandPermissionsUpdater;
import org.javacord.api.interaction.SlashCommand;
import org.javacord.api.interaction.SlashCommandInteraction;
import org.javacord.api.interaction.callback.InteractionCallbackDataFlag;
import org.javacord.api.listener.message.MessageCreateListener;
import org.javacord.api.listener.message.reaction.ReactionAddListener;
import org.javacord.api.listener.server.ServerBecomesAvailableListener;

public final class Main {
	public static Config config;

	public static void main(String[] args) throws IOException {
		Path configFile = Paths.get(args.length == 0 ? "config.properties" : args[0]);
		config = Config.createForFile(configFile, false, false);

		if (!config.isValidForApiServer() && !config.isValidForUpdateHandler()) {
			System.err.println("Missing config entries");
			System.exit(1);
		}

		ApiServer apiServer;

		if (config.isValidForApiServer()) {
			apiServer = new ApiServer(config.getServerApiAddress(), config.getApiPassword());
		} else {
			apiServer = null;
		}

		Handler handler;

		if (config.isValidForUpdateHandler()) {
			handler = new Handler(config.getGuildId(), config.getInstances());
		} else {
			handler = null;
		}

		DiscordApi api = new DiscordApiBuilder()
				.setWaitForUsersOnStartup(true)
				.setIntents(Intent.GUILDS, Intent.GUILD_MEMBERS, Intent.GUILD_MESSAGES, Intent.GUILD_MESSAGE_REACTIONS, Intent.DIRECT_MESSAGES, Intent.DIRECT_MESSAGE_REACTIONS)
				.setToken(config.getToken())
				.addServerBecomesAvailableListener(handler)
				.addMessageCreateListener(handler)
				.addReactionAddListener(handler)
				.login()
				.join();

		Server server = api.getServerById(config.getGuildId()).orElse(null);

		if (server != null) {
			handler.init(server);
			if (apiServer != null) apiServer.setServer(server);
		}
	}

	private static class Handler implements ServerBecomesAvailableListener, MessageCreateListener, ReactionAddListener {
		Handler(long guildId, Collection<Config.Instance> instances) {
			this.guildId = guildId;
			this.instanceMap = new HashMap<>(instances.size());

			for (Config.Instance instance : instances) {
				instanceMap.put(instance.getChannelId(), instance);
			}
		}

		synchronized void init(Server server) {
			if (initialized) return;
			initialized = true;

			SlashCommand.with("approveall", "approve all mods")
			.setDefaultPermission(false)
			.createForServer(server)
			.thenCompose(cmd -> {
				System.out.println("approveAll command created");

				cmd.getApi().addSlashCommandCreateListener(event -> {
					SlashCommandInteraction interaction = event.getSlashCommandInteractionWithCommandId(cmd.getId()).orElse(null);
					if (interaction == null) return;

					TextChannel channel = interaction.getChannel().orElseThrow();
					Config.Instance instance = instanceMap.get(channel.getId());

					if (instance == null) {
						interaction.createImmediateResponder().setContent("invalid channel").setFlags(InteractionCallbackDataFlag.EPHEMERAL).respond();
						return;
					}

					interaction.respondLater()
					.thenCompose(resp -> {
						resp.setContent("fetching messages...").setFlags(InteractionCallbackDataFlag.EPHEMERAL).update();

						return channel.getMessagesWhile(ModUpdateHandler::triggerMessage)
								.thenCompose(msgs -> interaction.createFollowupMessageBuilder().setContent("processing %d messages".formatted(msgs.size())).setFlags(InteractionCallbackDataFlag.EPHEMERAL).send()
										.thenApply(ignore -> msgs))
								.thenCompose(messages -> ModUpdateHandler.processMessages(messages, instance.getBaseDir()));
					})
					.whenComplete((res, exc) -> {
						if (exc != null) System.out.printf("error creating approveAll command: %s%n", exc);
						interaction.createFollowupMessageBuilder().setContent(exc == null ? "done" : "error: ".concat(exc.toString())).setFlags(InteractionCallbackDataFlag.EPHEMERAL).send();
					});
				});

				List<ApplicationCommandPermissions> perms = new ArrayList<>();

				for (Role role : server.getRoles()) {
					if (role.getAllowedPermissions().contains(PermissionType.ADMINISTRATOR)) {
						perms.add(ApplicationCommandPermissions.create(role.getId(), ApplicationCommandPermissionType.ROLE, true));
					}
				}

				System.out.printf("giving approveAll perms to %s%n", perms.stream().map(ApplicationCommandPermissions::getId).map(Object::toString).collect(Collectors.joining(", ")));

				return new ApplicationCommandPermissionsUpdater(server).setPermissions(perms).update(cmd.getId());
			})
			.exceptionally(exc -> {
				System.out.printf("error creating approveAll command: %s%n", exc);
				return null;
			});

			System.out.println("Instances:");

			for (Config.Instance instance : instanceMap.values()) {
				ServerChannel channel = server.getChannelById(instance.getChannelId()).orElse(null);

				System.out.printf("  %s: %s%n", channel != null ? "#"+channel.getName() : "missing:"+instance.getChannelId(), instance.getBaseDir());
			}

			System.out.println("Ready");
		}

		@Override
		public void onServerBecomesAvailable(ServerBecomesAvailableEvent event) {
			Server server = event.getServer();
			if (server.getId() != guildId) return;

			init(server);
		}

		@Override
		public void onMessageCreate(MessageCreateEvent event) {
			Server server = event.getServer().orElse(null);
			if (server == null || server.getId() != guildId) return;

			Config.Instance instance = instanceMap.get(event.getChannel().getId());

			if (instance != null) {
				ModUpdateHandler.handleMessage(event.getMessage(), instance.getBaseDir());
			}
		}

		@Override
		public void onReactionAdd(ReactionAddEvent event) {
			Server server = event.getServer().orElse(null);
			if (server == null || server.getId() != guildId) return;

			Config.Instance instance = instanceMap.get(event.getChannel().getId());

			if (instance != null) {
				event.requestReaction().thenCompose(reaction -> {
					if (reaction.isPresent()) {
						return event.requestUser().thenAccept(user -> ModUpdateHandler.handleReaction(reaction.orElseThrow(), user, instance.getBaseDir()));
					} else {
						return CompletableFuture.completedFuture(null);
					}
				})
				.exceptionally(exc -> {
					System.out.printf("error handling reaction addition: %s%n", exc);
					return null;
				});
			}
		}

		private final long guildId;
		private final Map<Long, Config.Instance> instanceMap;

		private boolean initialized;
	}
}
