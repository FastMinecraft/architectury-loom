/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.task;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.extension.LoomFiles;
import net.fabricmc.loom.util.Constants;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public abstract class UnpickJarTask extends DefaultTask {
	@InputFile
	public abstract RegularFileProperty getInputJar();

	@InputFile
	public abstract RegularFileProperty getUnpickDefinitions();

	@InputFiles
	// Only 1 file, but it comes from a configuration
	public abstract ConfigurableFileCollection getConstantJar();

	@InputFiles
	public abstract ConfigurableFileCollection getUnpickClasspath();

	@OutputFile
	public abstract RegularFileProperty getOutputJar();

	@Inject
	public abstract WorkerExecutor getWorkerExecutor();

	@Inject
	public UnpickJarTask() {
		getConstantJar().setFrom(getProject().getConfigurations().getByName(Constants.Configurations.MAPPING_CONSTANTS));
		getUnpickClasspath().setFrom(getProject().getConfigurations().getByName(Constants.Configurations.MINECRAFT_DEPENDENCIES));
	}

	@TaskAction
	public void exec() {
		writeUnpickLogConfig();

		WorkQueue workQueue = getWorkerExecutor().classLoaderIsolation(spec -> {
			spec.getClasspath().from(getProject().getConfigurations().getByName(Constants.Configurations.UNPICK_CLASSPATH));
		});

		workQueue.submit(UnpickJarAction.class, params -> {
			params.getInputJar().set(getInputJar());
			params.getOutputJar().set(getOutputJar());
			params.getUnpickDefinitions().set(getUnpickDefinitions());

			params.getConstantJar().set(getConstantJar().getSingleFile());

			for (Path minecraftJar : getExtension().getMinecraftJars(MappingsNamespace.NAMED)) {
				params.getMinecraftJars().from(minecraftJar.toFile().getAbsoluteFile());
			}

			params.getUnpickClasspath().from(getUnpickClasspath());

			params.getLoggingConfig().set(getDirectories().getUnpickLoggingConfigFile().getAbsoluteFile());
		});

		workQueue.await();
	}

	private void writeUnpickLogConfig() {
		try (InputStream is = UnpickJarTask.class.getClassLoader().getResourceAsStream("unpick-logging.properties")) {
			assert is != null;
			Files.deleteIfExists(getDirectories().getUnpickLoggingConfigFile().toPath());
			Files.copy(is, getDirectories().getUnpickLoggingConfigFile().toPath());
		} catch (IOException e) {
			throw new RuntimeException("Failed to copy unpick logging config", e);
		}
	}

	@Internal
	protected LoomGradleExtension getExtension() {
		return LoomGradleExtension.get(getProject());
	}

	private LoomFiles getDirectories() {
		return getExtension().getFiles();
	}

	public interface UnpickJarParams extends WorkParameters {
		RegularFileProperty getInputJar();

		RegularFileProperty getOutputJar();

		RegularFileProperty getUnpickDefinitions();

		RegularFileProperty getConstantJar();

		ConfigurableFileCollection getMinecraftJars();

		ConfigurableFileCollection getUnpickClasspath();

		RegularFileProperty getLoggingConfig();
	}

	public abstract static class UnpickJarAction implements WorkAction<UnpickJarParams> {
		@Override
		public void execute() {
			Method mainMethod;

			try {
				Class<?> mainClass = Class.forName("daomephsta.unpick.cli.Main");
				mainMethod = mainClass.getDeclaredMethod("main", String[].class);
			} catch (ClassNotFoundException | NoSuchMethodException e) {
				throw new RuntimeException(e);
			}

			UnpickJarParams params = getParameters();

			System.setProperty("java.util.logging.config.file", params.getLoggingConfig().get().getAsFile().getAbsolutePath());

			Set<File> minecraftJarFiles = params.getMinecraftJars().getFiles();
			Set<File> unpickClasspathFiles = params.getUnpickClasspath().getFiles();
			String[] args = new String[4 + minecraftJarFiles.size() + unpickClasspathFiles.size()];
			args[0] = params.getInputJar().get().getAsFile().getAbsolutePath();
			args[1] = params.getOutputJar().get().getAsFile().getAbsolutePath();
			args[2] = params.getUnpickDefinitions().get().getAsFile().getAbsolutePath();
			args[3] = params.getConstantJar().get().getAsFile().getAbsolutePath();

			int i = 0;
			for (File file : minecraftJarFiles) {
				args[4 + i++] = file.getAbsolutePath();
			}
			for (File file : unpickClasspathFiles) {
				args[4 + i++] = file.getAbsolutePath();
			}

			try {
				mainMethod.invoke(null, (Object) args);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		}
	}
}
