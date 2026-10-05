plugins { application }
repositories { mavenCentral() }
dependencies {
    implementation("org.openstreetmap.osmosis:osmosis-core:0.49.2")
    implementation("org.openstreetmap.osmosis:osmosis-xml:0.49.2")
    implementation("org.mapsforge:mapsforge-map-writer:0.25.0")
}
application { mainClass.set("org.openstreetmap.osmosis.core.Osmosis") }
tasks.named<JavaExec>("run") { maxHeapSize="1g" }
