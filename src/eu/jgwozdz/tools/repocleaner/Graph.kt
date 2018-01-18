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

}

