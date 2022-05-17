package fcdiscord.server.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlTable;

public final class Mod {
	public static ModList computeModList(Path dir) throws IOException {
		Map<String, List<ModEntry>> mods = new HashMap<>();
		@SuppressWarnings("unused")
		int totalModJars = 0;
		@SuppressWarnings("unused")
		int brokenModJars = 0;

		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, path -> !Files.isDirectory(path) && path.getFileName().toString().toLowerCase(Locale.ENGLISH).endsWith(".jar"))) {
			for (Path path : ds) {
				ModEntry res = parse(path);
				boolean broken = false;

				for (Mod mod : res.mods()) {
					mods.computeIfAbsent(mod.modId, ignore -> new ArrayList<>()).add(res);
					if (mod.version == null || mod.version.contains("${")) broken = true;
				}

				totalModJars++;
				if (broken) brokenModJars++;
			}
		}

		if (DEBUG) {
			System.out.printf("%d / %d mods broken (%.2f%%)%n", brokenModJars, totalModJars, 100. * brokenModJars / totalModJars);
			System.out.println(mods);
		}

		Set<ModEntry> activeMods = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<ModEntry> inactiveMods = Collections.newSetFromMap(new IdentityHashMap<>());

		for (Map.Entry<String, List<ModEntry>> entry : mods.entrySet()) {
			List<ModEntry> candidates = entry.getValue();
			if (candidates.isEmpty()) continue;

			if (candidates.size() == 1) {
				activeMods.add(candidates.get(0));
				continue;
			}

			candidates.sort(Mod::compareMods);
			ModEntry selected = null;

			for (ModEntry candidate : candidates) {
				if (activeMods.contains(candidate)) {
					selected = candidate;
					break;
				}
			}

			for (int i = candidates.size() - 1; i >= 0; i--) {
				ModEntry candidate = candidates.get(i);

				if (selected == null) {
					if (!inactiveMods.contains(candidate)) selected = candidate;
				} else if (activeMods.contains(candidate)) {
					throw new IOException("conflicting mods: "+candidate+" "+selected+" (sub-mod selected earlier)");
				} else {
					inactiveMods.add(candidate);
				}
			}

			if (DEBUG) {
				System.out.printf("%s:%n", entry.getKey());

				for (ModEntry mod : candidates) {
					System.out.printf("  %c %s (%s)%n", mod == selected ? '*' : ' ', mod.mods().iterator().next().version, dir.relativize(mod.path()));
				}
			}
		}

		return new ModList(activeMods, inactiveMods);
	}

	public record ModList(Set<ModEntry> active, Set<ModEntry> inactive) { }

	public static int compareMods(ModEntry jarA, ModEntry jarB) {
		int ret = 0;

		for (Mod a : jarA.mods()) {
			for (Mod b : jarB.mods()) {
				if (a.modId.equals(b.modId)) {
					int cmp = compareVersions(a.version, b.version);

					ret += Math.max(-1, Math.min(1, cmp));
					break;
				}
			}
		}

		return ret;
	}

	public static int compareVersions(String versionA, String versionB) {
		if (versionA == null) {
			return versionB == null ? 0 : -1;
		} else if (versionB == null) {
			return 1;
		}

		Pattern pattern = Pattern.compile("(\\d+(?:\\.\\d+)*)(?:-([^+]+))?(?:\\+.*)?");
		Matcher matcherA = pattern.matcher(versionA);
		Matcher matcherB = pattern.matcher(versionB);
		if (!matcherA.matches() || !matcherB.matches()) return versionA.compareTo(versionB);

		int cmp = compareVersionGroups(matcherA.group(1), matcherB.group(1)); // compare version core
		if (cmp != 0) return cmp;

		boolean aHasPreRelease = matcherA.group(2) != null;
		boolean bHasPreRelease = matcherB.group(2) != null;

		if (aHasPreRelease != bHasPreRelease) { // compare pre-release presence
			return aHasPreRelease ? -1 : 1;
		}

		if (aHasPreRelease) {
			if (matcherA.group(2).contains("-") || matcherB.group(2).contains("-")) {
				cmp = compareVersions(matcherA.group(2), matcherB.group(2));
			} else {
				cmp = compareVersionGroups(matcherA.group(2), matcherB.group(2)); // compare pre-release
			}

			if (cmp != 0) return cmp;
		}

		return 0;
	}

	private static int compareVersionGroups(String groupA, String groupB) {
		String[] partsA = groupA.split("\\.");
		String[] partsB = groupB.split("\\.");

		for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
			String partA = partsA[i];
			String partB = partsB[i];

			try {
				int a = Integer.parseInt(partA);

				try {
					int b = Integer.parseInt(partB);
					int cmp = Integer.compare(a, b); // both numeric, compare int value
					if (cmp != 0) return cmp;
				} catch (NumberFormatException e) {
					return -1; // only a numeric
				}
			} catch (NumberFormatException e) {
				try {
					Integer.parseInt(partB);
					return 1; // only b numeric
				} catch (NumberFormatException e2) {
					// ignore
				}
			}

			int cmp = partA.compareTo(partB); // neither numeric, compare lexicographically
			if (cmp != 0) return cmp;
		}

		return Integer.compare(partsA.length, partsB.length); // compare part count
	}

	record ModEntry(Path path, Collection<Mod> mods) { }

	public static ModEntry parse(Path file) throws IOException {
		Collection<Mod> mods;

		try (JarFile jar = new JarFile(file.toFile())) {
			Manifest manifest = jar.getManifest();
			ZipEntry entry = jar.getEntry("META-INF/mods.toml");

			if (entry != null) {
				mods = parse(jar.getInputStream(entry), manifest, file);
			} else if (jar.getEntry("fabric.mod.json") != null) {
				throw new IOException(file+" is a fabric-only mod");
			} else {
				mods = parse(manifest);
			}
		}

		return new ModEntry(file, mods);
	}

	private static Collection<Mod> parse(InputStream is, Manifest manifest, Path path) throws IOException {
		TomlArray mods = Toml.parse(is).getArray("mods");
		List<Mod> ret = new ArrayList<>(mods.size());

		for (int i = 0; i < mods.size(); i++) {
			TomlTable mod = mods.getTable(i);

			String modId = mod.getString("modId");
			if (modId == null) throw new IOException("missing modId in "+path);
			String version = mod.getString("version");
			if (version == null) throw new IOException("missing version in "+path);

			if (version.equals("${file.jarVersion}")) {
				version = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
			}

			ret.add(new Mod(modId, version));
		}

		return ret;
	}

	private static Collection<Mod> parse(Manifest manifest) {
		String name = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_TITLE);
		String version = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);

		if (name != null && version != null) {
			return Collections.singletonList(new Mod(name, version));
		} else {
			return Collections.emptyList();
		}
	}

	private Mod(String modId, String version) {
		this.modId = modId;
		this.version = version;
	}

	public String getModId() {
		return modId;
	}

	public String getVersion() {
		return version;
	}

	@Override
	public String toString() {
		return String.format("%s %s", modId, version);
	}

	private static final boolean DEBUG = false;

	private final String modId;
	private final String version;
}
