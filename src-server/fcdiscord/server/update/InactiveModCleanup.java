package fcdiscord.server.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import fcdiscord.server.update.Mod.ModEntry;
import fcdiscord.server.update.Mod.ModList;

public final class InactiveModCleanup {
	public static void main(String[] args) throws IOException {
		if (args.length != 1) {
			System.err.println("usage: <mods dir>");
			System.exit(1);
		}

		Path modsDir = Paths.get(args[0]).toAbsolutePath().normalize();
		Path inactiveModsDir = modsDir.resolveSibling(modsDir.getFileName().toString()+"-archive");
		Files.createDirectories(inactiveModsDir);

		ModList modList = Mod.computeModList(modsDir);

		for (ModEntry mod : modList.inactive()) {
			Path out = inactiveModsDir.resolve(modsDir.relativize(mod.path()));

			if (Files.exists(out)) {
				System.out.printf("deleting %s (already archived)%n", mod.path().getFileName());
				Files.delete(mod.path());
			} else {
				System.out.printf("moving %s%n", mod.path().getFileName());
				Files.move(mod.path(), out);
			}
		}

		System.out.printf("removed %d mods%n", modList.inactive().size());
	}
}
