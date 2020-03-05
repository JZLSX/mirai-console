/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress("EXPERIMENTAL_API_USAGE")

package net.mamoe.mirai.console.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.URLProtocol
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.mamoe.mirai.console.MiraiConsole
import java.io.File
import java.net.URLClassLoader
import kotlin.math.pow
import kotlin.system.exitProcess

val Http: HttpClient
    get() = HttpClient(CIO)

object MiraiCoreLoader {
    private val coresPath by lazy {
        File(System.getProperty("user.dir") + "/core/").also {
            if (!it.exists()) {
                it.mkdirs()
            }
        }
    }

    private fun getProtocolLib(): File? {
        this.coresPath.listFiles()?.forEach { file ->
            if (file != null && file.extension == "jar" && file.name.contains("qqandroid")) {
                return file
            }
        }
        return null
    }

    private fun getCore(): File? {
        this.coresPath.listFiles()?.forEach { file ->
            if (file != null && file.extension == "jar" && file.name.contains("core") && (!file.name.contains("qqandroid"))) {
                return file
            }
        }
        return null
    }


    fun loadCore(): String {
        MiraiConsole.logger("Fetching Newest Core Version .. ")
        val newest = runBlocking {
            getNewestVersion()
        }
        val current = getCurrentVersion()
        MiraiConsole.logger("Local Version: $current | Newest Version: $newest")
        if (current != newest) {
            MiraiConsole.logger("Updating from V$current -> V$newest, this is a force update")
            cleanCoreAndLib()
            runBlocking {
                downloadCoreAndLib(newest)
            }
            MiraiConsole.logger("Download complete")
        }
        MiraiConsole.logger("Loading Core")
        loadCoreAndLib()
        MiraiConsole.logger("Mirai Core Loaded, current core version $newest")
        return newest
    }


    /**
     * 判断最新版本
     * */
    private suspend fun getNewestVersion(): String {
        try {
            return """>([0-9])*\.([0-9])*\.([0-9])*/""".toRegex().findAll(
                    Http.get<String> {
                        url {
                            protocol = URLProtocol.HTTPS
                            host = "jcenter.bintray.com"
                            path("net/mamoe/mirai-core-qqandroid-jvm/")
                        }
                    }
                ).asSequence()
                .map { it.value.drop(1).dropLast(1) }
                .maxBy {
                    it.split('.').foldRightIndexed(0) { index: Int, s: String, acc: Int ->
                        acc + 100.0.pow(index).toInt() + (s.toIntOrNull() ?: 0)
                    }
                }!!
        } catch (e: Exception) {
            MiraiConsole.logger("Failed to fetch newest Core version, please seek for help")
            e.printStackTrace()
            MiraiConsole.logger("Failed to fetch newest Core version, please seek for help")
            exitProcess(1)
        }
    }

    /**
     * 判断当前版本
     * 默认返回 "0.0.0"
     */
    private fun getCurrentVersion(): String {
        val file = getProtocolLib()
        if (file == null || getCore() == null) return "0.0.0"
        val numberVersion = """([0-9])*\.([0-9])*\.([0-9])*""".toRegex().find(file.name)?.value
        if (numberVersion != null) {
            return numberVersion + file.name.substringAfter(numberVersion).substringBefore(".jar")
        }
        return "0.0.0"
    }


    private fun cleanCoreAndLib() {
        this.coresPath.listFiles()?.forEach {
            if (it != null && it.extension == "jar") {
                it.delete()
            }
        }
    }


    @Suppress("SpellCheckingInspection")
    private object Links {
        internal const val libJcenter =
            "https://jcenter.bintray.com/net/mamoe/mirai-core-qqandroid-jvm/{version}/:mirai-core-qqandroid-jvm-{version}.jar"
        internal const val libAliyun =
            "https://maven.aliyun.com/nexus/content/repositories/jcenter/net/mamoe/mirai-core-qqandroid-jvm/{version}/mirai-core-qqandroid-jvm-{version}.jar"

        internal const val coreJcenter =
            "https://jcenter.bintray.com/net/mamoe/mirai-core-jvm/{version}/:mirai-core-jvm-{version}.jar"
        internal const val coreAliyun =
            "https://maven.aliyun.com/nexus/content/repositories/jcenter/net/mamoe/mirai-core-jvm/{version}/mirai-core-jvm-{version}.jar"
    }

    private suspend fun downloadCoreAndLib(version: String) {
        var fileStream = File(coresPath.absolutePath + "/" + "mirai-core-qqandroid-jvm-$version.jar").also {
            withContext(Dispatchers.IO) {
                it.createNewFile()
            }
        }.outputStream()

        suspend fun downloadRequest(url: String, version: String): ByteReadChannel {
            return Http.get<HttpResponse>(url.replace("{version}", version)).content
        }

        var stream = kotlin.runCatching {
            MiraiConsole.logger("Downloading newest Protocol lib from Aliyun")
            downloadRequest(Links.libAliyun, version)
        }.getOrElse {
            kotlin.runCatching {
                MiraiConsole.logger("Downloading newest Protocol lib from JCenter")
                downloadRequest(Links.libJcenter, version)
            }.getOrElse { e ->
                MiraiConsole.logger("Failed to download Protocol lib, please seeking for help")
                e.printStackTrace()
                MiraiConsole.logger("Failed to download Protocol lib, please seeking for help")
                exitProcess(1)
            }
        }

        withContext(Dispatchers.IO) {
            stream.copyTo(fileStream)
            fileStream.flush()
        }

        fileStream = File(coresPath.absolutePath + "/" + "mirai-core-jvm-$version.jar").also {
            withContext(Dispatchers.IO) {
                it.createNewFile()
            }
        }.outputStream()


        stream = try {
            MiraiConsole.logger("Downloading newest Mirai Core from Aliyun")
            downloadRequest(Links.coreAliyun, version)
        } catch (ignored: Exception) {
            try {
                MiraiConsole.logger("Downloading newest Mirai Core from JCenter")
                downloadRequest(Links.coreJcenter, version)
            } catch (e: Exception) {
                MiraiConsole.logger("Failed to download Mirai Core, please seeking for help")
                e.printStackTrace()
                MiraiConsole.logger("Failed to download Mirai Core, please seeking for help")
                exitProcess(1)
            }
        }

        withContext(Dispatchers.IO) {
            stream.copyTo(fileStream)
            fileStream.flush()
        }
    }


    private fun loadCoreAndLib() {
        try {

            val coreFile = getCore()!!
            val protocolFile = getProtocolLib()!!

            MiraiConsole.logger("Core: $coreFile")
            MiraiConsole.logger("Protocol: $protocolFile")


            val classloader = URLClassLoader(
                arrayOf(coreFile.toURI().toURL(), protocolFile.toURI().toURL()),
                this.javaClass.classLoader
            )
            ClassLoader.getSystemClassLoader()
            // this.javaClass.classLoader.
            println(classloader.loadClass("net.mamoe.mirai.BotFactory"))
            println(classloader.loadClass("net.mamoe.mirai.qqandroid.QQAndroid"))
            println(classloader.loadClass("net.mamoe.mirai.utils.cryptor.ECDHJvmKt"))

            val a = classloader.loadClass("net.mamoe.mirai.qqandroid.QQAndroid").kotlin.objectInstance!!
            println(a::class.java)

            println(Class.forName("net.mamoe.mirai.qqandroid.QQAndroid"))

        } catch (e: ClassNotFoundException) {
            MiraiConsole.logger("Failed to load core, please seek for help")
            e.printStackTrace()
            MiraiConsole.logger("Failed to load core, please seek for help")
            exitProcess(1)
        }
    }


}


