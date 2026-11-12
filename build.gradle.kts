plugins {
  kotlin("jvm")
  kotlin("plugin.spring")
  id("com.vanniktech.maven.publish") version "0.33.0"
  id("java-test-fixtures")
  id("com.diffplug.spotless") version "8.11.0"
  jacoco
}

val springBootVersion = "4.1.2"
val swaggerAnnotationsVersion = "2.2.49"
val kotlinVersion: String by project
val packageId = "database-tools"
val packageName = "Database Tools"
group = "io.github.kostack"
version = providers.gradleProperty("projectVersion").getOrElse("1.0.0")
description =
  "Database Tools is a library that provides reactive pagination, query mapping, join queries and " +
  "database utilities for MongoDB and R2DBC in Spring applications."

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

repositories {
  mavenCentral()
}

dependencies {
  // Build and tests only: the Spring Boot BOM manages all versions below and is not published.
  val springBootBom = platform("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
  compileOnly(springBootBom)
  testImplementation(springBootBom)
  testFixturesImplementation(springBootBom)

  // Provided by the consuming application; not published as dependencies.
  listOf(
    "org.springframework.boot:spring-boot-starter-webflux",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core",
    "org.jetbrains.kotlinx:kotlinx-coroutines-reactor",
    "org.jetbrains.kotlin:kotlin-reflect"
  ).forEach {
    compileOnly(it)
    testImplementation(it)
    testFixturesImplementation(it)
  }

  // Optional integrations, provided by the consuming application when it uses them.
  listOf(
    "org.springframework.boot:spring-boot-starter-data-mongodb-reactive",
    "org.springframework.boot:spring-boot-starter-data-r2dbc",
    "io.asyncer:r2dbc-mysql",
    "io.swagger.core.v3:swagger-annotations:$swaggerAnnotationsVersion"
  ).forEach {
    compileOnly(it)
    testImplementation(it)
  }

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
  testImplementation("io.projectreactor:reactor-test")
  testImplementation("io.mockk:mockk:1.14.11")
  testImplementation(kotlin("test"))
  testImplementation("io.r2dbc:r2dbc-h2")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("org.testcontainers:testcontainers-mysql")
  // MySQLContainer waits for readiness over JDBC
  testRuntimeOnly("com.mysql:mysql-connector-j")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  compilerOptions {
    freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
  }
}

// Test fixtures are for this project's tests only; don't publish them.
(components["java"] as AdhocComponentWithVariants).apply {
  withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
  withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
}
// The publish plugin adds the fixtures' sources variant late, so skip it after evaluation.
afterEvaluate {
  configurations.findByName("testFixturesSourcesElements")?.let {
    (components["java"] as AdhocComponentWithVariants).withVariantsFromConfiguration(it) { skip() }
  }
}

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
  // Needed by MockK's inline mocking agent on recent JDKs.
  jvmArgs("-XX:+EnableDynamicAgentLoading")

  testLogging {
    events("passed", "skipped", "failed")
  }
}

tasks.jacocoTestReport {
  reports {
    xml.required = true
    html.required = true
    csv.required = false
    html.outputLocation = layout.buildDirectory.dir("jacocoHtml")
    xml.outputLocation = layout.buildDirectory.file("jacocoReport.xml")
  }
}

tasks.jacocoTestCoverageVerification {
  violationRules {
    rule {
      limit {
        minimum = "0.6".toBigDecimal()
      }
    }
  }
}

tasks.check {
  dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.register("installGitHook") {
  group = "build setup"
  description = "Installs a git pre-commit hook to enforce formatting rules"
  doLast {
    val hookSource = file("scripts/pre-commit")
    val hooksDir = file(".git/hooks")
    val hookTarget = hooksDir.resolve("pre-commit")
    check(hookSource.isFile) { "Git hook source not found: ${hookSource.path}" }
    check(hooksDir.isDirectory) { "Git hooks directory not found: ${hooksDir.path}" }

    if (!hookTarget.exists()) {
      hookSource.copyTo(hookTarget, overwrite = false)
    }
    hookTarget.setExecutable(true)
  }
}

spotless {
  kotlin {
    target("src/**/*.kt")
    ktlint()
  }
  kotlinGradle {
    target("*.kts")
    ktlint()
  }
}

mavenPublishing {
  publishToMavenCentral()
  signAllPublications()

  coordinates(
    groupId = group.toString(),
    artifactId = packageId,
    version = version.toString()
  )

  pom {
    name = packageName
    description = project.description
    url = "https://github.com/kostack/$packageId"

    licenses {
      license {
        name = "MIT License"
        url = "https://opensource.org/licenses/MIT"
      }
    }

    developers {
      developer {
        id = "zender"
        name = "Nikolay Georgiev"
        url = "https://github.com/zender"
      }
    }

    scm {
      connection = "scm:git:git://github.com/kostack/$packageId.git"
      developerConnection = "scm:git:ssh://github.com/kostack/$packageId.git"
      url = "https://github.com/kostack/$packageId"
    }
  }
}
