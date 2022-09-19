package fcdiscord.server.update;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;
import fcdiscord.Config;
import fcdiscord.server.Main;
import fcdiscord.server.update.Mod.ModEntry;
import fcdiscord.server.update.Mod.ModList;
import org.javacord.api.entity.emoji.Emoji;
import org.javacord.api.entity.message.Message;
import org.javacord.api.entity.message.MessageAttachment;
import org.javacord.api.entity.message.MessageBuilder;
import org.javacord.api.entity.message.Reaction;
import org.javacord.api.entity.message.mention.AllowedMentionsBuilder;
import org.javacord.api.entity.server.Server;
import org.javacord.api.entity.user.User;

public final class ModUpdateHandler {
	public static void handleMessage(Message message, Path instancePath) {
		if (message.getAuthor().isBotUser()) return;

		processMessage(message, instancePath, true);
	}

	public static void handleReaction(Reaction reaction, User user, Path instancePath) {
		if (user.isBot()) return;

		if (reaction.getEmoji().equalsEmoji(SIM_FAIL) && reaction.getCount() <= 1) {
			reaction.getMessage().getChannel().sendMessage(SIM_FAIL.repeat(5));
			return;
		}

		if (!reaction.getEmoji().equalsEmoji(TRIGGER_EMOJI)) return;

		Message msg = reaction.getMessage();
		Server server = msg.getServer().orElseThrow();
		if (!server.isAdmin(user)) return;

		CompletableFuture<List<User>> future = null;

		for (Reaction r : msg.getReactions()) {
			Emoji emoji = r.getEmoji();

			if (!emoji.equalsEmoji(SUCCESS_EMOJI)
					|| !emoji.equalsEmoji(EMPTY_EMOJI)
					|| !emoji.equalsEmoji(FAIL_EMOJI)) {
				continue;
			}

			if (future == null) {
				future = r.getUsers();
			} else {
				future = future.thenCombine(r.getUsers(), (a, b) -> {
					List<User> ret = new ArrayList<>(a);
					ret.addAll(b);
					return ret;
				});
			}
		}

		if (future != null) {
			future.thenAcceptAsync(users -> {
				for (User u : users) {
					if (u.isBot()) return;
				}

				processMessage(msg, instancePath, false);
			}, executor);
		} else {
			executor.execute(() -> processMessage(msg, instancePath, false));
		}
	}

	public static boolean triggerMessage(Message msg) {
		for (Reaction r : msg.getReactions()) {
			Emoji emoji = r.getEmoji();

			if (emoji.equalsEmoji(SUCCESS_EMOJI)
					|| emoji.equalsEmoji(EMPTY_EMOJI)
					|| emoji.equalsEmoji(FAIL_EMOJI)) {
				return false;
			}
		}

		msg.addReaction(TRIGGER_EMOJI);

		return true;
	}

	public static CompletableFuture<Void> processMessages(Collection<Message> msgs, Path instancePath) {
		return CompletableFuture.runAsync(() -> {
			System.out.printf("running bulk update for %d messages%n", msgs.size());

			for (Message msg : msgs) {
				System.out.printf("processing message %d: %s%n", msg.getId(), msg.getContent().replaceAll("\\s+", " ").trim());
				processMessage(msg, instancePath, false);
			}
		}, executor);
	}

	private static void processMessage(Message msg, Path instancePath, boolean simulate) {
		List<InstallResult> outputs = new ArrayList<>();

		try {
			if (!msg.getContent().isBlank()) {
				List<String> urls = new ArrayList<>();

				for (String line : msg.getContent().split("\\R")) {
					line = line.trim();
					System.out.println("checking "+line);

					boolean found;

					do {
						found = false;
						int start = line.indexOf('<');
						int end;
						char first;

						if (start >= 0
								&& (end = line.indexOf('>', start + 1)) > start + 1
								&& (first = line.charAt(start + 1)) != '@' && first != '#' && first != ':'
								&& line.charAt(start + 2) != ':') {
							urls.add(line.substring(start + 1, end));
							line = line.substring(0, start).concat(line.substring(end + 1));
						}
					} while (found);

					Matcher matcher = URL_PATTERN.matcher(line);

					while (matcher.find()) {
						urls.add(matcher.group(1));
					}
				}

				if (urls.isEmpty()) {
					System.out.println("no url");
				} else {
					System.out.println("urls: "+String.join(", ", urls));
				}

				for (Iterator<String> it = urls.iterator(); it.hasNext(); ) {
					String rawUrl = it.next();

					try {
						URI uri = new URI(rawUrl);

						System.out.println("processing url "+uri);
						msg.addReaction(WORKING);
						outputs.add(handleUrl(uri, instancePath, simulate));
						msg.removeReactionByEmoji(WORKING);
						it.remove();
					} catch (URISyntaxException e) {
						// ignore
					}
				}

				if (!urls.isEmpty()) {
					System.out.println("failed: "+String.join(", ", urls));
					msg.getChannel().sendMessage("Invalid URLs: "+urls.stream().map(u -> "`%s`".formatted(u)).collect(Collectors.joining(", ")));
				}
			}

			for (MessageAttachment attachment : msg.getAttachments()) {
				String filename = attachment.getFileName();
				if (!isJar(filename)) continue;

				System.out.println("processing attachment "+attachment.getUrl());
				outputs.add(installFile(filename, () -> attachment.downloadAsInputStream(), instancePath, simulate));
			}

			System.out.printf("installed %d mods%n", outputs.size());

			if (outputs.isEmpty()) {
				msg.addReaction(simulate ? SIM_FAIL : EMPTY_EMOJI);
			} else {
				msg.addReaction(simulate ? SIM_PASS : SUCCESS_EMOJI);

				if (simulate && outputs.stream().anyMatch(InstallResult::newMod)) {
					msg.addReaction(SIM_NEW);
				}
			}
		} catch (Throwable t) {
			t.printStackTrace();

			new MessageBuilder()
			.append("Processing failed: "+t.toString())
			.setAllowedMentions(new AllowedMentionsBuilder().build())
			.send(msg.getChannel());

			msg.addReaction(simulate ? SIM_FAIL : FAIL_EMOJI);

			if (!simulate) {
				for (InstallResult output : outputs) {
					try {
						Files.deleteIfExists(output.outputFile());
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	private static InstallResult handleUrl(URI uri, Path instancePath, boolean simulate) throws Exception {
		String host = uri.getHost();
		String path = uri.getPath();
		if (host == null || path == null || !"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IOException("invalid url: "+uri);
		Matcher matcher;

		if (host.equals(CF_LINK_HOST)
				&& (matcher = CF_LINK_PATTERN.matcher(path)).matches()) {

			uri = fetchFileUriFromCurse(matcher);
		}

		return handleDownload(uri, instancePath, simulate);
	}

	private static URI fetchFileUriFromCurse(Matcher matcher) throws URISyntaxException, JsonParserException, IOException, InterruptedException {
		// It's mad how much you have to do to simply get the file from cf these days
		URI modSearchUrl = new URI("https", null, "api.curseforge.com", -1, "/v1/mods/search", "slug=%s&gameId=432&classId=6".formatted(matcher.group(1)), null);
		HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder(modSearchUrl).headers("x-api-key", Main.config.getCfApiToken()).timeout(TIMEOUT).build(), BodyHandlers.ofString());

		// Fetch the id from the first data object
		JsonObject responseBody = JsonParser.object().from(response.body());
		if (responseBody.getArray("data").size() == 0 || !responseBody.getArray("data").getObject(0).has("id")) {
			throw new RuntimeException("Unable to find project on the curseforge api. Slug is possibly wrong?");
		}

		int projectId = responseBody.getArray("data").getObject(0).getInt("id");

		String fileId = matcher.group(2);
		// Get the file name by looking up the file id using the projectId from above
		URI filesUrl = new URI("https", null, "api.curseforge.com", -1, "/v1/mods/%s/files/%s".formatted(projectId, fileId), null, null);
		HttpResponse<String> filesResponse = httpClient.send(HttpRequest.newBuilder(filesUrl).headers("x-api-key", Main.config.getCfApiToken()).timeout(TIMEOUT).build(), BodyHandlers.ofString());

		// Pull the actual name from data
		JsonObject filesResponseBody = JsonParser.object().from(filesResponse.body());
		if (!filesResponseBody.has("data") || !filesResponseBody.getObject("data").has("id")) {
			throw new RuntimeException("Unable to retrieve project id from the project slug, possible curse is broken?");
		}

		String fileName = filesResponseBody.getObject("data").getString("fileName");

		// This sometimes won't work but looks like it's only effected on ancient version of the game
		// Example: https://mediafiles.forgecdn.net/files/538/70/gi.class with the file id of 538070... why does it miss
		// 		    the 0 and why do none of the other files I've tested do the same even when the 0 sits in the same place?
		String[] parts = new String[] {
				fileId.substring(0, 4),
				fileId.substring(4, Math.min(7, fileId.length()))
		};

		// Run over each possible endpoint to generate a download url. If we hit a 200, we've got the correct file!
		URI lookupUrl = null;
		boolean foundValid = false;
		String[] variants = new String[]{"mediafiles", "media", "edge"};
		for (String variant : variants) {
			lookupUrl = new URI("https", null, "%s.forgecdn.net".formatted(variant), -1, "/files/%s/%s/%s".formatted(parts[0], parts[1], fileName), null, null);
			HttpResponse<String> fileDownloadReq = httpClient.send(HttpRequest.newBuilder(lookupUrl).timeout(Duration.ofSeconds(5)).build(), BodyHandlers.ofString());
			if (fileDownloadReq.statusCode() == 200) {
				foundValid = true;
				break;
			}
		}

		if (!foundValid) {
			throw new RuntimeException("Unable to find any files on forgecdn with the id of %s".formatted(fileId));
		}

		return lookupUrl;
	}

	private static InstallResult handleDownload(URI uri, Path instancePath, boolean simulate) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).build();
		String path = uri.getPath();

		if (isJar(path)) { // proper filename available, use it
			return installFile(path.substring(path.lastIndexOf('/') + 1),
					() -> {
						HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());

						if (response.statusCode() != 200) {
							response.body().close();
							throw new IOException("Request to "+uri+" failed: "+response.statusCode());
						}

						return response.body();
					},
					instancePath, simulate);
		} else { // try to infer filename from redirect or Content-Disposition header
			HttpResponse<InputStream> response = httpClient.send(request, BodyHandlers.ofInputStream());

			if (response.statusCode() != 200) {
				response.body().close();
				throw new IOException("Request to "+uri+" failed: "+response.statusCode());
			}

			String actualPath = response.request().uri().getPath().replace('\\', '/'); // path after potential redirects
			String filename;

			if (isJar(actualPath)) { // redirected to actual jar
				filename = actualPath.substring(actualPath.lastIndexOf('/'));
			} else { // still no jar, try Content-Disposition header
				String header = response.headers().firstValue("Content-Disposition").orElse(null);

				if (header == null) {
					response.body().close();
					throw new IOException("unable to determine filename for "+uri);
				}

				Matcher matcher = CONTENT_DISPOSITION_PATTERN.matcher(header);

				if (!matcher.matches()) {
					response.body().close();
					throw new IOException("non-attachment download at "+uri);
				}

				filename = matcher.group(1);
				if (filename.startsWith("\"")) filename = filename.substring(1, filename.length() - 1);
				filename = filename.trim();

				if (!isJar(filename)) {
					response.body().close();
					throw new IOException("non-jar download at "+uri);
				}
			}

			InputStream is = response.body();

			try {
				return installFile(filename, () -> is, instancePath, simulate);
			} finally {
				try {
					is.close();
				} catch (IOException e) { }
			}
		}
	}

	private static InstallResult installFile(String filename, DataSource dataSource, Path instancePath, boolean simulate) throws Exception {
		if (!isJar(filename) || filename.contains("/") || filename.contains("\\")) {
			throw new IOException("invalid filename: "+filename);
		}

		Path targetDir = instancePath.resolve(TARGET_DIR).toAbsolutePath().normalize();
		Path targetFile = targetDir.resolve(filename).normalize();
		if (!targetFile.startsWith(targetDir)) throw new IOException("invalid filename: "+filename);

		if (Files.exists(targetFile)) throw new FileAlreadyExistsException(filename);

		Path tmpFile = Files.createTempFile("fcdDl", ".jar");

		try {
			try (InputStream is = dataSource.open()) {
				Files.copy(is, tmpFile, StandardCopyOption.REPLACE_EXISTING);
			}

			ModEntry mod = Mod.parse(tmpFile);
			if (mod.mods().isEmpty()) throw new IOException("jar doesn't contain any mods");

			for (Mod m : mod.mods()) {
				if (m.getVersion() == null) throw new IOException("mod "+m.getModId()+" doesn't declare a version");
			}

			ModList modList = Mod.computeModList(targetDir);
			List<ModEntry> replacedMods = new ArrayList<>();

			for (ModEntry activeMod : modList.active()) {
				boolean intersects = false;

				intersectCheckLoop : for (Mod am : activeMod.mods()) {
					for (Mod nm : mod.mods()) {
						if (Objects.equals(am.getModId(), nm.getModId())) {
							intersects = true;
							break intersectCheckLoop;
						}
					}
				}

				if (intersects) {
					if (Mod.compareMods(mod, activeMod) <= 0) {
						throw new IOException("submitted mod "+mod.mods()+" doesn't have a higher version than the currently active mod "+activeMod.mods());
					}

					replacedMods.add(activeMod);
				}
			}

			if (simulate) {
				Files.delete(tmpFile);
			} else {
				Files.createDirectories(targetDir);

				if (!replacedMods.isEmpty()) {
					Path archiveDir = targetDir.resolveSibling(targetDir.getFileName().toString()+"-archive");
					Files.createDirectories(archiveDir);

					for (ModEntry m : replacedMods) {
						Files.move(m.path(), archiveDir.resolve(targetDir.relativize(m.path())), StandardCopyOption.REPLACE_EXISTING);
					}
				}

				Files.move(tmpFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
			}

			return new InstallResult(targetFile, replacedMods.isEmpty());
		} catch (Throwable t) {
			Files.deleteIfExists(tmpFile);
			throw t;
		}
	}

	record InstallResult(Path outputFile, boolean newMod) { }

	private static boolean isJar(String filename) {
		return filename.toLowerCase(Locale.ENGLISH).endsWith(".jar");
	}

	private interface DataSource {
		InputStream open() throws Exception;
	}

	private static final String TRIGGER_EMOJI = "👍";
	private static final String SUCCESS_EMOJI = "✔️";
	private static final String EMPTY_EMOJI = "❔";
	private static final String FAIL_EMOJI = "❌";
	private static final String SIM_PASS = "✅";
	private static final String SIM_FAIL = "❎";
	private static final String SIM_NEW = "✨";
	private static final String WORKING = "⏳";

	private static final Path TARGET_DIR = Paths.get("servermods");
	private static final Duration TIMEOUT = Duration.ofSeconds(20);

	private static final Pattern URL_PATTERN = Pattern.compile("(https?://[^\\s<]+[^\\s<\\.,:\\)])"); // approximate set or urls detected by discord (made clickable)
	private static final String CF_LINK_HOST = "www.curseforge.com";
	private static final Pattern CF_LINK_PATTERN = Pattern.compile("/minecraft/mc-mods/([^/]+)/(?:files|download)/(\\d+)(?:/file)?");

	private static final Pattern CONTENT_DISPOSITION_PATTERN = Pattern.compile("\\s*attachment\\s*;(?:.+?;)?\\s*"
			+ "(?:filename\\s*=\\s*|filename\\*\\s*=\\s*[^']+'[^']*')(\".+?\"|[^\"]+)\\s*(?:;.+?)?", Pattern.CASE_INSENSITIVE);

	private static final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread ret = new Thread(r, "mod update thread");
		ret.setDaemon(true);
		ret.setUncaughtExceptionHandler((thread, exc) -> exc.printStackTrace());

		return ret;
	});

	private static final HttpClient httpClient = HttpClient.newBuilder()
			.followRedirects(Redirect.NORMAL)
			.connectTimeout(TIMEOUT)
			.build();
}
