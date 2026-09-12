pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "forestread-storage-lab"
include(":writer-schema") // Test substrate generated from the actual FN .sq/.sqm files.
// Explicit headless build against the working Rhizome sources. Does not publish
// artifacts or change the Android app's dependency resolution/registry.
val rhizomeCheckout = providers.gradleProperty("rhizomeCheckout").orNull
includeBuild(if (rhizomeCheckout == null) "../../../rhizome/client-kotlin" else "$rhizomeCheckout/client-kotlin")
