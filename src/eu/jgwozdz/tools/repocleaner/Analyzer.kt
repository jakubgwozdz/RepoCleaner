package eu.jgwozdz.tools.repocleaner

import org.apache.maven.model.building.DefaultModelBuilderFactory
import org.apache.maven.model.building.ModelBuilder
import org.apache.maven.model.building.ModelBuildingRequest
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.impl.ArtifactDescriptorReader
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.internal.impl.SimpleLocalRepositoryManagerFactory
import org.eclipse.aether.repository.LocalRepository
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class LocalRepoScanner(private val pathToRepo: String) {
    fun scanLocalRepo(progressListener: ProgressListener = IgnoringProgressListener(),
                      tickFunction: (() -> Unit) = {}): List<File> {
        return File(pathToRepo).walkTopDown()
                .filter { it.isFile && it.toString().endsWith(".pom", true) }
                .chunked(100)
                .onEach { progressListener.tick(100); tickFunction.invoke() }
                .flatten()
                .toList()
    }

}

class Analyzer(pathToRepo: String) {

    private val session: DefaultRepositorySystemSession = MavenRepositorySystemUtils.newSession()
    private val serviceLocator: DefaultServiceLocator = MavenRepositorySystemUtils.newServiceLocator()
    private val descriptorReader: ArtifactDescriptorReader
    private val modelBuilder: ModelBuilder

    private val baseDirPath: Path = Paths.get(pathToRepo)

    init {

        val localRepository = LocalRepository(baseDirPath.toFile())

        session.localRepositoryManager = SimpleLocalRepositoryManagerFactory().newInstance(session, localRepository)
        session.setReadOnly()

        descriptorReader = serviceLocator.getService(ArtifactDescriptorReader::class.java)

        modelBuilder = serviceLocator.getService(ModelBuilder::class.java) ?: DefaultModelBuilderFactory().newInstance()

    }

    fun extractGAV(pomFile: File): ScannedArtifact {
        val relativePath = baseDirPath.relativize(pomFile.toPath())
        val pathElems = relativePath.toMutableList().asReversed()
        val pom = pathElems.removeAt(0)
        assert(pom.toString().endsWith(".pom"), { "filename `$pom` does not end with `.pom`" })

        val v = pathElems.removeAt(0).toString()
        val a = pathElems.removeAt(0).toString()
        val g = pathElems.asReversed().joinToString(".")

        return ScannedArtifact(GAV(g, a, v), pomFile)
    }

    fun validateArtifactFile(scannedArtifact: ScannedArtifact) {
        val artifactResult = session.localRepositoryManager.find(session, scannedArtifact.localArtifactRequest())
        assert(artifactResult.isAvailable, { "`${scannedArtifact.gav}` not found" })
        assert(artifactResult.file == scannedArtifact.pomFile, { "`${scannedArtifact.gav}` file mismatch. Expected `${scannedArtifact.pomFile}`, actual `${artifactResult.file}`" })
        scannedArtifact.validate(artifactResult)
    }

    fun readRawModel(scannedArtifact: ScannedArtifact) {
        val pomFile = scannedArtifact.pomFile
        val rawModel = modelBuilder.buildRawModel(pomFile, ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL, false)
        assert(rawModel.get() != null)
        scannedArtifact.addRawModel(rawModel.get())
    }

    fun readDependencies(scannedArtifact: ScannedArtifact) {
        val artifactDescriptorResult = descriptorReader.readArtifactDescriptor(session, scannedArtifact.artifactDescriptorRequest())
        scannedArtifact.addDescriptor(artifactDescriptorResult)
    }

    fun collectDependenciesGAVs(analyzedArtifact: AnalyzedArtifact): Set<GAV> {
        val directDependencies: Iterable<GAV> = analyzedArtifact.artifactDescriptorResult.dependencies.orEmpty()
                .mapNotNull { it.artifact }
                .map { GAV(it.groupId, it.artifactId, it.version) }
        val managedDependencies: Iterable<GAV> = analyzedArtifact.artifactDescriptorResult.managedDependencies.orEmpty()
                .mapNotNull { it.artifact }
                .map { GAV(it.groupId, it.artifactId, it.version) }

        return emptySet<GAV>() + directDependencies + managedDependencies
    }

    /**
     * returns set of GAVs that depends on artifact. currently it is parent only
     */
    fun collectDependantsGAVs(analyzedArtifact: AnalyzedArtifact): Set<GAV> {

        val parent: GAV? = analyzedArtifact.rawModel.parent?.let { GAV(it.groupId, it.artifactId, it.version) }

        return parent?.let { setOf(it) }.orEmpty()
    }

}
