/*
 * Copyright 2014 Centril / Mazdak Farrokhzad <twingoow@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.centril.robospock

import com.jakewharton.sdkmanager.internal.PackageResolver
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.StopExecutionException

/**
 * {@link RoboSpockAction}: Is the heart of the plugin,
 * this is where all the action happens.
 *
 * @author Mazdak Farrokhzad <twingoow@gmail.com>
 * @version 1.0
 * @since Oct , 02, 2014
 */
class RoboSpockAction implements Action<RoboSpockConfiguration> {
	public static String robospockTaskName = 'robospock'
	public static final String ZIP_2_JAR_TASK = 'robospock_zip2jar'
	public static final String ZIP_2_JAR_DESCRIPTION = "Zips for Robospock."

	static void perform( RoboSpockConfiguration cfg ) {
		// Add jcenter to buildscript repo.
		addJCenterBuildScript( cfg )

		def p = cfg.perspective

		// Temporarily fix the issue with not being able to set the tester in inverse mode.
		// Due to be removed in Gradle 2.2.
		if ( p.hasProperty( 'robospockTester' ) ) {
			cfg.tester = cfg.project.getProperty( 'robospockTester' )
		}

		// Apply the groovy plugin.
		cfg.tester.apply plugin: 'groovy'

		// Configure robospock.
		p.afterEvaluate {
			new RoboSpockAction().execute( cfg )
		}
	}

	@Override
	void execute( RoboSpockConfiguration cfg ) {
		cfg.verify()

		def run = [this.&addJCenter, this.&addAndroidRepositories, this.&addDependencies,
				   this.&fixSupportLib, this.&copyAndroidDependencies, this.&setupTestTask,
				   this.&fixRobolectricBugs, this.&executeAfterConfigured]
		run.each { it cfg }
	}

	def executeAfterConfigured( RoboSpockConfiguration cfg ) {
		cfg.executeAfterConfigured()
	}

	/**
	 * Makes sure that android-sdk-manager pulls support libs.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def fixSupportLib( RoboSpockConfiguration cfg ) {
		// Roboelectric needs this, make com.jakewharton.sdkmanager download it.
		cfg.tester.dependencies {
			testCompile 'com.android.support:support-v4:19.0.1'
		}
		cfg.tester.ext.android = cfg.android.android

		/*
		 * Fix issue: https://github.com/Centril/gradle-plugin-robospock/issues/6
		 * TODO: Remove once in upstream of com.jakewharton.sdkmanager.
		 *
		 * GroovyCastException: Cannot cast object '21.1.0' with class
		 * 'com.android.sdklib.repository.FullRevision' to class
		 * 'com.android.sdklib.repository.FullRevision'
		 */
		PackageResolver.metaClass.resolveBuildTools { ->
		    def buildToolsRevision = project.android.buildToolsRevision
		    log.debug "Build tools version: $buildToolsRevision"

		    def buildToolsRevisionDir = new File(buildToolsDir, buildToolsRevision.toString())
		    if (folderExists(buildToolsRevisionDir)) {
		        log.debug 'Build tools found!'
		        return
		    }

		    log.lifecycle "Build tools $buildToolsRevision missing. Downloading..."

		    def code = androidCommand.update "build-tools-$buildToolsRevision"
		    if (code != 0) {
		        throw new StopExecutionException("Build tools download failed with code $code.")
		    }
		}

		PackageResolver.resolve cfg.tester, cfg.sdkDir()
	}

	/**
	 * Adds the jcenter() to repositories.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def addJCenter( RoboSpockConfiguration cfg ) {
		cfg.tester.repositories {
			jcenter()
		}
	}

	/**
	 * Adds the jcenter() to buildscript repositories.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def static addJCenterBuildScript( RoboSpockConfiguration cfg ) {
		cfg.perspective.buildscript {
			repositories {
				jcenter()
			}
		}
	}

	/**
	 * Sets up the test task.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def setupTestTask( RoboSpockConfiguration cfg ) {
		cfg.robospockTask = cfg.tester.tasks.create( name: robospockTaskName, type: RoboSpockTest ) {
			config = cfg
			configure()
		}

		// Remove all actions on test & make it basically do robospock task.
		cfg.tester.test {
			deleteAllActions()
			dependsOn cfg.robospockTask
		}
	}

	/**
	 * Fixes/addresses various bugs in robolectric.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def fixRobolectricBugs( RoboSpockConfiguration cfg ) {
		def manifest = 'AndroidManifest.xml'
		def correctManifestPath = 'intermediates/manifests/full'

		cfg.variants.each { variant ->
			def taskName = "robospockFixRobolectricBugs${variant.name.capitalize()}"
			def copier = cfg.android.tasks.create( name: taskName )
			variant.getOutputs()[0].processResources.finalizedBy copier

			copier << {
				// Library: Copy manifest, intermediates/manifests/full/ -> intermediates/bundles/
				cfg.android.copy {
					from( "${cfg.android.buildDir}/intermediates/bundles/${variant.dirName}/" ) {
						include manifest
					}
					into( "${cfg.android.buildDir}/${correctManifestPath}/${variant.dirName}/" )
				}

				// Manifest: Clamp any SDK VERSION in the eyes of roboelectric to API level 18.
				cfg.android.copy {
					from( "${cfg.android.buildDir}" ) {
						include "${correctManifestPath}/${variant.dirName}/${manifest}"
					}
					into( "${cfg.tester.buildDir}" )
					filter {
						it.replaceFirst( ~/android\:targetSdkVersion\=\"(\d{1,2})\"/, {
							def ver = Integer.parseInt( it[1] ) > 18
							return 'android:targetSdkVersion="' + 18 + '"'
						} )
					}
				}

				// Library: Copy intermediates/bundles/{buildType}/res/ -> intermediates/res/{buildType}/
				cfg.android.copy {
					from( "${cfg.android.buildDir}/intermediates/bundles/${variant.dirName}/res/" )
					into( "${cfg.android.buildDir}/intermediates/res/${variant.dirName}/" )
				}
			}
		}
	}

	/**
	 * Adds all the dependencies of this configuration to {@link Project}.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def addDependencies( RoboSpockConfiguration cfg ) {
		def deps = [
				"org.codehaus.groovy:groovy-all:${cfg.groovyVersion}",
				"org.spockframework:spock-core:${cfg.spockVersion}",
				"org.robospock:robospock:${cfg.robospockVersion}"
		]

		cfg.cglibVersion = cfg.cglibVersion.trim()
		if ( cfg.cglibVersion ) {
			deps << "cglib:cglib-nodep:${cfg.cglibVersion}"
		}

		cfg.objenesisVersion = cfg.objenesisVersion.trim()
		if ( cfg.objenesisVersion ) {
			deps << "org.objenesis:objenesis:${cfg.objenesisVersion}"
		}

		deps.each { dep ->
			cfg.tester.dependencies {
				testCompile dep
			}
		}
	}

	/**
	 * Adds the android SDK dir repositories to {@link RoboSpockConfiguration#android}.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def addAndroidRepositories( RoboSpockConfiguration cfg ) {
		def sdkDir = cfg.sdkDir()
		cfg.tester.repositories {
			maven { url "${sdkDir}/extras/android/m2repository" }
			maven { url "${sdkDir}/extras/google/m2repository" }
		}
	}

	/**
	 * Copies the android project dependencies to this project.
	 *
	 * @param cfg the {@link RoboSpockConfiguration} object.
	 */
	def copyAndroidDependencies( RoboSpockConfiguration cfg ) {
		def tester = cfg.tester
		def android = cfg.android
		def projDep = getSubprojects( android ) + android

		def zip2jarDependsTask = "compile${cfg.buildType.capitalize()}Java"

		projDep.each { proj ->
			def libsPath = new File( android.buildDir, 'libs' )
			def aarPath = new File( android.buildDir, 'intermediates/exploded-aar/' )

			// Create zip2jar task & make compileJava depend on it.
			Task zip2jar = proj.tasks.create( name: ZIP_2_JAR_TASK, type: Zip ) {
				dependsOn zip2jarDependsTask
				description ZIP_2_JAR_DESCRIPTION
				from new File( android.buildDir, "intermediates/classes/${cfg.buildType}" )
				destinationDir = libsPath
				extension = "jar"
			}
			tester.tasks.compileTestJava.dependsOn( zip2jar )

			// Add all jars frm zip2jar + exploded-aar:s to dependencies.
			tester.dependencies {
				testCompile tester.fileTree( dir: libsPath, include: "*.jar" )
				testCompile tester.fileTree( dir: aarPath, include: ['*/*/*/*.jar'] )
				testCompile tester.fileTree( dir: aarPath, include: ['*/*/*/*/*.jar'] )
			}
		}
	}

	/**
	 * Returns all sub projects to the android project.
	 *
	 * @param project the {@link org.gradle.api.Project}.
	 * @return the sub{@link org.gradle.api.Project}s.
	 */
	def List<Project> getSubprojects( Project project ) {
		def projects = []
		extractSubprojects( project, projects )
		return projects
	}

	/**
	 * Recursively extracts all dependency-subprojects from project.
	 *
	 * @param libraryProject the library {@link Project} to search in.
	 * @param projects the list of {@link Project}s to add to.
	 * @return the sub{@link Project}s.
	 */
	def extractSubprojects( Project libraryProject, List<Project> projects ) {
		Configuration compile = libraryProject.configurations.all.find { it.name == 'compile' }

		def projDeps = compile.allDependencies
				.findAll { it instanceof ProjectDependency }
				.collect { ((ProjectDependency) it).dependencyProject }

		projDeps.each { extractSubprojects( it, projects ) }
		projects.addAll( projDeps )
	}
}
