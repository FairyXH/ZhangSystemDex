// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// ---------------------------------------------------------------------------
// Platform-aware AAPT2 override (see docs/BUILD_NOTES.md)
// ---------------------------------------------------------------------------
// On an aarch64 host running an x86_64 proot, aapt2 has to be invoked through a
// qemu wrapper script, otherwise it dies with SIGILL and Gradle reports
// "AAPT2 Daemon startup failed". That is a machine-specific setup: hardcoding it
// in gradle.properties leaks a local /opt path into the repo and breaks builds
// on Windows / macOS.
//
// Resolution order:
//   1. -Paapt2Override=<path>
//   2. environment variable AAPT2_WRAPPER
//   3. on Linux, /opt/aapt2wrap/aapt2 when present and executable
// Nothing matched -> no property is injected and AGP uses its Maven aapt2.
private fun resolveAapt2Override(): String? {
    val explicit = providers.gradleProperty("aapt2Override").orNull
        ?: providers.environmentVariable("AAPT2_WRAPPER").orNull
    if (explicit != null) return explicit

    val onLinux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")
    if (!onLinux) return null
    val defaultWrapper = File("/opt/aapt2wrap/aapt2")
    return defaultWrapper.takeIf { it.isFile && it.canExecute() }?.absolutePath
}

resolveAapt2Override()?.let { raw ->
    val wrapper = File(raw).absoluteFile
    require(wrapper.isFile) {
        "aapt2 override points to a missing file: ${wrapper.path} (check -Paapt2Override / AAPT2_WRAPPER)"
    }
    logger.lifecycle("aapt2 override -> ${wrapper.path}")
    // AGP reads project.findProperty("android.aapt2FromMavenOverride"), which must
    // be visible before :app is configured, so publish it as an extra property.
    subprojects {
        extra["android.aapt2FromMavenOverride"] = wrapper.path
    }
}
