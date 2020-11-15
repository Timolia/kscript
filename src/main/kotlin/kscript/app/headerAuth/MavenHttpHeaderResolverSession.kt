package kscript.app.headerAuth


import org.apache.commons.io.filefilter.NameFileFilter
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.apache.maven.settings.Settings
import org.apache.maven.settings.SettingsUtils
import org.apache.maven.settings.TrackableBase
import org.apache.maven.settings.building.*
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyFilter
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.Proxy
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.ArtifactResult
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.DependencyResult
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.filter.DependencyFilterUtils
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.util.repository.DefaultMirrorSelector
import org.eclipse.aether.util.repository.DefaultProxySelector
import java.io.File
import java.io.FileFilter
import java.util.*
import java.util.Collections.singletonList
import kotlin.collections.HashMap
import kotlin.script.experimental.dependencies.maven.impl.mavenCentral

/**
 * Alternative Implementation of AetherResolveSession (kotlin.script.experimental.dependencies.maven.impl)
 * Used, but not modified methods see below in FromAetherResolveSession::class
 */
class MavenHttpHeaderResolverSession(
        localRepo: File = File(File(System.getProperty("user.home")!!, ".m2"), "repository"),
        remoteRepos: List<RemoteRepository> = listOf()
) {

    private val originalAetherResolverSession = OriginalAetherResolveSession(localRepo, remoteRepos)


    fun resolve(root: Artifact, scope: String, filter: DependencyFilter? = null): List<Artifact>? {

        for (remote in originalAetherResolverSession.remotes) {

            val repositorySystemSession = createDefaultSystemSession()

            val authentication = remote.authentication
            if (authentication is HttpHeaderAuthentication) {
                val propertyKey = "aether.connector.http.headers"
                val newHeaders = (repositorySystemSession.configProperties[propertyKey] as? Map<String, String>
                    ?: HashMap<Any, Any>())
                    .toMutableMap()
                    .apply { put(authentication.headerKey, authentication.headerValue) }

                repositorySystemSession.setConfigProperty(propertyKey, newHeaders)
            }

            val request = CollectRequest().apply {
                this.root = Dependency(root, scope)
                this.repositories = singletonList(remote)
            }

            val fetchResult = fetch(
                originalAetherResolverSession.repositorySystem,
                repositorySystemSession,
                DependencyRequest(request, filter ?: DependencyFilterUtils.classpathFilter(scope))
            )

            if (fetchResult is FetchResult.Success) {
                return fetchResult.artifacts
            }
        }

        val triedRemotes = originalAetherResolverSession.remotes.map { it.url }
        throw RuntimeException("Could not resolve $root for remotes $triedRemotes")
    }

    private fun fetch(system: RepositorySystem, session: RepositorySystemSession, dreq: DependencyRequest): FetchResult {
        return try {
            var results: List<Artifact>
            synchronized(this) {
                results = system.resolveDependencies(session, dreq)
                        .artifactResults
                        .map { it.artifact }
            }
            FetchResult.Success(results)
        } catch (ex: Exception) {
            FetchResult.DependencyResolutionException(DependencyResult(dreq), ex)
        }
    }

    sealed class FetchResult {
        data class Success(val artifacts: List<Artifact>) : FetchResult()
        data class DependencyResolutionException(val result: DependencyResult, val exception: Exception) : FetchResult()
    }

    private fun createDefaultSystemSession(): DefaultRepositorySystemSession {
        val localRepo = LocalRepository(originalAetherResolverSession.localRepo.absolutePath)
        return MavenRepositorySystemUtils.newSession().also {
            it.localRepositoryManager = originalAetherResolverSession.repositorySystem.newLocalRepositoryManager(it, localRepo)
        }
    }



    /**
     * Original class AetherResolveSession from kotlin.script.experimental.dependencies.maven
     * without private modifier for methods and properties
     */
    private inner class OriginalAetherResolveSession(
        val localRepo: File = File(File(System.getProperty("user.home")!!, ".m2"), "repository"),
        remoteRepos: List<RemoteRepository> = listOf(mavenCentral)
    ) {

        val remotes by lazy {
            val proxySelector = settings.getActiveProxy()?.let { proxy ->
                val selector = DefaultProxySelector()
                val auth = with (AuthenticationBuilder()) {
                    addUsername(proxy.username)
                    addPassword(proxy.password)
                    build()
                }
                selector.add(
                    Proxy(
                        proxy.protocol,
                        proxy.host,
                        proxy.port,
                        auth
                    ), proxy.nonProxyHosts
                )
                selector
            }
            val mirrorSelector = getMirrorSelector()
            remoteRepos.mapNotNull {
                val builder = RemoteRepository.Builder(it)
                if (proxySelector != null) {
                    builder.setProxy(proxySelector.getProxy(builder.build()))
                }
                val built = builder.build()
                if (!built.protocol.matches(Regex("https?|file"))) {
                    //Logger.warn(
                    //        this,
                    //        "%s ignored (only S3, HTTP/S, and FILE are supported)",
                    //        repo
                    //);
                    null
                } else {
                    mirrorSelector.getMirror(built) ?: built
                }
            }
        }

        val repositorySystem: RepositorySystem by lazy {
            val locator = MavenRepositorySystemUtils.newServiceLocator()
            locator.addService(
                RepositoryConnectorFactory::class.java,
                BasicRepositoryConnectorFactory::class.java
            )
            locator.addService(
                TransporterFactory::class.java,
                FileTransporterFactory::class.java
            )
            locator.addService(
                TransporterFactory::class.java,
                HttpTransporterFactory::class.java
            )
            locator.getService(RepositorySystem::class.java)
        }

        val repositorySystemSession: RepositorySystemSession by lazy {
            val localRepo = LocalRepository(localRepo.absolutePath)
            MavenRepositorySystemUtils.newSession().also {
                it.localRepositoryManager = repositorySystem.newLocalRepositoryManager(it, localRepo)
            }
        }

        fun resolve(coordinates: String, scope: String, filter: DependencyFilter? = null): List<Artifact>? =
            resolve(DefaultArtifact(coordinates), scope, filter)

        fun resolve(root: Artifact, scope: String, filter: DependencyFilter? = null): List<Artifact>? {

            return fetch(
                repositorySystem,
                repositorySystemSession,
                DependencyRequest(
                    request(Dependency(root, scope)),
                    filter ?: DependencyFilterUtils.classpathFilter(scope)
                )
            )
        }

        fun request(root: Dependency): CollectRequest? {
            val request = CollectRequest()
            request.root = root
            for (repo in remotes) {
                request.addRepository(repo)
            }
            return request
        }

        fun fetch(system: RepositorySystem, session: RepositorySystemSession, dreq: DependencyRequest): List<Artifact>? {
            val deps: MutableList<Artifact> = LinkedList()
            try {
                var results: Collection<ArtifactResult>
                synchronized(this) {
                    results = system.resolveDependencies(session, dreq)
                        .artifactResults
                }
                for (res in results) {
                    deps.add(res.artifact)
                }
                // @checkstyle IllegalCatch (1 line)
            } catch (ex: Exception) {
                throw DependencyResolutionException(
                    DependencyResult(dreq),
                    IllegalArgumentException( //Logger.format(
                        //        "failed to load '%s' from %[list]s into %s",
                        //        dreq.getCollectRequest().getRoot(),
                        //        Aether.reps(dreq.getCollectRequest().getRepositories()),
                        //        session.getLocalRepositoryManager()
                        //                .getRepository()
                        //                .getBasedir()
                        //),
                        ex
                    )
                )
            }
            return deps
        }

        fun getMirrorSelector(): DefaultMirrorSelector {
            val selector = DefaultMirrorSelector()
            val mirrors = settings.mirrors
            if (mirrors != null) {
                for (mirror in mirrors) {
                    selector.add(
                        mirror.id, mirror.url, mirror.layout, false,
                        mirror.mirrorOf, mirror.mirrorOfLayouts
                    )
                }
            }
            return selector
        }

        val settings: Settings by lazy {
            val builder: SettingsBuilder = DefaultSettingsBuilderFactory().newInstance()
            val request: SettingsBuildingRequest = DefaultSettingsBuildingRequest()
            val user = System.getProperty("org.apache.maven.user-settings")
            if (user == null) {
                request.userSettingsFile = File(
                    File(System.getProperty("user.home")).absoluteFile,
                    "/.m2/settings.xml"
                )
            } else {
                request.userSettingsFile = File(user)
            }
            val global = System.getProperty("org.apache.maven.global-settings")
            if (global != null) {
                request.globalSettingsFile = File(global)
            }
            val result: SettingsBuildingResult
            result = try {
                builder.build(request)
            } catch (ex: SettingsBuildingException) {
                throw IllegalStateException(ex)
            }
            this.invokers(builder, result)
        }

        fun invokers(
            builder: SettingsBuilder,
            result: SettingsBuildingResult
        ): Settings {
            var main = result.effectiveSettings
            val files = File(System.getProperty("user.dir"))
                .parentFile.listFiles(
                    NameFileFilter("interpolated-settings.xml") as FileFilter
                )
            if (files.size == 1) {
                val irequest =
                    DefaultSettingsBuildingRequest()
                irequest.userSettingsFile = files[0]
                main = try {
                    val isettings = builder.build(irequest)
                        .effectiveSettings
                    SettingsUtils.merge(isettings, main, TrackableBase.USER_LEVEL)
                    isettings
                } catch (ex: SettingsBuildingException) {
                    throw java.lang.IllegalStateException(ex)
                }
            }
            return main
        }
    }

}