package eu.jgwozdz.tools.repocleaner

import java.util.stream.Collectors.toSet

const val BASEDIR = "C:\\Users\\Jakub\\.m2\\repository"

fun main(args: Array<String>) {
    println("Scanning $BASEDIR...")

    val localRepoScanner = LocalRepoScanner(BASEDIR)
    val analyzer = Analyzer(BASEDIR)

    val pomsInRepo = localRepoScanner.scanLocalRepo(tickFunction = { print(".") })
    println()
    println("Found ${pomsInRepo.size} *.pom files")

    println("Extracting GAVs...")
    val artifacts: List<ScannedArtifact> = pomsInRepo.asSequence()
            .chunked(100)
            .map { it.map { analyzer.extractGAV(it) } }
            .onEach { print(".") }
            .flatten()
            .toList()
    println()

    println("Validating GAVs...")
    artifacts.asSequence()
            .chunked(100)
            .onEach { it.forEach { analyzer.validateArtifactFile(it) } }
            .onEach { print(".") }
            .flatten()
            .toList()
    println()

    println("Reading raw models...")
    artifacts.asSequence()
            .chunked(100)
            .onEach { it.filter { it.status == ScannedArtifact.Status.Validated }.forEach { analyzer.readRawModel(it) } }
            .onEach { print(".") }
            .flatten()
            .toList()
    println()

    println("Analyzing dependencies...")
    artifacts.asSequence()
            .chunked(100)
            .onEach { it.filter { it.status == ScannedArtifact.Status.Validated }.forEach { analyzer.readDependencies(it) } }
            .onEach { print(".") }
            .flatten()
            .toList()
    println()

    artifacts.groupBy { it.status }
            .forEach { status, list -> println("$status: ${list.size}") }

    println("Building graph VERTICES...")
    val vertices: Map<GAV, AnalyzedArtifact> = artifacts
            .filter { it.status == ScannedArtifact.Status.Analyzed }
            .map { AnalyzedArtifact(it.gav, it.pomFile, it.artifactDescriptorResult!!, it.rawModel!!) }
            .associateBy { it.gav }
    println("${vertices.size} vertices")

    println("Building graph EDGES pass 1 of 4... (dependencies)")
    val directDependencies: Set<Edge> = vertices.asSequence()
            .chunked(100)
            .map { it.map { (gav, artifact) -> gav to analyzer.collectDependenciesGAVs(artifact).filter { vertices.containsKey(it) } } }
            .onEach { print(".") }
            .flatten()
            .flatMap { (gav, dependencies) -> dependencies.map { Edge(gav, it) }.asSequence() }
            .toSet()
    println(" found ${directDependencies.size} edges")

    println("Building graph EDGES pass 2 of 4... (direct parents)")
    val directParents: Set<Edge> = vertices.asSequence()
            .chunked(100)
            .map {
                it.map { (gav, artifact) ->
                    gav to findDirectParents(artifact, analyzer, vertices)
                }
            }
            .onEach { print(".") }
            .flatten()
            .flatMap { (gav, parents) -> parents.map { Edge(it, gav) }.asSequence() }
            .toSet()
    println(" found ${directParents.size} edges")

    println("Building graph EDGES pass 3 of 4... (parents' dependencies)")
    val parentsDependencies: Set<Edge> = vertices.keys.asSequence()
            .chunked(100)
            .map {
                it.map { gav -> gav to findIndirectParents(gav, directParents) }
                        .map { (gav: GAV, parents: Set<GAV>) -> gav to parents.flatMap { p -> directDependencies.filter { it.from == p }.map { it.to } } }
            }
            .onEach { print(".") }
            .flatten()
            .flatMap { (gav, dependencies) -> dependencies.map { Edge(gav, it) }.asSequence() }
            .toSet()
    println(" found ${parentsDependencies.size} edges")

    val graphWorker = GraphWorker(vertices, directDependencies + parentsDependencies)
    println("Calculating distances...")

    val fullGraph = graphWorker.calculateFullGraph()

    fullGraph.distanceFromRoot.entries.groupBy { it.value }
            .toSortedMap()
            .forEach { distance, entries -> println("$distance (${entries.size})") } // : ${entries.map { it.key }}

    fullGraph.distanceFromRoot.filterValues { it == 1 }
            .forEach { println("${it.key}") }

    println("jgwozdz distances:")
    fullGraph.distanceFromRoot.filterKeys { it.toString().contains("jgwozdz") }.forEach { println(it) }
    println("depends on jgwozdz:")
    fullGraph.directDependants.filterKeys { it.toString().contains("jgwozdz") }.forEach { println(it) }
    println("jgwozdz depends on:")
    fullGraph.directDependencies.filterKeys { it.toString().contains("jgwozdz") }.forEach { println(it) }

}

private fun findIndirectParents(gav: GAV, allDirectParents: Set<Edge>): Set<GAV> {
    val parents = allDirectParents.filter { it.to == gav }.map { it.from }
    val indirectParents = parents.flatMap { findIndirectParents(it, allDirectParents) }
    return parents.toSet() + indirectParents
}

private fun findDirectParents(artifact: AnalyzedArtifact, analyzer: Analyzer, vertices: Map<GAV, AnalyzedArtifact>) =
        analyzer.collectDependantsGAVs(artifact).filter { vertices.containsKey(it) }

