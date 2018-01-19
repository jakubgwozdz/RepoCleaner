package eu.jgwozdz.tools.repocleaner

import org.apache.maven.model.Model
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.repository.LocalArtifactRequest
import org.eclipse.aether.repository.LocalArtifactResult
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.resolution.ArtifactDescriptorResult
import java.io.File

data class GAV(val groupId: String, val artifactId: String, val version: String) : Comparable<GAV> {
    override fun compareTo(other: GAV): Int = this.toString().compareTo(other.toString())

    override fun toString(): String {
        return "$groupId:$artifactId:$version"
    }
}

data class ScannedArtifact(val gav: GAV, val pomFile: File) {
    private val artifact: Artifact = DefaultArtifact(gav.groupId, gav.artifactId, "pom", gav.version)

    var status: Status = Status.Created
    var localArtifactResult: LocalArtifactResult? = null
    var artifactDescriptorResult: ArtifactDescriptorResult? = null
    var rawModel: Model? = null

    enum class Status {
        Created,
        Validated,
        Analyzed,
        Failed
    }

    fun validate(localArtifactResult: LocalArtifactResult) {
        this.localArtifactResult = localArtifactResult
        status = Status.Validated
        artifact.file = localArtifactResult.file
    }

    fun addDescriptor(descriptorResult: ArtifactDescriptorResult) {
        this.artifactDescriptorResult = descriptorResult
        if (artifactDescriptorResult != null && rawModel != null) status = Status.Analyzed
    }

    fun addRawModel(rawModel: Model?) {
        this.rawModel = rawModel
        if (this.rawModel == null) status = Status.Failed
        if (artifactDescriptorResult != null && this.rawModel != null) status = Status.Analyzed
    }

    fun localArtifactRequest() = LocalArtifactRequest(artifact, null, null)

    fun artifactDescriptorRequest() = ArtifactDescriptorRequest(artifact, null, null)
    fun markAsFailed(problem: String) {
        status = Status.Failed
    }

}

data class AnalyzedArtifact(val gav: GAV, val pomFile: File,
                            val directDependencies: Set<GAV>,
                            val parents: Set<GAV>)

data class Edge(val from: GAV, val to: GAV)

data class DependenciesGraph(val vertices: Map<GAV, AnalyzedArtifact>,
                             val edges: Collection<Edge>,
                             val directDependencies: Map<GAV, Collection<GAV>>,
                             val directDependants: Map<GAV, Collection<GAV>>,
//                             val indirectDependencies: Map<GAV, Collection<GAV>>,
//                             val indirectDependants: Map<GAV, Collection<GAV>>,
                             val distanceFromRoot: Map<GAV, Int>
                             )