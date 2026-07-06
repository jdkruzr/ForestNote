package com.forestnote.core.format

/**
 * Where the library database file should live for this launch. Decided purely from three
 * booleans so the "create if not exist, migrate if needed" scaffolding is unit-testable off-device;
 * [StorageLocation] supplies the booleans (from real File probes) and acts on the result.
 */
enum class StorageChoice {
    /** Open (or create) the DB directly in the external `/sdcard/ForestNote` dir. */
    USE_EXTERNAL,

    /** Copy the private-storage DB out to external once, then open external. */
    MIGRATE_THEN_EXTERNAL,

    /** External storage is unusable — keep using the app-private `databases/` dir (legacy behavior). */
    USE_PRIVATE,
}

/**
 * Pure decision for the datastore relocation (FCIS, like [FolderPathLogic]/[RecycleBinLogic]).
 *
 * The datastore moved from private app storage to `/sdcard/ForestNote/` so it survives an
 * uninstall/reinstall (chiefly the debug→release signing-key swap, which forces a reinstall) and is
 * inspectable from Termux. This function encodes the three cases the resolver must handle; the
 * only hard guarantee is that an unwritable `/sdcard` degrades to private storage rather than
 * bricking the app.
 */
object StorageLocationLogic {
    fun decide(
        externalWritable: Boolean,
        externalDbExists: Boolean,
        privateDbExists: Boolean,
    ): StorageChoice = when {
        !externalWritable -> StorageChoice.USE_PRIVATE
        externalDbExists -> StorageChoice.USE_EXTERNAL
        privateDbExists -> StorageChoice.MIGRATE_THEN_EXTERNAL
        else -> StorageChoice.USE_EXTERNAL
    }
}
