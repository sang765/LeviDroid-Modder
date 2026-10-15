package org.levimc.launcher.core.minecraft

/**
 * Loads Minecraft and its patch-injected libs by NAME inside a child
 * classloader namespace whose search path is the version lib dir. Patch
 * loader libs (MCPatch's libmtbinloader2.so) are built without DT_SONAME,
 * so System.load'ing them by absolute path registers them under the full
 * path and libminecraftpe.so's DT_NEEDED lookup by name fails, while the
 * version dir - holding every game lib - is not in the app namespace
 * search path.
 *
 * Invoked reflectively on a copy of this class loaded by the child
 * classloader; its System.loadLibrary calls therefore run against that
 * namespace. Must not reference any other launcher class.
 */
object PatchLibBootstrap {
    /** Returns null on success, or the name of the lib that failed. */
    @JvmStatic
    fun load(names: Array<String>): String? {
        for (name in names) {
            val shortName = name.removePrefix("lib").removeSuffix(".so")
            try {
                System.loadLibrary(shortName)
            } catch (e: UnsatisfiedLinkError) {
                return name
            }
        }
        return null
    }
}
