package eu.jgwozdz.tools.repocleaner

val BASEDIR = System.getenv("USERPROFILE") + "\\.m2\\repository"

val FOCUS = System.getProperty("nameToFocus", "jgwozdz")

val focusFilter: (Any) -> Boolean = { it.toString().contains(FOCUS) }
val filesToKeepFilter = { gav: GAV -> gav.version == "1.1.28-SNAPSHOT" }

val CLEANUP = System.getProperty("cleanUpErrors", "false") == "true"

fun main(args: Array<String>) {
    println("Scanning $BASEDIR...")

    val localRepoScanner = LocalRepoScanner(BASEDIR)
    val analyzer = Analyzer(BASEDIR)

    val pomsInRepo = localRepoScanner.scanLocalRepo(tickFunction = { print(".") })
    println()
    println("Found ${pomsInRepo.size} *.pom files")

    val artifacts: List<ScannedArtifact> = analyzer.analyzeAllPoms(pomsInRepo)

    artifacts.groupBy { it.status }
            .forEach { status, list -> println("$status: ${list.size}") }


    if (CLEANUP) {
        artifacts
            .filter { it.status == ScannedArtifact.Status.Failed }
            .forEach {
                println("Will try to remove $it")
                it.pomFile.parentFile.deleteRecursively()

            }
    }


    println("Building graph VERTICES...")
    val analyzedArtifacts = artifacts
        .filter { it.status == ScannedArtifact.Status.Analyzed }
        .map { analyzer.extract(it) }

    val vertices: Map<GAV, AnalyzedArtifact> = analyzedArtifacts
            .associateBy { it.gav }
    println("${vertices.size} vertices")

    println("Building graph EDGES pass 1 of 4... (dependencies)")
    val directDependencies: Set<Edge> = vertices.asSequence()
            .chunked(100)
        .map { it.map { (gav, artifact) -> gav to artifact.directDependencies.filter { vertices.containsKey(it) } } }
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
                    gav to artifact.parents
                }
            }
            .onEach { print(".") }
            .flatten()
            .flatMap { (gav, parents) -> parents.map { Edge(it, gav) }.asSequence() }
            .toSet()
    println(" found ${directParents.size} edges")

//    println("Building graph EDGES pass 3 of 4... (parents' dependencies)")
//    val parentsDependencies: Set<Edge> = vertices.keys.asSequence()
//            .chunked(100)
//            .map {
//                it.map { gav -> gav to findIndirectParents(gav, directParents) }
//                        .map { (gav: GAV, parents: Set<GAV>) -> gav to parents.flatMap { p -> directDependencies.filter { it.from == p }.map { it.to } } }
//            }
//            .onEach { print(".") }
//            .flatten()
//            .flatMap { (gav, dependencies) -> dependencies.map { Edge(gav, it) }.asSequence() }
//            .toSet()
//    println(" found ${parentsDependencies.size} edges")
//
//    val graphWorker = GraphWorker(vertices, directDependencies + parentsDependencies)
    val graphWorker = GraphWorker(vertices, directDependencies + directParents.map { Edge(it.to, it.from) })
    println("Calculating distances...")

    val fullGraph = graphWorker.calculateFullGraph()

    reportGraph(fullGraph)

    println("Multiple versions:")
    val gavsToCleanup = fullGraph.vertices.keys.filter(focusFilter)
    val gavsToKeep = fullGraph.vertices.keys.filter(filesToKeepFilter)

    val multipleVersion = gavsToCleanup
        .groupBy { Pair(it.groupId, it.artifactId) }
        .mapValues { entry -> entry.value.sortedByDescending { gav -> fullGraph.vertices[gav]?.pomFile?.lastModified() } }

    multipleVersion
        .forEach { (g, a), u -> println("$g:$a: ${u.map { it.version }}") }

    val allDependencies:Set<GAV> = graphWorker.collectDependencies(fullGraph, gavsToCleanup)
    val dependenciesToKeep = graphWorker.collectDependencies(fullGraph, gavsToKeep)

    val gavsToDelete = allDependencies - dependenciesToKeep

    println("Want to delete ${gavsToDelete.size} dependencies for ${(gavsToCleanup-gavsToKeep).size} artifacts")

    if (CLEANUP) {
        gavsToDelete.mapNotNull { fullGraph.vertices[it]?.pomFile }
            .forEach {
                println("Will try to remove `$it` with parent dir")
//                it. parentFile.deleteRecursively()

            }
    }


}

private fun reportGraph(fullGraph: DependenciesGraph) {
    fullGraph.distanceFromRoot.entries.groupBy { it.value }
        .toSortedMap()
        .forEach { distance, entries -> println("$distance (${entries.size})") } // : ${entries.map { it.key }}

    fullGraph.distanceFromRoot.filterValues { it == 1 }
        .forEach { println("${it.key}") }

    println("$FOCUS distances:")
    fullGraph.distanceFromRoot.filterKeys(focusFilter).forEach { println(it) }
    println("depends on $FOCUS:")
    fullGraph.directDependants.filterKeys(focusFilter).forEach { println(it) }
    println("$FOCUS depends on:")
    fullGraph.directDependencies.filterKeys(focusFilter).forEach { println(it) }
}

private fun findIndirectParents(gav: GAV, allDirectParents: Set<Edge>): Set<GAV> {
    val parents = allDirectParents.filter { it.to == gav }.map { it.from }
    val indirectParents = parents.flatMap { findIndirectParents(it, allDirectParents) }
    return parents.toSet() + indirectParents
}
