package tk.glucodata

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Libre3SourceSecurityBuildIsolationTests {
    private val moduleRoot = File("").absoluteFile.let { working ->
        generateSequence(working) { it.parentFile }
            .firstOrNull { File(it, "build.gradle").exists() && File(it, "src/main/cpp/CMakeLists.txt").exists() }
            ?: working
    }

    @Test
    fun sourceEngineIsOptInAtBothBuildLayers() {
        val gradle = File(moduleRoot, "build.gradle").readText()
        val cmake = File(moduleRoot, "src/main/cpp/CMakeLists.txt").readText()

        assertTrue(gradle.contains("'EXPERIMENTAL_LIBRE3_SOURCE', 'false'"))
        assertTrue(gradle.contains("l3experimental"))
        assertTrue(gradle.contains("-DEXPERIMENTAL_LIBRE3_SOURCE=TRUE"))
        val experimentalBuild = gradle.substringAfter("l3experimental {")
            .substringBefore("releasedub {")
        assertTrue(experimentalBuild.contains("minifyEnabled false"))
        assertTrue(experimentalBuild.contains("shrinkResources false"))
        assertTrue(cmake.contains("option(EXPERIMENTAL_LIBRE3_SOURCE"))
        assertTrue(cmake.contains("if(EXPERIMENTAL_LIBRE3_SOURCE)"))
        assertFalse(cmake.substringBefore("if(EXPERIMENTAL_LIBRE3_SOURCE)")
            .contains("source_security_jni.cpp"))
    }

    @Test
    fun experimentalDisconnectPathCannotEraseLegacyAuthorization() {
        val callback = File(
            moduleRoot,
            "src/libre3/java/tk/glucodata/Libre3GattCallback.java",
        ).readText().replace(Regex("\\s+"), " ")

        assertTrue(callback.contains("if(!USE_SOURCE_SECURITY) Natives.setLibre3kAuth(sensorptr,null);"))
        assertTrue(callback.contains("ExperimentalLibre3AuthorizationStore.saveCandidate"))
        assertTrue(callback.contains("ExperimentalLibre3AuthorizationStore.saveVerified"))
    }
}
