/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2016, 2017, 2018 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.providers;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.zip.ZipError;

import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraftforge.binarypatcher.ConsoleTool;
import net.minecraftforge.gradle.mcp.util.MCPRuntime;
import net.minecraftforge.gradle.mcp.util.MCPWrapper;
import org.cadixdev.atlas.Atlas;
import org.cadixdev.bombe.asm.jar.JarEntryRemappingTransformer;
import org.cadixdev.lorenz.MappingSet;
import org.cadixdev.lorenz.asm.LorenzRemapper;
import org.cadixdev.lorenz.io.srg.tsrg.TSrgReader;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.logging.Logger;

import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.DependencyProvider;
import net.fabricmc.loom.util.DownloadUtil;
import net.fabricmc.loom.util.FsPathConsumer;
import net.fabricmc.loom.util.IoConsumer;
import net.fabricmc.loom.util.ManifestVersion;
import net.fabricmc.loom.util.MinecraftVersionInfo;
import net.fabricmc.loom.util.StaticPathWatcher;
import net.fabricmc.stitch.merge.JarMerger;

public class MinecraftProvider extends DependencyProvider {
	private String minecraftVersion;

	private MinecraftVersionInfo versionInfo;
	private MinecraftLibraryProvider libraryProvider;

	private File minecraftJson;
	private File minecraftClientJar;
	private File minecraftServerJar;
	private File minecraftClientSrgJar;
	private File minecraftServerSrgJar;
	private File minecraftClientPatchedSrgJar;
	private File minecraftServerPatchedSrgJar;
	private File minecraftClientPatchedJar;
	private File minecraftServerPatchedJar;
	private File minecraftMergedJar;
	private String jarSuffix = "";

	Gson gson = new Gson();

	public MinecraftProvider(Project project) {
		super(project);
	}

	@Override
	public void provide(DependencyInfo dependency, Consumer<Runnable> postPopulationScheduler) throws Exception {
		minecraftVersion = dependency.getDependency().getVersion();

		boolean offline = getProject().getGradle().getStartParameter().isOffline();

		initFiles();

		downloadMcJson(offline);

		try (FileReader reader = new FileReader(minecraftJson)) {
			versionInfo = gson.fromJson(reader, MinecraftVersionInfo.class);
		}

		// Add Loom as an annotation processor
		addDependency(getProject().files(this.getClass().getProtectionDomain().getCodeSource().getLocation()), "compileOnly");

		if (offline) {
			if (minecraftClientJar.exists() && minecraftServerJar.exists()) {
				getProject().getLogger().debug("Found client and server jars, presuming up-to-date");
			} else if (minecraftMergedJar.exists()) {
				//Strictly we don't need the split jars if the merged one exists, let's try go on
				getProject().getLogger().warn("Missing game jar but merged jar present, things might end badly");
			} else {
				throw new GradleException("Missing jar(s); Client: " + minecraftClientJar.exists() + ", Server: " + minecraftServerJar.exists());
			}
		} else {
			downloadJars(getProject().getLogger());
		}

		libraryProvider = new MinecraftLibraryProvider();
		libraryProvider.provide(this, getProject());

		if (getExtension().isForge() && (!minecraftClientPatchedJar.exists() || !minecraftServerPatchedJar.exists())) {
			if (!minecraftClientSrgJar.exists() || !minecraftServerSrgJar.exists()) {
				createSrgJars(getProject().getLogger());
			}

			if (!minecraftClientPatchedSrgJar.exists() || !minecraftServerPatchedSrgJar.exists()) {
				patchJars(getProject().getLogger());
				injectForgeClasses(getProject().getLogger());
			}

			remapPatchedJars(getProject().getLogger());
		}

		if (!minecraftMergedJar.exists() || isRefreshDeps()) {
			try {
				mergeJars(getProject().getLogger());
			} catch (ZipError e) {
				DownloadUtil.delete(minecraftClientJar);
				DownloadUtil.delete(minecraftServerJar);

				if (getExtension().isForge()) {
					DownloadUtil.delete(minecraftClientPatchedJar);
					DownloadUtil.delete(minecraftServerPatchedJar);
					DownloadUtil.delete(minecraftClientSrgJar);
					DownloadUtil.delete(minecraftServerSrgJar);
					DownloadUtil.delete(minecraftClientPatchedSrgJar);
					DownloadUtil.delete(minecraftServerPatchedSrgJar);
				}

				getProject().getLogger().error("Could not merge JARs! Deleting source JARs - please re-run the command and move on.", e);
				throw new RuntimeException();
			}
		}
	}

	private void initFiles() {
		minecraftJson = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-info.json");
		minecraftClientJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-client.jar");
		minecraftServerJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-server.jar");

		if (getExtension().isForge()) {
			// Forge-related JARs
			PatchProvider patchProvider = getExtension().getPatchProvider();
			jarSuffix = "-patched-forge-" + patchProvider.forgeVersion;

			minecraftClientPatchedJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-client" + jarSuffix + ".jar");
			minecraftServerPatchedJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-server" + jarSuffix + ".jar");
			minecraftClientSrgJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-client-srg.jar");
			minecraftServerSrgJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-server-srg.jar");
			minecraftClientPatchedSrgJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-client-srg" + jarSuffix + ".jar");
			minecraftServerPatchedSrgJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-server-srg" + jarSuffix + ".jar");
		}

		minecraftMergedJar = new File(getExtension().getUserCache(), "minecraft-" + minecraftVersion + "-merged" + jarSuffix + ".jar");
	}

	private void downloadMcJson(boolean offline) throws IOException {
		File manifests = new File(getExtension().getUserCache(), "version_manifest.json");

		if (getExtension().isShareCaches() && !getExtension().isRootProject() && manifests.exists() && !isRefreshDeps()) {
			return;
		}

		if (offline) {
			if (manifests.exists()) {
				// If there is the manifests already we'll presume that's good enough
				getProject().getLogger().debug("Found version manifests, presuming up-to-date");
			} else {
				// If we don't have the manifests then there's nothing more we can do
				throw new GradleException("Version manifests not found at " + manifests.getAbsolutePath());
			}
		} else {
			getProject().getLogger().debug("Downloading version manifests");
			DownloadUtil.downloadIfChanged(new URL("https://launchermeta.mojang.com/mc/game/version_manifest.json"), manifests, getProject().getLogger());
		}

		String versionManifest = Files.asCharSource(manifests, StandardCharsets.UTF_8).read();
		ManifestVersion mcManifest = new GsonBuilder().create().fromJson(versionManifest, ManifestVersion.class);

		Optional<ManifestVersion.Versions> optionalVersion = Optional.empty();

		if (getExtension().customManifest != null) {
			ManifestVersion.Versions customVersion = new ManifestVersion.Versions();
			customVersion.id = minecraftVersion;
			customVersion.url = getExtension().customManifest;
			optionalVersion = Optional.of(customVersion);
			getProject().getLogger().lifecycle("Using custom minecraft manifest");
		}

		if (!optionalVersion.isPresent()) {
			optionalVersion = mcManifest.versions.stream().filter(versions -> versions.id.equalsIgnoreCase(minecraftVersion)).findFirst();
		}

		if (optionalVersion.isPresent()) {
			if (offline) {
				if (minecraftJson.exists()) {
					//If there is the manifest already we'll presume that's good enough
					getProject().getLogger().debug("Found Minecraft {} manifest, presuming up-to-date", minecraftVersion);
				} else {
					//If we don't have the manifests then there's nothing more we can do
					throw new GradleException("Minecraft " + minecraftVersion + " manifest not found at " + minecraftJson.getAbsolutePath());
				}
			} else {
				if (StaticPathWatcher.INSTANCE.hasFileChanged(minecraftJson.toPath()) || isRefreshDeps()) {
					getProject().getLogger().debug("Downloading Minecraft {} manifest", minecraftVersion);
					DownloadUtil.downloadIfChanged(new URL(optionalVersion.get().url), minecraftJson, getProject().getLogger());
				}
			}
		} else {
			throw new RuntimeException("Failed to find minecraft version: " + minecraftVersion);
		}
	}

	private void downloadJars(Logger logger) throws IOException {
		if (getExtension().isShareCaches() && !getExtension().isRootProject() && minecraftClientJar.exists() && minecraftServerJar.exists() && !isRefreshDeps()) {
			return;
		}

		DownloadUtil.downloadIfChanged(new URL(versionInfo.downloads.get("client").url), minecraftClientJar, logger);
		DownloadUtil.downloadIfChanged(new URL(versionInfo.downloads.get("server").url), minecraftServerJar, logger);
	}

	private void createSrgJars(Logger logger) throws Exception {
		logger.lifecycle(":remapping minecraft (MCP, official -> srg)");

		McpConfigProvider volde = getExtension().getMcpConfigProvider();
		File root = new File(getExtension().getUserCache(), "mcp_root");
		root.mkdirs();
		MCPWrapper wrapper = new MCPWrapper(volde.getMcp(), root);

		// Client
		{
			MCPRuntime runtime = wrapper.getRuntime(getProject(), "client");
			File output = runtime.execute(logger, "rename");
			Files.copy(output, minecraftClientSrgJar);
		}

		// Server
		{
			MCPRuntime runtime = wrapper.getRuntime(getProject(), "server");
			File output = runtime.execute(logger, "rename");
			Files.copy(output, minecraftServerSrgJar);
		}
	}

	private void injectForgeClasses(Logger logger) throws IOException {
		logger.lifecycle(":injecting forge classes into minecraft");
		copyAll(getExtension().getForgeUniversalProvider().getForge(), minecraftClientPatchedSrgJar);
		copyAll(getExtension().getForgeUniversalProvider().getForge(), minecraftServerPatchedSrgJar);
	}

	private void remapPatchedJars(Logger logger) throws IOException {
		logger.lifecycle(":remapping minecraft (Atlas, srg -> official)");

		useAtlas(MappingSet::reverse, atlas -> {
			atlas.run(minecraftClientPatchedSrgJar.toPath(), minecraftClientPatchedJar.toPath());
			atlas.run(minecraftServerPatchedSrgJar.toPath(), minecraftServerPatchedJar.toPath());
		});
	}

	private void useAtlas(UnaryOperator<MappingSet> mappingOp, IoConsumer<Atlas> action) throws IOException {
		try (Reader mappingReader = new FileReader(getExtension().getMcpConfigProvider().getSrg());
				TSrgReader reader = new TSrgReader(mappingReader);
				Atlas atlas = new Atlas()) {
			MappingSet mappings = mappingOp.apply(reader.read());

			atlas.install(ctx -> new JarEntryRemappingTransformer(
					new LorenzRemapper(mappings, ctx.inheritanceProvider())
			));

			for (File library : getLibraryProvider().getLibraries()) {
				atlas.use(library.toPath());
			}

			action.accept(atlas);
		}
	}

	private void patchJars(Logger logger) throws IOException {
		logger.lifecycle(":patching jars");

		PatchProvider patchProvider = getExtension().getPatchProvider();
		patchJars(minecraftClientSrgJar, minecraftClientPatchedSrgJar, patchProvider.clientPatches);
		patchJars(minecraftServerSrgJar, minecraftServerPatchedSrgJar, patchProvider.serverPatches);

		logger.lifecycle(":copying missing classes into patched jars");
		copyMissingClasses(minecraftClientSrgJar, minecraftClientPatchedSrgJar);
		copyMissingClasses(minecraftServerSrgJar, minecraftServerPatchedSrgJar);
	}

	private void patchJars(File clean, File output, Path patches) throws IOException {
		ConsoleTool.main(new String[]{
				"--clean", clean.getAbsolutePath(),
				"--output", output.getAbsolutePath(),
				"--apply", patches.toAbsolutePath().toString()
		});
	}

	private void mergeJars(Logger logger) throws IOException {
		if (getExtension().isForge()) {
			// FIXME: Hack here: There are no server-only classes so we can just copy the client JAR.
			Files.copy(minecraftClientPatchedJar, minecraftMergedJar);

			logger.lifecycle(":copying resources");

			// Copy resources
			copyNonClassFiles(minecraftClientJar, minecraftMergedJar);
			copyNonClassFiles(minecraftServerJar, minecraftMergedJar);
		} else {
			logger.lifecycle(":merging jars");

			try (JarMerger jarMerger = new JarMerger(minecraftClientJar, minecraftServerJar, minecraftMergedJar)) {
				jarMerger.enableSyntheticParamsOffset();
				jarMerger.merge();
			}
		}
	}

	private void walkFileSystems(File source, File target, Predicate<Path> filter, FsPathConsumer action) throws IOException {
		try (FileSystem sourceFs = FileSystems.newFileSystem(new URI("jar:" + source.toURI()), ImmutableMap.of("create", false));
				FileSystem targetFs = FileSystems.newFileSystem(new URI("jar:" + target.toURI()), ImmutableMap.of("create", false))) {
			for (Path rootDirectory : sourceFs.getRootDirectories()) {
				java.nio.file.Files.walk(rootDirectory)
						.filter(java.nio.file.Files::isRegularFile)
						.filter(filter)
						.forEach(it -> {
							Path targetPath = targetFs.getPath(it.toString());

							try {
								action.accept(sourceFs, targetFs, it, targetPath);
							} catch (IOException e) {
								throw new UncheckedIOException(e);
							}
						});
			}
		} catch (URISyntaxException e) {
			throw new IOException(e);
		}
	}

	private void copyAll(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> true, this::copyReplacing);
	}

	private void copyMissingClasses(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> it.toString().endsWith(".class"), (sourceFs, targetFs, sourcePath, targetPath) -> {
			if (java.nio.file.Files.exists(targetPath)) return;
			Path parent = targetPath.getParent();

			if (parent != null) {
				java.nio.file.Files.createDirectories(parent);
			}

			java.nio.file.Files.copy(sourcePath, targetPath);
		});
	}

	private void copyNonClassFiles(File source, File target) throws IOException {
		walkFileSystems(source, target, it -> !it.toString().endsWith(".class"), this::copyReplacing);
	}

	private void copyReplacing(FileSystem sourceFs, FileSystem targetFs, Path sourcePath, Path targetPath) throws IOException {
		Path parent = targetPath.getParent();

		if (parent != null) {
			java.nio.file.Files.createDirectories(parent);
		}

		java.nio.file.Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
	}

	public File getMergedJar() {
		return minecraftMergedJar;
	}

	public String getMinecraftVersion() {
		return minecraftVersion;
	}

	public MinecraftVersionInfo getVersionInfo() {
		return versionInfo;
	}

	public MinecraftLibraryProvider getLibraryProvider() {
		return libraryProvider;
	}

	public String getJarSuffix() {
		return jarSuffix;
	}

	@Override
	public String getTargetConfig() {
		return Constants.MINECRAFT;
	}
}
