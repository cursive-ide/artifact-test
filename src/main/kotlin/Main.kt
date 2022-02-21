import java.io.File
import java.net.*
import java.util.*
import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.ModelBuilder
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.*
import org.eclipse.aether.repository.Proxy
import org.eclipse.aether.repository.ProxySelector
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferListener
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator

class ArtifactManager(
    private val localRepo: String = defaultLocalRepo(),
    repositories: List<RemoteRepository> = listOf(CENTRAL, CLOJARS)
) {

    data class Artifact(val groupId: String, val artifactId: String, val version: String) {
        fun coords() = "$groupId:$artifactId:$version"
    }

    private val system = newRepositorySystem()
    private val session = newSession(system)
    private val repositories = repositories.map { createRemoteRepository(it) }

    fun getArtifactFiles(vararg artifacts: Artifact): List<File> {
        val collectRequest = CollectRequest()
        artifacts.forEach { collectRequest.addDependency(dependency(it.coords())) }
        repositories.forEach { collectRequest.addRepository(it) }
        val node = system.collectDependencies(session, collectRequest).root

        val dependencyRequest = DependencyRequest()
        dependencyRequest.root = node

        system.resolveDependencies(session, dependencyRequest)

        val nlg = PreorderNodeListGenerator()
        node.accept(nlg)
        return nlg.files
    }

    /**
     * Returns versions from lowest to highest
     */
    fun getVersions(groupId: String, artifactId: String): List<String> {
        return getVersionRangeResult(groupId, artifactId).versions.map { it.toString() }
    }

    fun getLatestVersion(groupId: String, artifactId: String): String {
        return getVersionRangeResult(groupId, artifactId).highestVersion.toString()
    }

    private fun getVersionRangeResult(groupId: String, artifactId: String): VersionRangeResult {
        val artifact = DefaultArtifact("$groupId:$artifactId:(0,]")
        val request = VersionRangeRequest(artifact, repositories, null)
        return system.resolveVersionRange(session, request)
    }

    private fun newRepositorySystem(): RepositorySystem {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.setErrorHandler(ErrorHandler)
        locator.setServices(ModelBuilder::class.java, DefaultModelBuilderFactory().newInstance())
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)

        return locator.getService(RepositorySystem::class.java)
    }

    private fun newSession(system: RepositorySystem): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()

        session.localRepositoryManager = system.newLocalRepositoryManager(session, LocalRepository(localRepo))

        session.transferListener = object : TransferListener {
            private val transfers: MutableMap<String, TransferEvent> = mutableMapOf()

            override fun transferInitiated(e: TransferEvent) {}

            override fun transferStarted(e: TransferEvent) {
                update(e)
            }

            override fun transferProgressed(e: TransferEvent) {
                update(e)
            }

            override fun transferCorrupted(e: TransferEvent) {
                println("The transfer of ${e.resource.resourceName} from ${e.resource.repositoryUrl} was corrupted")
            }

            override fun transferSucceeded(e: TransferEvent) {
                remove(e)
            }

            override fun transferFailed(e: TransferEvent) {
                println("The transfer of ${e.resource.resourceName} from ${e.resource.repositoryUrl} failed")
                remove(e)
            }

            private fun update(e: TransferEvent) {
                transfers[e.resource.file.absolutePath] = e
                update()
            }

            private fun remove(e: TransferEvent) {
                transfers.remove(e.resource.file.absolutePath)
                update()
            }

            fun update() {
                if (transfers.isEmpty()) {
                    println("No transfers...")
                } else {
                    if (transfers.size == 1) {
                        val event = transfers.values.first()
                        println("Transferring ${event.resource.resourceName} from ${event.resource.repositoryUrl}")
                    } else {
                        val events = transfers.values
                        println("Transferring ${transfers.size} files...")
                    }
                }
            }
        }

        return session
    }

    fun createRemoteRepository(prototype: RemoteRepository) =
        RemoteRepository.Builder(prototype.id, prototype.contentType, prototype.url)
            .setProxy(session.proxySelector.getProxy(prototype))
            .build()

    internal object ErrorHandler : DefaultServiceLocator.ErrorHandler() {
        override fun serviceCreationFailed(type: Class<*>, impl: Class<*>, exception: Throwable) {
            println(
                "Could not initialize repository system${
                    if (RepositorySystem::class.java != type) {
                        ", service ${type.name} (${impl.name}) failed to initialize"
                    } else ""
                }: ${exception.message}"
            )
        }
    }

    companion object {
        private const val DEFAULT_REPOSITORY_PATH = ".m2/repository"

        val CENTRAL = repository("central", "https://repo1.maven.org/maven2/")
        val CLOJARS = repository("clojars", "https://repo.clojars.org")

        fun dependency(coords: String) = Dependency(DefaultArtifact(coords), "compile")
        fun repository(id: String, url: String) = RemoteRepository.Builder(id, "default", url).build()

        fun defaultLocalRepo(): String {
            val userHome = System.getProperty("user.home", null)
            return if (userHome != null)
                File(userHome, DEFAULT_REPOSITORY_PATH).absolutePath
            else
                File(DEFAULT_REPOSITORY_PATH).absolutePath
        }
    }
}

fun main(args: Array<String>) {
    val manager = ArtifactManager()
    val files = manager.getArtifactFiles(ArtifactManager.Artifact("org.clojure", "tools.deps.alpha", "0.12.1148"))
    println("Files:")
    for (file in files) {
        println("${file.absolutePath}")
    }
}
