package fcdiscord;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.channels.AlreadyBoundException;
import java.nio.channels.ServerSocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public final class Config {
	public static Config createForFile(Path file, boolean clientOnly, boolean alwaysStoreClientChannelId) throws IOException {
		Config ret = new Config("", 0, "", "", 0, clientOnly ? null : new ArrayList<>(), 0, clientOnly);
		boolean save = false;
		Properties properties;

		if (Files.exists(file)) {
			properties = new Properties();

			try (Reader reader = Files.newBufferedReader(file)) {
				properties.load(reader);
			}
		} else {
			properties = null;
			save = true;
		}

		String value;

		if (properties != null && (value = properties.getProperty("apiHost")) != null && (!value.isEmpty() || !clientOnly)) {
			ret.apiHost = value;
		} else {
			if (clientOnly) ret.apiHost = "localhost";
			save = true;
		}

		if (properties != null && (value = properties.getProperty("apiPort")) != null && !value.isEmpty() && !value.equals("0")) {
			ret.apiPort = Integer.parseInt(value);
		} else {
			if (!clientOnly) ret.apiPort = generatePort();
			save = true;
		}

		if (properties != null && (value = properties.getProperty("apiPassword")) != null && !value.isEmpty()) {
			ret.apiPassword = value;
		} else {
			if (!clientOnly) ret.apiPassword = generatePassword(20);
			save = true;
		}

		if (properties != null && (value = properties.getProperty("token")) != null && !value.isEmpty()) {
			ret.token = value;
			clientOnly = false;
		} else if (!clientOnly) {
			save = true;
		}

		if (properties != null && (value = properties.getProperty("guildId")) != null && !value.isEmpty() && !value.equals("0")) {
			ret.guildId = Long.parseUnsignedLong(value);
			clientOnly = false;
		} else if (!clientOnly) {
			save = true;
		}

		if (properties != null) {
			for (int idx = 0; idx < 10000; idx++) {
				value = properties.getProperty("channel"+idx);
				if (value == null) break;

				clientOnly = false;

				String rawPath = properties.getProperty("directory"+idx);
				if (rawPath == null) throw new IOException("missing directory"+idx+" config entry");

				Path path = Paths.get(rawPath);
				if (!Files.exists(path)) throw new IOException("missing directory "+idx+": "+rawPath);
				if (!Files.isDirectory(path)) throw new IOException("invalid directory "+idx+": "+rawPath);

				ret.instances.add(new Instance(Long.parseUnsignedLong(value), path));
			}

			if ((value = properties.getProperty("channelId")) != null && !value.isEmpty() && !value.equals("0")) {
				ret.channelId = Long.parseUnsignedLong(value);
			} else if (alwaysStoreClientChannelId) {
				save = true;
			}
		}

		if (save) {
			properties = new Properties();

			properties.setProperty("apiHost", ret.apiHost);
			properties.setProperty("apiPort", ret.apiPort > 0 ? Integer.toString(ret.apiPort) : "");
			properties.setProperty("apiPassword", ret.apiPassword);

			if (!clientOnly) {
				properties.setProperty("token", ret.token);
				properties.setProperty("guildId", ret.guildId != 0 ? Long.toUnsignedString(ret.guildId) : "");

				for (int idx = 0; idx < ret.instances.size(); idx++) {
					Instance instance = ret.instances.get(idx);
					properties.setProperty("channel"+idx, Long.toUnsignedString(instance.channelId));
					properties.setProperty("directory"+idx, instance.baseDir.toString());
				}
			}

			if (ret.channelId != 0 || alwaysStoreClientChannelId) {
				properties.setProperty("channelId", ret.channelId != 0 ? Long.toUnsignedString(ret.channelId) : "");
			}

			try (Writer writer = Files.newBufferedWriter(file)) {
				properties.store(writer, null);
			}
		}

		return ret;
	}

	private static int generatePort() throws IOException {
		Random rnd = ThreadLocalRandom.current();

		portGenLoop: for (int i = 0; i < 10000; i++) {
			int port = minPort + rnd.nextInt(maxPort - minPort);

			for (int reservedPort : reservedPorts) {
				if (port >= reservedPort && port <= reservedPort + extraReservedPortRange) {
					continue portGenLoop;
				}
			}

			InetSocketAddress addr = new InetSocketAddress(port);

			try (ServerSocketChannel testChannel = ServerSocketChannel.open()) {
				testChannel.bind(addr);
			} catch (AlreadyBoundException e) {
				continue;
			}

			return port;
		}

		throw new RuntimeException("can't generate port");
	}

	private static String generatePassword(int len) {
		StringBuilder ret = new StringBuilder(len);
		Random rnd = new SecureRandom();

		for (int i = 0; i < len; i++) {
			int n = rnd.nextInt(26 * 2 + 10);

			if (n < 10) {
				ret.append((char) ('0' + n));
			} else {
				n -= 10;

				if (n < 26) {
					ret.append((char) ('a' + n));
				} else {
					ret.append((char) ('A' + n - 26));
				}
			}
		}

		return ret.toString();
	}

	private Config(String apiHost, int apiPort, String apiPassword,
			String token, long guildId, List<Instance> instances,
			long channelId,
			boolean clientOnly) {
		this.apiHost = apiHost;
		this.apiPort = apiPort;
		this.apiPassword = apiPassword;

		this.token = token;
		this.guildId = guildId;
		this.instances = instances;

		this.channelId = channelId;

		this.clientOnly = clientOnly;
	}

	public String getApiHost() {
		return apiHost;
	}

	public int getApiPort() {
		return apiPort;
	}

	public InetSocketAddress getServerApiAddress() {
		if (apiHost.isEmpty()) {
			return new InetSocketAddress(apiPort);
		} else {
			return new InetSocketAddress(apiHost, apiPort);
		}
	}

	public InetSocketAddress getClientApiAddress() {
		if (apiHost.isEmpty()) {
			return new InetSocketAddress("localhost", apiPort);
		} else {
			return new InetSocketAddress(apiHost, apiPort);
		}
	}

	public String getApiPassword() {
		return apiPassword;
	}

	public String getToken() {
		if (clientOnly) throw new IllegalStateException();

		return token;
	}

	public long getGuildId() {
		if (clientOnly) throw new IllegalStateException();

		return guildId;
	}

	public List<Instance> getInstances() {
		if (clientOnly) throw new IllegalStateException();

		return instances;
	}

	public long getChannelId() {
		return channelId;
	}

	public boolean isValidForApiServer() {
		return isValidForDiscord() && apiPort > 0 && !apiPassword.isEmpty();
	}

	public boolean isValidForApiClient() {
		return !apiHost.isEmpty() && apiPort > 0 && !apiPassword.isEmpty();
	}

	public boolean isValidForUpdateHandler() {
		return isValidForDiscord() && !instances.isEmpty();
	}

	public boolean isValidForDiscord() {
		return !clientOnly && !token.isEmpty() && guildId != 0;
	}

	public static class Instance {
		Instance(long channelId, Path baseDir) {
			this.channelId = channelId;
			this.baseDir = baseDir;
		}

		public long getChannelId() {
			return channelId;
		}

		public Path getBaseDir() {
			return baseDir;
		}

		long channelId;
		Path baseDir;
	}

	private static final int minPort = 1024;
	private static final int maxPort = 0x10000;
	private static final int[] reservedPorts = { 8080, 25565, 25575, 46819 }; // default http-proxy, mc, mc-rcon, sampler-rcon ports
	private static final int extraReservedPortRange = 3;

	String apiHost;
	int apiPort;
	String apiPassword;

	String token;
	long guildId;
	List<Instance> instances;

	long channelId; // used for external client (mod)

	private final boolean clientOnly;
}
