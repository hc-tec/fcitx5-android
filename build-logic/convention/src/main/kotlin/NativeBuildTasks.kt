/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024-2025 Fcitx5 for Android Contributors
 */
import com.android.build.gradle.internal.cxx.configure.rewriteWithLocations
import com.android.build.gradle.internal.cxx.logging.PassThroughRecordingLoggingEnvironment
import com.android.build.gradle.internal.cxx.model.CxxAbiModel
import com.android.build.gradle.tasks.ExternalNativeBuildJsonTask
import com.android.build.gradle.tasks.ExternalNativeBuildTask
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.withType
import org.gradle.process.ExecSpec
import java.io.File

fun ExternalNativeBuildJsonTask.abiModel(): CxxAbiModel {
    val abi = ExternalNativeBuildJsonTask::class.java.declaredFields.find { it.name == "abi" }!!
    abi.isAccessible = true
    return abi.get(this) as CxxAbiModel
}

/**
 * This function contains `configureEach` call, to avoid "task warming-up failure",
 * **DO NOT** call this during other tasks' configuration
 */
fun Project.getCxxAbiModelProperty(): Provider<CxxAbiModel> =
    providers.provider {
        val jsonTask =
            tasks.withType<ExternalNativeBuildJsonTask>()
                .sortedBy { it.name }
                .lastOrNull { task ->
                    task.state.executed || task.state.upToDate || task.state.skipped
                }
                ?: error("No ExternalNativeBuildJsonTask has completed for $path")
        // `CxxAbiModel.rewriteWithLocations` requires a "Non-default logger"
        // https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:build-system/gradle-core/src/main/java/com/android/build/gradle/internal/cxx/configure/NativeLocationsBuildService.kt;drc=b5516899015633c99dc64b510d9729c4e001e89c;l=67
        PassThroughRecordingLoggingEnvironment().use {
            jsonTask.abiModel().rewriteWithLocations(jsonTask.nativeLocationsBuildService.get())
        }
    }

abstract class CMakeBuildInstallTask : DefaultTask() {
    // The ABI model is discovered from AGP's configureCMake task at execution time.
    // Treat it as internal state so Gradle does not require the value during task graph calculation.
    @get:Internal
    abstract val cxxAbiModel: Property<CxxAbiModel>

    @get:Input
    @get:Optional
    abstract val buildTarget: Property<String>

    @get:Input
    @get:Optional
    abstract val installComponent: Property<String>

    @get:Input
    abstract val destDir: Property<File>

    private fun exec(action: Action<ExecSpec>) {
        project.providers.exec(action).result.get()
    }

    @TaskAction
    fun execute() {
        val cxxAbiModel = this.cxxAbiModel.get()
        val buildTarget = this.buildTarget.getOrElse("")
        val installComponent = this.installComponent.getOrElse("")
        val destDir = this.destDir.get()
        val cmake = cxxAbiModel.variant.module.cmake!!.cmakeExe!!
        if (buildTarget.isNotEmpty()) {
            exec {
                commandLine(
                    cmake,
                    "--build", cxxAbiModel.cxxBuildFolder,
                    "--target", buildTarget
                )
            }
        }
        if (installComponent.isNotEmpty()) {
            exec {
                environment("DESTDIR", destDir.absolutePath)
                commandLine(
                    cmake,
                    "--install", cxxAbiModel.cxxBuildFolder,
                    "--component", installComponent
                )
            }
        }
    }
}

/**
 * Important: make sure that the task runs after than the native task
 * Since we can't declare the dependency relationship, a weaker running order constraint must be enforced
 */

fun Task.runAfterNativeBuild(project: Project) {
    project.tasks.withType<ExternalNativeBuildTask>().all externalNativeBuild@{
        this@runAfterNativeBuild.mustRunAfter(this@externalNativeBuild)
    }
}
