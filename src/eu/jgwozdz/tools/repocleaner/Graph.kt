package eu.jgwozdz.tools.repocleaner

class GraphWorker(private val vertices: Map<GAV, AnalyzedArtifact>, private val edges: Set<Edge>) {

    fun calculateFullGraph(): DependenciesGraph {
        val directDependencies: Map<GAV, Collection<GAV>> = edges.groupBy({ it.from }, { it.to })
        val directDependendants: Map<GAV, Collection<GAV>> = edges.groupBy({ it.to }, { it.from })

        val distanceFromRoot: MutableMap<GAV, Int> = mutableMapOf()
        vertices.keys.forEach {
            calculate(it, directDependendants, distanceFromRoot)
        }

        return DependenciesGraph(vertices, edges, directDependencies, directDependendants, distanceFromRoot.toMap())
    }

    private fun calculate(vertex: GAV, edges: Map<GAV, Collection<GAV>>, distanceFromRoot: MutableMap<GAV, Int>) {
        if (distanceFromRoot.containsKey(vertex)) return
        distanceFromRoot[vertex] = 1000 // to avoid cycles in recursion (is that possible? should investigate)
        val minimumDependenciesDistance = edges[vertex].orEmpty()
                .onEach { calculate(it, edges, distanceFromRoot) }
                .mapNotNull { distanceFromRoot[it] }
                .min()
        distanceFromRoot[vertex] = (minimumDependenciesDistance ?: 0) + 1
    }

    fun collectDependencies(fullGraph: DependenciesGraph, gavsToCleanup: Collection<GAV>, collected: Set<GAV> = emptySet()): Set<GAV> {
        if (gavsToCleanup.isEmpty()) return emptySet()

//        report("gavsToCleanup", gavsToCleanup)
//        report("collected", collected)
//
        val result =  gavsToCleanup.toSet()
//        report("result", result)

        val dependencies = gavsToCleanup.flatMap { fullGraph.directDependencies[it].orEmpty() }.toSet()
//        report("dependencies", dependencies)

        val gavsToCleanup1 = dependencies - collected
//        report("gavsToCleanup1", gavsToCleanup1)

        val collected1 = collected + result
//        report("collected1", collected1)

        val result1 = result + collectDependencies(fullGraph, gavsToCleanup1, collected1)
//        report("result1", result1)

        return result1
    }

    private fun report(name: String, set: Collection<GAV>) {
        println("$name: ${set.size} elements: ${set.sortedBy { it -> it }.take(10)}")
    }

}

