package com.prezi.gradle.pride.cli.commands;

import com.google.common.base.Function;
import com.google.common.collect.Maps;
import com.prezi.gradle.pride.Module;
import com.prezi.gradle.pride.Pride;
import com.prezi.gradle.pride.PrideException;
import com.prezi.gradle.pride.RuntimeConfiguration;
import com.prezi.gradle.pride.cli.PrideInitializer;
import com.prezi.gradle.pride.cli.gradle.GradleConnectorManager;
import com.prezi.gradle.pride.vcs.Vcs;
import io.airlift.command.Command;
import io.airlift.command.Option;

import java.io.File;
import java.io.FileFilter;
import java.util.Map;

import static com.prezi.gradle.pride.cli.Configurations.GRADLE_WRAPPER;

@Command(name = "init", description = "Initialize pride")
public class InitCommand extends AbstractConfiguredCommand {

	@Option(name = {"-f", "--force"},
			description = "Force initialization of a pride, even if one already exists")
	private boolean explicitForce;

	@Option(name = "--with-wrapper",
			description = "Add a Gradle wrapper")
	private boolean explicitWithWrapper;

	@Option(name = "--no-wrapper",
			description = "Do not add Gradle wrapper")
	private boolean explicitNoWrapper;

	@Option(name = "--no-add-existing",
			description = "Do not add existing modules in the pride directory to the pride")
	private boolean explicitNoAddExisting;

	@Option(name = "--ignore-config",
			description = "Ignore existing pride's configuration (to be used with --force)")
	private boolean explicitIgnoreConfig;

	@Override
	protected int executeWithConfiguration(RuntimeConfiguration globalConfig) throws Exception {
		boolean prideExistsAlready = Pride.containsPride(getPrideDirectory());
		if (!explicitForce && prideExistsAlready) {
			throw new PrideException("A pride already exists in " + getPrideDirectory());
		}

		RuntimeConfiguration config = globalConfig;
		Map<String, Module> existingModules = Maps.newTreeMap();
		if (prideExistsAlready && !explicitIgnoreConfig) {
			try {
				Pride pride = Pride.getPride(getPrideDirectory(), globalConfig, getVcsManager());
				config = pride.getConfiguration();
				// Get existing modules
				existingModules = Maps.uniqueIndex(pride.getModules(), new Function<Module, String>() {
					@Override
					public String apply(Module module) {
						return module.getName();
					}
				});
			} catch (Exception ex) {
				logger.warn("Could not load existing pride, ignoring existing configuration");
				logger.debug("Exception was", ex);
			}
		}
		boolean addWrapper = config.override(GRADLE_WRAPPER, explicitWithWrapper, explicitNoWrapper);

		GradleConnectorManager gradleConnectorManager = new GradleConnectorManager(config);
		PrideInitializer prideInitializer = new PrideInitializer(gradleConnectorManager);
		Pride pride = prideInitializer.create(getPrideDirectory(), globalConfig, getVcsManager());

		if (addWrapper) {
			prideInitializer.addWrapper(pride);
		}

		if (!explicitNoAddExisting) {
			logger.debug("Adding existing modules");
			boolean addedAny = false;
			for (File dir : getPrideDirectory().listFiles(new FileFilter() {
				@Override
				public boolean accept(File path) {
					return path.isDirectory();
				}
			})) {
				String moduleName = dir.getName();
				String repositoryUrl;
				Vcs vcs;

				Module existingModule = existingModules.get(moduleName);
				if (existingModule != null) {
					logger.info("Found existing module from previous configuration: {}", moduleName);
					repositoryUrl = existingModule.getRemote();
					vcs = existingModule.getVcs();
				} else if (Pride.isValidModuleDirectory(dir)) {
					vcs = getVcsManager().findSupportingVcs(dir, config);
					repositoryUrl = vcs.getSupport().getRepositoryUrl(dir);
					if (repositoryUrl == null) {
						throw new PrideException("Could not detect remote URL for " + dir);
					}
				} else {
					continue;
				}
				logger.info("Adding existing {} module in {}", vcs.getType(), dir);
				pride.addModule(moduleName, repositoryUrl, vcs);
				addedAny = true;
			}
			if (addedAny) {
				pride.save();
				prideInitializer.reinitialize(pride);
			}
		}
		return 0;
	}
}
