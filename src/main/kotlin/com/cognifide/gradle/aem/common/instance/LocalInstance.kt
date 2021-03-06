package com.cognifide.gradle.aem.common.instance

import com.cognifide.gradle.aem.AemExtension
import com.cognifide.gradle.aem.common.file.FileOperations
import com.cognifide.gradle.aem.common.instance.local.Script
import com.cognifide.gradle.aem.common.instance.local.Status
import com.cognifide.gradle.common.utils.Formats
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.JavaVersion
import org.apache.commons.lang3.StringUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.internal.os.OperatingSystem

class LocalInstance private constructor(aem: AemExtension) : Instance(aem) {

    override var user: String = USER

    var debugPort: Int = 5005

    var debugAddress: String = ""

    private val debugSocketAddress: String
        get() = when (debugAddress) {
            "*" -> "0.0.0.0:$debugPort"
            "" -> "$debugPort"
            else -> "$debugAddress:$debugPort"
        }

    @get:JsonIgnore
    val jvmOptsDefaults: List<String>
        get() = mutableListOf<String>().apply {
            if (debugPort in 1..65535) {
                add(jvmDebugOpt)
            }
            if (password != Instance.PASSWORD_DEFAULT) {
                add("-Dadmin.password=$password")
            }
        }

    @get:JsonIgnore
    private val jvmDebugOpt: String
        get() = when {
            SystemUtils.isJavaVersionAtLeast(JavaVersion.JAVA_9) ->
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debugSocketAddress"
            else ->
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$debugPort"
        }

    @get:JsonIgnore
    var jvmOpts: List<String> = listOf(
            "-server", "-Xmx2048m", "-XX:MaxPermSize=512M", "-Djava.awt.headless=true"
    )

    @get:JsonProperty("jvmOpts")
    val jvmOptsString: String get() = (jvmOptsDefaults + jvmOpts).joinToString(" ")

    @get:JsonIgnore
    var startOpts: List<String> = listOf()

    @get:JsonProperty("startOpts")
    val startOptsString: String get() = startOpts.joinToString(" ")

    @get:JsonIgnore
    val runModesDefault get() = listOf(type.name.toLowerCase())

    @get:JsonIgnore
    var runModes: List<String> = listOf(ENVIRONMENT)

    @get:JsonProperty("runModes")
    val runModesString: String get() = (runModesDefault + runModes).joinToString(",")

    @get:JsonIgnore
    val dir get() = aem.localInstanceManager.rootDir.get().asFile.resolve(id)

    @get:JsonIgnore
    val overridesDirs get() = localManager.overrideDir.get().asFile.run { listOf(resolve("common"), resolve(id)) }

    @get:JsonIgnore
    val jar get() = dir.resolve("aem-quickstart.jar")

    @get:JsonIgnore
    val quickstartDir get() = dir.resolve("crx-quickstart")

    @get:JsonIgnore
    val license get() = dir.resolve("license.properties")

    @get:JsonIgnore
    val versionFile get() = dir.resolve("version.txt")

    override val version: String
        get() {
            var result = super.version
            if (result == Formats.versionUnknown().version && versionFile.exists()) {
                result = versionFile.readText()
            }
            return result
        }

    internal fun saveVersion() {
        if (version != Formats.versionUnknown().version) {
            versionFile.writeText(version)
        }
    }

    private val startScript: Script get() = binScript("start")

    internal fun executeStartScript() = try {
        logger.info("Executing start script: $startScript")
        startScript.executeAsync()
    } catch (e: InstanceException) {
        throw InstanceException("Instance start script failed! Check resources like disk free space, open HTTP ports etc.", e)
    }

    private val stopScript: Script get() = binScript("stop")

    internal fun executeStopScript() {
        try {
            logger.info("Executing stop script: $stopScript")
            stopScript.executeAsync()
        } catch (e: InstanceException) {
            throw InstanceException("Instance stop script failed!", e)
        }

        try {
            sync.osgiFramework.stop()
        } catch (e: InstanceException) {
            // ignore, fallback for sure
        }
    }

    private val statusScript: Script get() = binScript("status")

    @get:JsonIgnore
    val touched: Boolean get() = dir.exists()

    @get:JsonIgnore
    val created: Boolean get() = locked(LOCK_CREATE)

    @get:JsonIgnore
    val initialized: Boolean get() = locked(LOCK_INIT)

    @get:JsonIgnore
    val installDir get() = quickstartDir.resolve("install")

    private fun binScript(name: String, os: OperatingSystem = OperatingSystem.current()): Script {
        return if (os.isWindows) {
            Script(this, listOf("cmd", "/C"), dir.resolve("$name.bat"), quickstartDir.resolve("bin/$name.bat"))
        } else {
            Script(this, listOf("sh"), dir.resolve(name), quickstartDir.resolve("bin/$name"))
        }
    }

    @get:JsonIgnore
    val localManager: LocalInstanceManager get() = aem.localInstanceManager

    fun create() = localManager.create(this)

    internal fun prepare() {
        cleanDir(true)
        copyFiles()
        validateFiles()
        unpackFiles()
        correctFiles()
        customize()
        lock(LOCK_CREATE)
    }

    private fun copyFiles() {
        dir.mkdirs()

        logger.info("Copying quickstart JAR '$jar' to directory '$quickstartDir'")
        localManager.quickstart.jar?.let { FileUtils.copyFile(it, jar) }

        logger.info("Copying quickstart license '$license' to directory '$quickstartDir'")
        localManager.quickstart.license?.let { FileUtils.copyFile(it, license) }
    }

    private fun validateFiles() {
        if (!jar.exists()) {
            throw InstanceException("Instance JAR file not found at path: ${jar.absolutePath}. Is instance JAR URL configured?")
        }

        if (!license.exists()) {
            throw InstanceException("License file not found at path: ${license.absolutePath}. Is instance license URL configured?")
        }
    }

    private fun correctFiles() {
        FileOperations.amendFile(binScript("start", OperatingSystem.forName("windows")).bin) { origin ->
            var result = origin

            // Force CMD to be launched in closable window mode.
            result = result.replace(
                    "start \"CQ\" cmd.exe /K",
                    "start /min \"CQ\" cmd.exe /C"
            ) // AEM <= 6.2
            result = result.replace(
                    "start \"CQ\" cmd.exe /C",
                    "start /min \"CQ\" cmd.exe /C"
            ) // AEM 6.3

            // Introduce missing CQ_START_OPTS injectable by parent script.
            result = result.replace(
                    "set START_OPTS=start -c %CurrDirName% -i launchpad",
                    "set START_OPTS=start -c %CurrDirName% -i launchpad %CQ_START_OPTS%"
            )

            result
        }

        FileOperations.amendFile(binScript("start", OperatingSystem.forName("unix")).bin) { origin ->
            var result = origin

            // Introduce missing CQ_START_OPTS injectable by parent script.
            result = result.replace(
                    "START_OPTS=\"start -c ${'$'}{CURR_DIR} -i launchpad\"",
                    "START_OPTS=\"start -c ${'$'}{CURR_DIR} -i launchpad ${'$'}{CQ_START_OPTS}\""
            )

            result
        }

        // Ensure that 'logs' directory exists
        quickstartDir.resolve("logs").mkdirs()
    }

    private fun unpackFiles() {
        logger.info("Unpacking quickstart from JAR '$jar' to directory '$quickstartDir'")

        common.progressIndicator {
            message = "Unpacking quickstart JAR: ${jar.name}, size: ${Formats.fileSize(jar)}"
            aem.project.javaexec { spec ->
                spec.workingDir = dir
                spec.main = "-jar"
                spec.args = listOf(jar.name, "-unpack")
            }
        }
    }

    internal fun delete() = cleanDir(create = false)

    private fun cleanDir(create: Boolean) {
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        if (create) {
            dir.mkdirs()
        }
    }

    internal fun customize() {
        FileOperations.copyResources(FILES_PATH, dir, false)

        overridesDirs.filter { it.exists() }.forEach {
            FileUtils.copyDirectory(it, dir)
        }

        val propertiesAll = mapOf("instance" to this) + properties + localManager.expandProperties.get()

        FileOperations.amendFiles(dir, localManager.expandFiles.get()) { file, source ->
            aem.prop.expand(source, propertiesAll, file.absolutePath)
        }

        FileOperations.amendFile(binScript("start", OperatingSystem.forName("windows")).bin) { origin ->
            var result = origin

            // Update window title
            val previousWindowTitle = StringUtils.substringBetween(origin, "start /min \"", "\" cmd.exe ")
            if (previousWindowTitle != null) {
                result = StringUtils.replace(result,
                        "start /min \"$previousWindowTitle\" cmd.exe ",
                        "start /min \"$windowTitle\" cmd.exe "
                )
            }

            result
        }

        val installFiles = localManager.install.files
        if (installFiles.isNotEmpty()) {
            installDir.mkdirs()
            installFiles.forEach { source ->
                val target = installDir.resolve(source.name)
                if (!target.exists()) {
                    logger.info("Copying quickstart install file from '$source' to '$target'")
                    FileUtils.copyFileToDirectory(source, installDir)
                }
            }
        }
    }

    fun up() = localManager.up(this)

    fun down() = localManager.down(this)

    @get:JsonIgnore
    val status: Status get() = checkStatus()

    fun checkStatus(): Status {
        if (!created) {
            return Status.UNKNOWN
        }

        return try {
            val procResult = statusScript.executeSync()
            Status.byExitCode(procResult.exitValue)
        } catch (e: InstanceException) {
            logger.info("Instance status not available: $this")
            logger.debug("Instance status error", e)
            Status.UNKNOWN
        }
    }

    @get:JsonIgnore
    val running: Boolean get() = created && checkStatus() == Status.RUNNING

    @get:JsonIgnore
    val runningDir get() = property("user.dir")?.let { aem.project.file(it) }

    @get:JsonIgnore
    val runningOther get() = runningDir?.let { dir != it } ?: false

    internal fun init(callback: LocalInstance.() -> Unit) {
        apply(callback)
        lock(LOCK_INIT)
    }

    fun destroy() = localManager.destroy(this)

    private fun lockFile(name: String) = dir.resolve("$name.lock")

    private fun lock(name: String) = FileOperations.lock(lockFile(name))

    private fun locked(name: String): Boolean = lockFile(name).exists()

    @get:JsonIgnore
    val windowTitle get() = "LocalInstance(name='$name', httpUrl='$httpUrl'" +
            (version.takeIf { it != Formats.versionUnknown().version }?.run { ", version=$this" } ?: "") +
            ", debugPort=$debugPort, user='$user', password='${Formats.toPassword(password)}')"

    override fun toString() = "LocalInstance(name='$name', httpUrl='$httpUrl')"

    companion object {

        const val FILES_PATH = "instance/local"

        const val ENVIRONMENT = "local"

        const val USER = "admin"

        const val LOCK_CREATE = "create"

        const val LOCK_INIT = "init"

        fun create(aem: AemExtension, httpUrl: String, configurer: LocalInstance.() -> Unit = {}): LocalInstance {
            return LocalInstance(aem).apply {
                val instanceUrl = InstanceUrl.parse(httpUrl)
                if (instanceUrl.user != USER) {
                    throw InstanceException("User '${instanceUrl.user}' (other than 'admin') is not allowed while using local instance(s).")
                }

                this.httpUrl = instanceUrl.httpUrl
                this.password = instanceUrl.password
                this.id = instanceUrl.id
                this.debugPort = instanceUrl.debugPort
                this.environment = aem.commonOptions.env.get()

                configurer()
                validate()
            }
        }
    }
}
