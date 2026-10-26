// Port of src/plugins/graph/projects.rs: `rtok graph projects`, the project registry from the command line.
package rtok.plugins.graph

import rtok.store.Project
import rtok.store.Store
import rtok.store.project
import rtok.store.projectByRoot

object Projects {
    /** `<id|path>`: a number naming a known project is its id, anything else is a directory. */
    fun resolve(store: Store, target: String): Project =
        target.toIntOrNull()?.let { store.project(it) } ?: store.projectByRoot(target)
            ?: throw IllegalStateException("no project `$target`; see `rtok graph projects`")
}
