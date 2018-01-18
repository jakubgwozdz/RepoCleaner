package eu.jgwozdz.tools.repocleaner

interface ProgressListener {
    fun tick(count: Int = 100)
}

class IgnoringProgressListener : ProgressListener {
    override fun tick(count: Int) { }
}

