package dev.codeassist.ndk

import dev.ide.model.FacetData
import dev.ide.model.MODULE_SOURCES
import dev.ide.model.Module
import dev.ide.model.WORKSPACE_SERVICE
import dev.ide.platform.log.Logger
import dev.ide.plugin.action.ActionContext
import dev.ide.plugin.action.ActionPlaces
import dev.ide.plugin.action.ActionResult
import dev.ide.plugin.action.SimpleAction
import java.nio.file.Paths

/**
 * "Import C/C++ Build" on a module row: reads the module's CMakeLists.txt or Android.mk with
 * [NativeBuildImporter] and writes the `[ndk]` table that builds the same library.
 *
 * Shown only where there is something to import. What the importer could not express is reported rather than
 * guessed at: the message carries the first few warnings and the log carries all of them.
 */
internal object NdkImportAction {

    fun importBuild(log: Logger) = SimpleAction(
        id = "dev.codeassist.ndk.importBuild",
        text = "Import C/C++ Build (CMake / Android.mk)",
        places = setOf(ActionPlaces.FILE_CONTEXT),
        iconId = "code",
        visible = { ctx -> moduleAt(ctx)?.let { NativeBuildImporter.findScript(Paths.get(it.dir.path)) != null } == true },
    ) { ctx ->
        val module = moduleAt(ctx) ?: return@SimpleAction ActionResult.message("Not a module")
        val sources = ctx.workspaceServices.getServiceOrNull(MODULE_SOURCES)
            ?: return@SimpleAction ActionResult.message("This IDE cannot change the module's configuration")

        when (val result = NativeBuildImporter.import(Paths.get(module.dir.path))) {
            is NativeImport.Failed -> {
                result.warnings.forEach { log.warn("import: $it") }
                log.warn("import of ${module.name} failed: ${result.reason}")
                ActionResult.message(result.reason)
            }

            is NativeImport.Imported -> {
                if (!sources.setFacetData(module.name, FacetData("ndk", NdkFacetCodec.encode(result.facet)))) {
                    return@SimpleAction ActionResult.message("Could not save the [ndk] table for ${module.name}")
                }
                result.warnings.forEach { log.warn("import: $it") }
                log.info("imported ${result.script} into ${module.name}")
                val head = "Imported ${result.script.fileName}: library '${result.facet.libraryName}' from " +
                    "${result.facet.sourceDirs.joinToString()}."
                val w = result.warnings
                ActionResult.message(
                    if (w.isEmpty()) head
                    else "$head ${w.size} warning(s): ${w.take(3).joinToString(" ")}" + if (w.size > 3) " (see the log)" else ""
                )
            }
        }
    }

    /** The module whose own directory the tree row is, through the action's workspace (SPI 3.1.0). */
    private fun moduleAt(ctx: ActionContext): Module? {
        val path = ctx.contextPath?.trimEnd('/') ?: return null
        val workspace = try {
            ctx.workspaceServices.getServiceOrNull(WORKSPACE_SERVICE)
        } catch (e: LinkageError) {
            null
        } ?: return null
        return workspace.projects.flatMap { it.modules }.firstOrNull { it.dir.path.trimEnd('/') == path }
    }
}
